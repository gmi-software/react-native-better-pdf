package com.margelo.nitro.betterpdf

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.margelo.nitro.core.ArrayBuffer
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch

/**
 * Turns a [NativePdfSource] into a seekable [ParcelFileDescriptor] (plus its
 * file, when there is one; see [ResolvedSource]), which is
 * what [android.graphics.pdf.PdfRenderer] requires. Runs on the document's
 * worker thread; every method here may block.
 *
 * Remote documents come from the process-wide [DocumentCache].
 */
class PdfSourceResolver(private val context: Context) {
  @Volatile
  private var subscription: DocumentCache.Subscription? = null

  @Volatile
  private var cancelled = false
  private val remoteDone = CountDownLatch(1)
  private val contentCancellation = CancellationSignal()

  /** Aborts an in-flight download or copy; [open] then throws [InterruptedException]. Safe from any thread. */
  fun cancel() {
    cancelled = true
    subscription?.cancel()
    remoteDone.countDown()
    contentCancellation.cancel()
  }

  /**
   * Opens [source]. For remote sources that are downloaded (not cached),
   * [onProgress] receives every chunk on a download thread, and for
   * `content://` sources that are copied, on the calling thread; callers throttle.
   */
  fun open(
    source: NativePdfSource,
    onProgress: (loadedBytes: Long, totalBytes: Long?, isComplete: Boolean) -> Unit,
  ): ResolvedSource {
    // Cancelled while waiting for its turn among a document's sources.
    if (cancelled) throw InterruptedException()
    source.base64?.let { return openBase64(it) }
    val uri = source.uri
    if (uri.isBlank()) throw invalidSource(uri)
    if (uri.startsWith("/")) return openFile(File(uri))

    val parsed = Uri.parse(uri)
    return when (parsed.scheme?.lowercase()) {
      "file" -> openFile(File(parsed.path ?: throw invalidSource(uri)))
      "http", "https" -> openRemote(source, onProgress)
      "content" -> openContent(parsed, onProgress)
      // Release builds resolve `require('./x.pdf')` to a bare resource name.
      null -> openRawResource(uri)
      else -> throw invalidSource(uri)
    }
  }

