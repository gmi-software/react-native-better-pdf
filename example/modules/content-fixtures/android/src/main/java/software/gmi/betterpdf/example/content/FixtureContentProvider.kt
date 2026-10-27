package software.gmi.betterpdf.example.content

import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.thread

/**
 * Serves the fixtures `ensureFixture` copied to `files/fixtures/` the ways
 * providers hand out documents, at `content://<applicationId>.fixtures/<kind>/<name>.pdf`:
 *
 * - `file`: the file itself, seekable, like FileProvider and MediaStore.
 * - `pipe`: streamed through a pipe (`openPipeHelper`).
 * - `partial`: a part of a larger file, as an asset at an offset.
 * - `stalled`: a pipe that stops for a minute after its first 64 KiB.
 * - `broken`: a pipe whose writer fails halfway (`closeWithError`).
 */
class FixtureContentProvider : ContentProvider() {
  override fun onCreate() = true

  override fun getType(uri: Uri) = "application/pdf"

  override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
    val file = fixture(uri)
    val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    return MatrixCursor(columns).apply {
      addRow(
        columns.map {
          when (it) {
            OpenableColumns.DISPLAY_NAME -> file.name
            OpenableColumns.SIZE -> file.length()
            else -> null
          }
        },
      )
    }
  }

  override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
    if (kind(uri) != "partial") return super.openAssetFile(uri, mode)
    val file = fixture(uri)
    // More padding than the 1024 bytes PDFium searches for the header, so
    // the document opens only when the offset is honoured.
    val padded = File(context!!.cacheDir, "padded-${file.name}")
    if (!padded.exists()) {
      padded.outputStream().use { output ->
        output.write(ByteArray(PADDING))
        file.inputStream().use { it.copyTo(output) }
        output.write(ByteArray(PADDING))
      }
    }
    return AssetFileDescriptor(ParcelFileDescriptor.open(padded, ParcelFileDescriptor.MODE_READ_ONLY), PADDING.toLong(), file.length())
  }

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    val file = fixture(uri)
    return when (kind(uri)) {
      "file" -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
      "pipe" -> pipe(uri, file) { input, output -> input.copyTo(output) }
      "stalled" -> pipe(uri, file) { input, output ->
        val head = ByteArray(STALL_AFTER)
        output.write(head, 0, input.read(head))
        Thread.sleep(STALL_MS)
        input.copyTo(output)
      }
      "broken" -> {
        val (reader, writer) = ParcelFileDescriptor.createReliablePipe()
        thread {
          val bytes = file.readBytes()
          try {
            FileOutputStream(writer.fileDescriptor).write(bytes, 0, bytes.size / 2)
            writer.closeWithError("The fixture provider failed")
          } catch (e: IOException) {
            Log.i(TAG, "Stopped writing ${file.name}: ${e.message}")
          }
        }
        reader
      }
      else -> throw FileNotFoundException("Unknown kind in $uri")
    }
  }

  private fun pipe(uri: Uri, file: File, write: (InputStream, OutputStream) -> Unit): ParcelFileDescriptor =
    openPipeHelper(uri, getType(uri), null, file) { output, _, _, _, _ ->
      try {
        file.inputStream().use { input -> FileOutputStream(output.fileDescriptor).use { write(input, it) } }
      } catch (e: IOException) {
        // The reader closed its end.
        Log.i(TAG, "Stopped writing ${file.name}: ${e.message}")
      }
    }

  private fun kind(uri: Uri): String? = uri.pathSegments.firstOrNull()

  private fun fixture(uri: Uri): File {
    val file = File(context!!.filesDir, "fixtures/${uri.lastPathSegment}")
    if (!file.isFile) throw FileNotFoundException("No fixture for $uri")
    return file
  }

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0

  private companion object {
    const val TAG = "FixtureContentProvider"
    const val PADDING = 4096
    const val STALL_AFTER = 64 * 1024
    const val STALL_MS = 60_000L
  }
}
