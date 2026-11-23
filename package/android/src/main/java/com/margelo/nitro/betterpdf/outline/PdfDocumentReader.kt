package com.margelo.nitro.betterpdf.outline

/**
 * Random access to the objects of a PDF file, objects in object streams
 * included, through its [crossReferences] (read from the file unless
 * given). Not thread-safe; the readers of one file on several threads each
 * use their own, with the same cross-references. One reader's parsing, and
 * what it holds at once, is bounded by a [ScanBudget]: every object
 * [resolve] returns counts as held by the caller until the [releasing]
 * block around the call ends.
 *
 * Encrypted documents are rejected: their strings (outline titles) would
 * need the security handler.
 */
class PdfDocumentReader internal constructor(
  private val source: PdfByteSource,
  crossReferences: PdfCrossReferences,
  private val budget: ScanBudget,
) {
  constructor(
    source: PdfByteSource,
    crossReferences: PdfCrossReferences = PdfCrossReferences.read(source),
  ) : this(source, crossReferences, ScanBudget(source.size))

  val trailer: PdfObject.Dictionary = crossReferences.trailer
  private val xref: XrefTable = crossReferences.entries
  /** Objects read, the most recently used last, [cachedBytes] in all: [remember]. */
  private val cache = LinkedHashMap<Int, CachedObject>(16, 0.75f, true)
  private var cachedBytes = 0L
  /** Object streams read, the most recently used last, with [keptStreamBytes] of data: [keep]. */
  private val objectStreams = LinkedHashMap<Int, ObjectStreamIndex>(16, 0.75f, true)
  private var keptStreamBytes = 0L
  private val invalidObjectStreams = HashSet<Int>()
  /** The objects being read, each needed to read the one before (an indirect `/Length`, an object stream). */
  private val resolving = HashSet<Int>()
  /** Reads cut short by the nesting limit, or by a reference back to an object being read. */
  private var truncatedReads = 0

  init {
    if (trailer["Encrypt"] != null && trailer["Encrypt"] != PdfObject.Null) {
      throw EncryptedPdfException()
    }
  }

  /** Follows references (bounded) and returns the direct object; null when missing. */
  fun resolve(value: PdfObject?): PdfObject? {
    var current = value
    repeat(MAX_REFERENCE_HOPS) {
      val reference = current as? PdfObject.Reference ?: return current
      current = objectAt(reference.number)
    }
    return null
  }

  /**
   * Decoded data of [stream].
   *
   * @throws PdfFormatException when it cannot be decoded, or this read's
   *   budget for streams is spent.
   */
  fun streamData(stream: PdfObject.Stream): ByteArray {
    return StreamDecoder.read(source, stream, budget, ::resolve)
  }

  /** [ScanBudget.releasing]. */
  internal inline fun <T> releasing(block: () -> T): T = budget.releasing(block)

  /** [ScanBudget.keeping]. */
  internal inline fun <T> keeping(block: () -> T): T = budget.keeping(block)

  /** [ScanBudget.hold]: something a reader makes of the objects it reads. */
  internal fun hold(bytes: Long) = budget.hold(bytes)

  /** [ScanBudget.spend]: a unit for each byte of a string a reader decodes. */
  internal fun spend(bytes: Int) = budget.spend(bytes.toLong())

  /** [ScanBudget.keep]. */
  internal fun keep(bytes: Long) = budget.keep(bytes)

  private fun objectAt(number: Int): PdfObject? {
    cache[number]?.let { cached ->
      // Not parsed again, but gone through again by the caller, who holds it
      // from now on whether or not the cache still does.
      budget.spend(cached.bytes / CACHED_BYTES_PER_UNIT)
      budget.hold(cached.bytes)
      return cached.value
    }
    val entry = xref[number] ?: return null
    // Each object of a chain recurses: a crafted chain of thousands would
    // overflow the stack. The read that needed this one fails instead.
    if (resolving.size >= MAX_NESTED_READS) {
      truncatedReads++
      throw PdfFormatException("Objects nested too deeply at object $number")
    }
    // A /Length that refers back to the object being read would recurse.
    if (!resolving.add(number)) {
      truncatedReads++
      return null
    }
    try {
      val heldBefore = budget.held
      val value = when (entry) {
        is XrefEntry.InFile -> readInFile(entry.offset)
        is XrefEntry.Compressed -> readCompressed(entry)
      } ?: return null
      remember(number, value, budget.held - heldBefore)
      return value
    } catch (error: ScanBudget.Exceeded) {
      throw error
    } catch (error: PdfFormatException) {
      return null
    } finally {
      resolving.remove(number)
    }
  }

  /**
   * Keeps [value], which took [bytes] to read, for the next reference to
   * [number], and as many of the most recently used objects as fit
   * [MAX_CACHED_BYTES]. The others are read again when needed.
   */
  private fun remember(number: Int, value: PdfObject, bytes: Long) {
    if (bytes > MAX_CACHED_OBJECT_BYTES) return
    cachedBytes += CACHED_ENTRY_BYTES + bytes - (cache.put(number, CachedObject(value, bytes))?.let { CACHED_ENTRY_BYTES + it.bytes } ?: 0)
    val eldest = cache.values.iterator()
    while (cachedBytes > MAX_CACHED_BYTES) {
      cachedBytes -= CACHED_ENTRY_BYTES + eldest.next().bytes
      eldest.remove()
    }
  }

  private fun readInFile(offset: Long): PdfObject? {
    val parser = PdfParser(source, offset, budget)
    parser.readIndirectHeader() ?: return null
    return parser.readIndirectBody(::lengthOf)
  }

  private fun lengthOf(value: PdfObject): Int? = (resolve(value) as? PdfObject.Number)?.intValue

  private fun readCompressed(entry: XrefEntry.Compressed): PdfObject? {
    val stream = objectStream(entry.streamNumber) ?: return null
    val offset = stream.offsets.getOrNull(entry.index) ?: return null
    val parser = PdfParser(ByteArraySource(stream.data), (stream.first + offset).toLong(), budget)
    return parser.readObject()
  }

  private fun objectStream(number: Int): ObjectStreamIndex? {
    objectStreams[number]?.let { return it }
    if (number in invalidObjectStreams) return null
    val truncated = truncatedReads
    val index = try {
      (resolve(PdfObject.Reference(number, 0)) as? PdfObject.Stream)?.let(::indexObjectStream)
    } catch (error: ScanBudget.Exceeded) {
      throw error
    } catch (error: PdfFormatException) {
      // Not decodable: an unsupported filter, data expanding beyond the limit.
      null
    }
    // A read cut short by the nesting limit can make this one differ from a
    // shallower one: read it again next time.
    if (truncatedReads == truncated) {
      if (index == null) {
        if (invalidObjectStreams.add(number)) budget.keep(INVALID_STREAM_BYTES)
      } else {
        keep(number, index)
      }
    }
    return index
  }

  /**
   * Keeps [index] for the next object needed from it, and as many of the
   * most recently used object streams as fit [MAX_KEPT_STREAM_BYTES]: a
   * file can have many that expand to 32 MB. The others are decoded again
   * when needed.
   */
  private fun keep(number: Int, index: ObjectStreamIndex) {
    if (index.bytes > MAX_KEPT_STREAM_BYTES) return
    objectStreams[number] = index
    keptStreamBytes += index.bytes
    val eldest = objectStreams.entries.iterator()
    while (keptStreamBytes > MAX_KEPT_STREAM_BYTES) {
      val (kept, stream) = eldest.next()
      if (kept == number) break
      keptStreamBytes -= stream.bytes
      eldest.remove()
    }
  }

  /** null when [stream] is not a valid object stream; throws when it cannot be decoded. */
  private fun indexObjectStream(stream: PdfObject.Stream): ObjectStreamIndex? {
    val count = (stream.dictionary["N"] as? PdfObject.Number)?.intValue ?: return null
    val first = (stream.dictionary["First"] as? PdfObject.Number)?.intValue ?: return null
    if (count !in 0..ObjectStreamIndex.MAX_OBJECTS || first < 0) return null
    val data = streamData(stream)
    // Decoded once per reader: room to parse its objects.
    budget.extend(data.size)
    budget.hold(Int.SIZE_BYTES.toLong() * count)
    val header = PdfParser(ByteArraySource(data), 0, budget)
    val offsets = try {
      IntArray(count) {
        header.readToken() // object number; the xref entry already names it
        header.readToken()?.toIntOrNull() ?: return null
      }
    } catch (error: ScanBudget.Exceeded) {
      throw error
    } catch (error: PdfFormatException) {
      return null
    }
    return ObjectStreamIndex(data, first, offsets)
  }

  private companion object {
    const val MAX_REFERENCE_HOPS = 16
    const val MAX_NESTED_READS = 32
    const val MAX_CACHED_BYTES = 4L shl 20
    const val MAX_CACHED_OBJECT_BYTES = MAX_CACHED_BYTES / 2
    /** What the cache takes for an object besides the object: a null takes nothing itself. */
    const val CACHED_ENTRY_BYTES = 96L
    /** A unit of work for as many bytes of an object taken from the cache: a fifth or less of what parsing them took. */
    const val CACHED_BYTES_PER_UNIT = 8
    const val MAX_KEPT_STREAM_BYTES = 16L shl 20
    /** What remembering that an object stream cannot be read takes ([ScanBudget.keep]). */
    const val INVALID_STREAM_BYTES = 64L
  }
}

/** An object in the reader's cache, and what it took to read ([ScanBudget.hold]). */
private class CachedObject(val value: PdfObject, val bytes: Long)