  private fun openFile(file: File): ResolvedSource {
    if (!file.isFile) {
      throw PdfFailure(PdfErrorCode.FILE_NOT_FOUND, "No file at ${file.path}")
    }
    try {
      return ResolvedSource(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), file.absoluteFile)
    } catch (e: FileNotFoundException) {
      throw PdfFailure(PdfErrorCode.FILE_NOT_FOUND, "Cannot open ${file.path}", cause = e)
    }
  }

  /** The provider's descriptor when the renderer can use it; a pipe, or part of a file, is copied. */
  private fun openContent(
    uri: Uri,
    onProgress: (loadedBytes: Long, totalBytes: Long?, isComplete: Boolean) -> Unit,
  ): ResolvedSource {
    val asset = try {
      context.contentResolver.openAssetFileDescriptor(uri, "r", contentCancellation)
        ?: throw PdfFailure(PdfErrorCode.FILE_NOT_FOUND, "No content at $uri")
    } catch (e: FileNotFoundException) {
      throw PdfFailure(PdfErrorCode.FILE_NOT_FOUND, "No content at $uri", cause = e)
    } catch (e: SecurityException) {
      throw PdfFailure(PdfErrorCode.FILE_NOT_FOUND, "No permission to read $uri", cause = e)
    } catch (_: OperationCanceledException) {
      throw InterruptedException()
    }
    val descriptor = asset.parcelFileDescriptor
    if (asset.declaredLength == AssetFileDescriptor.UNKNOWN_LENGTH && descriptor.isSeekable()) {
      return ResolvedSource(descriptor, file = null)
    }
    val totalBytes = if (asset.declaredLength >= 0) asset.declaredLength else statedSize(uri)
    // Closing it also ends a read blocked on a stalled pipe.
    contentCancellation.setOnCancelListener(descriptor::closeQuietly)
    try {
      return openTemporaryCopy { file ->
        file.outputStream().use { output ->
          asset.createInputStream().use { it.copyWithProgress(output, totalBytes, { cancelled }, onProgress) }
        }
      }
    } finally {
      contentCancellation.setOnCancelListener(null)
      descriptor.closeQuietly()
    }
  }

  private fun ParcelFileDescriptor.isSeekable(): Boolean =
    try {
      Os.lseek(fileDescriptor, 0, OsConstants.SEEK_CUR)
      true
    } catch (_: ErrnoException) {
      false
    }

  private fun statedSize(uri: Uri): Long? =
    try {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column) else null
      }
    } catch (_: RuntimeException) {
      null
    }

  private fun openBase64(text: ArrayBuffer): ResolvedSource {
    if (!text.isOwner) throw IllegalStateException("The base64 text is not in a native buffer")
    return openTemporaryCopy { file ->
      val mode = ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
      ParcelFileDescriptor.open(file, mode).use { output ->
        NativeBase64Document.writeDecoded(text.getBuffer(false), output.fd)?.let {
          throw PdfFailure(PdfErrorCode.INVALID_SOURCE, it)
        }
      }
    }
  }

  /**
   * Opens the file [write] fills, and deletes it at once: the session needs
   * only the descriptor, so nothing stays on disk.
   */
  private fun openTemporaryCopy(write: (File) -> Unit): ResolvedSource {
    val file = File(openDirectory(), "${UUID.randomUUID()}.pdf")
    try {
      write(file)
      if (cancelled) throw InterruptedException()
      return ResolvedSource(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), file = null)
    } catch (e: IOException) {
      // Cancelling closes what a copy reads from.
      if (cancelled) throw InterruptedException()
      throw PdfFailure(PdfErrorCode.INVALID_PDF, "The PDF could not be copied to the app's cache", cause = e)
    } finally {
      file.delete()
    }
  }

  /** Emptied once per process, for files left by a crash before deletion. */
  private fun openDirectory(): File {
    val directory = File(cacheDirectory(), "open")
    synchronized(Companion) {
      if (!isOpenDirectorySwept) {
        directory.listFiles()?.forEach { it.delete() }
        isOpenDirectorySwept = true
      }
    }
    directory.mkdirs()
    return directory
  }

  private fun openRawResource(name: String): ResolvedSource {
    val resourceName = name.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9_]"), "_").lowercase()
    @Suppress("DiscouragedApi") // Resource names are only known at runtime here.
    val id = context.resources.getIdentifier(resourceName, "raw", context.packageName)
    if (id == 0) throw invalidSource(name)
    // Raw resources may be compressed inside the APK and therefore not
    // seekable, so copy once into the cache.
    val target = File(cacheDirectory(), "raw-$resourceName-${appVersionStamp()}.pdf")
    if (!target.isFile) {
      val partial = File(target.path + ".${System.nanoTime()}.part")
      context.resources.openRawResource(id).use { input ->
        partial.outputStream().use { output -> input.copyTo(output) }
      }
      if (!partial.renameTo(target)) throw IOException("Cannot move ${partial.path}")
    }
    return openFile(target)
  }

  /**
   * The copy of a remote [source], downloaded or revalidated when needed, or
   * an existing copy (marked stale) when the server cannot be reached.
   */
  private fun openRemote(
    source: NativePdfSource,
    onProgress: (loadedBytes: Long, totalBytes: Long?, isComplete: Boolean) -> Unit,
  ): ResolvedSource {
    // Written on a download thread before the latch opens; read after.
    var delivered: CachedCopy? = null
    var deliveredStale = false
    var deliveredFailure: PdfFailure? = null
    subscription = DocumentCache.shared(context).fetch(
      source,
      allowStale = true,
      object : DocumentCache.Listener {
        override fun onProgress(loadedBytes: Long, totalBytes: Long?, isComplete: Boolean) =
          onProgress(loadedBytes, totalBytes, isComplete)

        override fun onReady(copy: CachedCopy, isStale: Boolean) {
          delivered = copy
          deliveredStale = isStale
          remoteDone.countDown()
        }

        override fun onFailed(failure: PdfFailure) {
          deliveredFailure = failure
          remoteDone.countDown()
        }
      },
    )
    // cancel() may have run before the subscription existed.
    if (cancelled) subscription?.cancel()
    remoteDone.await()
    if (cancelled) throw InterruptedException()
    deliveredFailure?.let { throw it }
    val copy = delivered ?: throw IllegalStateException("The cache delivered no copy")
    return openFile(copy.file).copy(isStale = deliveredStale)
  }

  private fun cacheDirectory(): File =
    File(context.cacheDir, "BetterPdf").apply { mkdirs() }

  /** Invalidates copied raw resources when the app is updated. */
  private fun appVersionStamp(): Long =
    context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

  private fun invalidSource(uri: String) =
    PdfFailure(PdfErrorCode.INVALID_SOURCE, "Unsupported PDF source: \"$uri\"")

  private companion object {
    var isOpenDirectorySwept = false
  }
}
