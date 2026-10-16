package com.margelo.nitro.betterpdf

import java.io.File
import java.io.IOException
import java.util.Properties

/**
 * The downloaded copies of remote documents on disk, by cache key
 * ([NativePdfSource.cacheKey]): `<key>.pdf` plus `<key>.properties`
 * ([CacheMetadata]) in [sessionDirectory] or [persistentDirectory]. A key has
 * at most one current copy; a persistent copy wins over a session one.
 *
 * The first use in a process deletes the previous process's session copies
 * and unfinished downloads ([partialDirectory]), and the flat layout of
 * earlier versions in [legacyDirectory] (never its subdirectories: `renders`
 * belongs to the app).
 *
 * Thread-safe; every method blocks on disk I/O, none on the network.
 */
internal class DocumentCacheStore(
  private val sessionDirectory: File,
  private val persistentDirectory: File,
  private val partialDirectory: File,
  private val legacyDirectory: File?,
  /** Creates a hard link; throws when the file system cannot. */
  private val link: (existing: File, link: File) -> Unit,
) {
  private var isPrepared = false

  @Synchronized
  fun find(key: String): CachedCopy? {
    prepare()
    return copyIn(persistentDirectory, key, PdfCacheMode.PERSISTENT)
      ?: copyIn(sessionDirectory, key, PdfCacheMode.SESSION)
  }

  /** A new file for a download in progress, to be [commit]ted or deleted. */
  @Synchronized
  fun newPartialFile(key: String): File {
    prepare()
    partialDirectory.mkdirs()
    return File(partialDirectory, "$key.${System.nanoTime()}.part")
  }

  /**
   * Makes [partial] the copy of [key], replacing any previous one (open
   * descriptors keep reading the old file). A persistent copy replaces the
   * session one too.
   */
  @Synchronized
  fun commit(key: String, partial: File, mode: PdfCacheMode, metadata: CacheMetadata): CachedCopy {
    prepare()
    val directory = directoryFor(mode)
    directory.mkdirs()
    val target = pdfFile(directory, key)
    if (!partial.renameTo(target)) throw IOException("Cannot move ${partial.path} to ${target.path}")
    writeMetadata(directory, key, metadata)
    if (mode == PdfCacheMode.PERSISTENT) deleteCopy(sessionDirectory, key)
    return CachedCopy(target, mode, metadata)
  }

  /** Records that the server confirmed [copy] unchanged. */
  @Synchronized
  fun markValidated(copy: CachedCopy, metadata: CacheMetadata): CachedCopy {
    prepare()
    writeMetadata(copy.file.parentFile!!, copy.file.nameWithoutExtension, metadata)
    return copy.copy(metadata = metadata)
  }

  /**
   * Keeps a session [copy] across launches. The session file stays where it
   * is (a hard link, so no second copy of the data) until the next launch,
   * so paths already handed out stay valid.
   */
  @Synchronized
  fun promote(copy: CachedCopy): CachedCopy {
    prepare()
    if (copy.mode == PdfCacheMode.PERSISTENT) return copy
    val key = copy.file.nameWithoutExtension
    persistentDirectory.mkdirs()
    val target = pdfFile(persistentDirectory, key)
    target.delete()
    try {
      link(copy.file, target)
    } catch (_: Exception) {
      val partial = newPartialFile(key)
      copy.file.copyTo(partial, overwrite = true)
      if (!partial.renameTo(target)) {
        partial.delete()
        throw IOException("Cannot move ${partial.path} to ${target.path}")
      }
    }
    writeMetadata(persistentDirectory, key, copy.metadata)
    return CachedCopy(target, PdfCacheMode.PERSISTENT, copy.metadata)
  }

  /** Deletes every copy of [key]; false when there was none. */
  @Synchronized
  fun remove(key: String): Boolean {
    prepare()
    val removedPersistent = deleteCopy(persistentDirectory, key)
    val removedSession = deleteCopy(sessionDirectory, key)
    return removedPersistent || removedSession
  }

  /** Deletes every copy. Downloads in progress still store their result. */
  @Synchronized
  fun clear() {
    prepare()
    sessionDirectory.deleteRecursively()
    persistentDirectory.deleteRecursively()
  }

  /** Bytes of all current copies; a session copy linked to a persistent one counts once. */
  @Synchronized
  fun byteSize(): Long {
    prepare()
    val persistent = pdfFiles(persistentDirectory)
    val persistentKeys = persistent.map { it.nameWithoutExtension }.toSet()
    val session = pdfFiles(sessionDirectory).filter { it.nameWithoutExtension !in persistentKeys }
    return (persistent + session).sumOf { it.length() }
  }

  /**
   * Whether [file] is in one of the store's directories or below them,
   * where writing it would replace a copy or be deleted with them. [file]
   * need not exist.
   */
  fun contains(file: File): Boolean {
    val canonical = file.canonicalFile
    return listOf(sessionDirectory, persistentDirectory, partialDirectory).any { canonical.startsWith(it.canonicalFile) }
  }

  private fun prepare() {
    if (isPrepared) return
    isPrepared = true
    sessionDirectory.deleteRecursively()
    partialDirectory.deleteRecursively()
    legacyDirectory?.listFiles()?.forEach { file ->
      if (file.isFile && LEGACY_FILE.matches(file.name)) file.delete()
    }
  }

  private fun directoryFor(mode: PdfCacheMode): File =
    if (mode == PdfCacheMode.PERSISTENT) persistentDirectory else sessionDirectory

  private fun copyIn(directory: File, key: String, mode: PdfCacheMode): CachedCopy? {
    val file = pdfFile(directory, key)
    if (!file.isFile) return null
    return CachedCopy(file, mode, readMetadata(directory, key) ?: CacheMetadata(file.lastModified(), null, null))
  }

  private fun deleteCopy(directory: File, key: String): Boolean {
    metadataFile(directory, key).delete()
    return pdfFile(directory, key).delete()
  }

  private fun readMetadata(directory: File, key: String): CacheMetadata? {
    val properties = Properties()
    try {
      metadataFile(directory, key).reader().use { properties.load(it) }
    } catch (_: IOException) {
      return null
    } catch (_: IllegalArgumentException) {
      return null
    }
    val validatedAtMs = properties.getProperty(VALIDATED_AT)?.toLongOrNull() ?: return null
    return CacheMetadata(validatedAtMs, properties.getProperty(ETAG), properties.getProperty(LAST_MODIFIED))
  }

  private fun writeMetadata(directory: File, key: String, metadata: CacheMetadata) {
    val properties = Properties()
    properties.setProperty(VALIDATED_AT, metadata.validatedAtMs.toString())
    metadata.etag?.let { properties.setProperty(ETAG, it) }
    metadata.lastModified?.let { properties.setProperty(LAST_MODIFIED, it) }
    val target = metadataFile(directory, key)
    val partial = File(directory, "${target.name}.${System.nanoTime()}.tmp")
    partial.writer().use { properties.store(it, null) }
    if (!partial.renameTo(target)) {
      partial.delete()
      throw IOException("Cannot write ${target.path}")
    }
  }

  private fun pdfFile(directory: File, key: String) = File(directory, "$key.pdf")

  private fun metadataFile(directory: File, key: String) = File(directory, "$key.properties")

  private fun pdfFiles(directory: File): List<File> =
    directory.listFiles { file -> file.isFile && file.extension == "pdf" }?.toList().orEmpty()

  private companion object {
    const val VALIDATED_AT = "validatedAtMs"
    const val ETAG = "etag"
    const val LAST_MODIFIED = "lastModified"

    /** Downloads (`<sha256>.pdf`) and unfinished downloads of versions before the cache API. */
    val LEGACY_FILE = Regex("[0-9a-f]{64}(\\.pdf|\\.[0-9]+\\.part)")
  }
}
