package com.margelo.nitro.betterpdf.outline

/**
 * Random access to the objects of a PDF file, objects in object streams
 * included, through its [crossReferences] (read from the file unless
 * given). Not thread-safe; the readers of one file on several threads each
 * use their own, with the same cross-references. One reader's parsing is
 * bounded by a [ScanBudget].
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
  private val xref: Map<Int, XrefEntry> = crossReferences.entries
  private val cache = HashMap<Int, PdfObject>()
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
      throw PdfFormatException("Encrypted documents are not supported")
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

  private fun objectAt(number: Int): PdfObject? {
    cache[number]?.let { return it }
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
      val value = when (entry) {
        is XrefEntry.InFile -> readInFile(entry.offset)
        is XrefEntry.Compressed -> readCompressed(entry)
      } ?: return null
      if (cache.size < MAX_CACHED_OBJECTS) cache[number] = value
      return value
    } catch (error: ScanBudget.Exceeded) {
      throw error
    } catch (error: PdfFormatException) {
      return null
    } finally {
      resolving.remove(number)
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
      if (index == null) invalidObjectStreams += number else keep(number, index)
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
    if (index.data.size > MAX_KEPT_STREAM_BYTES) return
    objectStreams[number] = index
    keptStreamBytes += index.data.size
    val eldest = objectStreams.entries.iterator()
    while (keptStreamBytes > MAX_KEPT_STREAM_BYTES) {
      val (kept, stream) = eldest.next()
      if (kept == number) break
      keptStreamBytes -= stream.data.size
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
    const val MAX_CACHED_OBJECTS = 50_000
    const val MAX_KEPT_STREAM_BYTES = 32L shl 20
  }
}
