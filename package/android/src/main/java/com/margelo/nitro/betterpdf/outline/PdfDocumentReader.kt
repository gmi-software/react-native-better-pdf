package com.margelo.nitro.betterpdf.outline

/**
 * Random access to the objects of a PDF file: cross-reference tables and
 * streams (with `/Prev` chains and hybrid `/XRefStm` files), object streams,
 * and a rebuild by scanning the file when the cross-reference data is
 * broken — the same recovery viewers perform.
 *
 * Encrypted documents are rejected: their strings (outline titles) would
 * need the security handler.
 */
class PdfDocumentReader(private val source: PdfByteSource) {
  val trailer: PdfObject.Dictionary
  private val xref: Map<Int, XrefEntry>
  private val cache = HashMap<Int, PdfObject>()
  private val objectStreams = HashMap<Int, ObjectStreamIndex>()
  /** The objects being read, each needed to read the one before (an indirect `/Length`, an object stream). */
  private val resolving = HashSet<Int>()

  init {
    val parsed = try {
      readCrossReferences().takeIf { it.second["Root"] != null }
    } catch (error: PdfFormatException) {
      null
    }
    val (entries, trailerDictionary) = parsed ?: rebuildCrossReferences()
    xref = entries
    trailer = trailerDictionary
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

  /** Decoded data of [stream]. */
  fun streamData(stream: PdfObject.Stream): ByteArray {
    val raw = source.read(stream.dataOffset, stream.dataLength)
    return StreamDecoder.decode(stream.dictionary, raw, ::resolve)
  }

  private fun objectAt(number: Int): PdfObject? {
    cache[number]?.let { return it }
    val entry = xref[number] ?: return null
    // Each object of a chain recurses: a crafted chain of thousands would
    // overflow the stack. The read that needed this one fails instead.
    if (resolving.size >= MAX_NESTED_READS) throw PdfFormatException("Objects nested too deeply at object $number")
    // A /Length that refers back to the object being read would recurse.
    if (!resolving.add(number)) return null
    try {
      val value = when (entry) {
        is XrefEntry.InFile -> readInFile(entry.offset)
        is XrefEntry.Compressed -> readCompressed(entry)
      } ?: return null
      if (cache.size < MAX_CACHED_OBJECTS) cache[number] = value
      return value
    } catch (error: PdfFormatException) {
      return null
    } finally {
      resolving.remove(number)
    }
  }

  private fun readInFile(offset: Long): PdfObject? {
    val parser = PdfParser(source, offset)
    parser.readIndirectHeader() ?: return null
    return parser.readIndirectBody(::lengthOf)
  }

  private fun lengthOf(value: PdfObject): Int? = (resolve(value) as? PdfObject.Number)?.intValue

  private fun readCompressed(entry: XrefEntry.Compressed): PdfObject? {
    val stream = objectStreams[entry.streamNumber] ?: loadObjectStream(entry.streamNumber)?.also {
      objectStreams[entry.streamNumber] = it
    } ?: return null
    val offset = stream.offsets.getOrNull(entry.index) ?: return null
    val parser = PdfParser(ByteArraySource(stream.data), (stream.first + offset).toLong())
    return parser.readObject()
  }

  private fun loadObjectStream(number: Int): ObjectStreamIndex? {
    val stream = resolve(PdfObject.Reference(number, 0)) as? PdfObject.Stream ?: return null
    val count = (stream.dictionary["N"] as? PdfObject.Number)?.intValue ?: return null
    val first = (stream.dictionary["First"] as? PdfObject.Number)?.intValue ?: return null
    if (count !in 0..MAX_OBJECTS_PER_STREAM || first < 0) return null
    val data = streamData(stream)
    val header = PdfParser(ByteArraySource(data), 0)
    val offsets = IntArray(count) {
      header.readToken() // object number; the xref entry already names it
      header.readToken()?.toIntOrNull() ?: return null
    }
    return ObjectStreamIndex(data, first, offsets)
  }

  // region Cross-reference data

  private fun readCrossReferences(): Pair<Map<Int, XrefEntry>, PdfObject.Dictionary> {
    val entries = HashMap<Int, XrefEntry>()
    var newestTrailer: PdfObject.Dictionary? = null
    var offset: Long? = findStartXref() ?: throw PdfFormatException("No startxref")
    val visited = HashSet<Long>()
    while (offset != null && visited.add(offset)) {
      if (visited.size > MAX_XREF_SECTIONS) throw PdfFormatException("Too many cross-reference sections")
      val sectionTrailer = readSection(offset, entries)
      if (newestTrailer == null) newestTrailer = sectionTrailer
      // Hybrid files: the classic table is extended by an xref stream.
      (sectionTrailer["XRefStm"] as? PdfObject.Number)?.value?.toLong()?.let { stream ->
        if (visited.add(stream)) readSection(stream, entries)
      }
      offset = (sectionTrailer["Prev"] as? PdfObject.Number)?.value?.toLong()
    }
    return entries to (newestTrailer ?: throw PdfFormatException("No trailer"))
  }

  private fun findStartXref(): Long? {
    val tailStart = maxOf(0L, source.size - TAIL_BYTES)
    val tail = source.read(tailStart, TAIL_BYTES).toString(Charsets.ISO_8859_1)
    val index = tail.lastIndexOf("startxref").takeIf { it >= 0 } ?: return null
    val parser = PdfParser(source, tailStart + index + "startxref".length)
    return parser.readToken()?.toLongOrNull()?.takeIf { it in 0 until source.size }
  }

  /** Reads one section into [entries] (older sections never override newer ones); returns its trailer. */
  private fun readSection(offset: Long, entries: MutableMap<Int, XrefEntry>): PdfObject.Dictionary {
    val parser = PdfParser(source, offset)
    if (parser.matchKeyword("xref")) return readTable(parser, entries)
    parser.readIndirectHeader() ?: throw PdfFormatException("No cross-reference section at $offset")
    val stream = parser.readIndirectBody { (it as? PdfObject.Number)?.intValue } as? PdfObject.Stream
      ?: throw PdfFormatException("Cross-reference stream expected at $offset")
    readXrefStream(stream, entries)
    return stream.dictionary
  }

  private fun readTable(parser: PdfParser, entries: MutableMap<Int, XrefEntry>): PdfObject.Dictionary {
    while (true) {
      val token = parser.readToken() ?: throw PdfFormatException("Truncated cross-reference table")
      if (token == "trailer") {
        return parser.readObject() as? PdfObject.Dictionary ?: throw PdfFormatException("Invalid trailer")
      }
      val start = token.toIntOrNull() ?: throw PdfFormatException("Invalid subsection \"$token\"")
      val count = parser.readToken()?.toIntOrNull() ?: throw PdfFormatException("Invalid subsection count")
      if (start < 0 || count !in 0..MAX_XREF_ENTRIES) throw PdfFormatException("Invalid subsection $start $count")
      for (index in 0 until count) {
        val offset = parser.readToken()?.toLongOrNull() ?: throw PdfFormatException("Invalid xref entry")
        parser.readToken() // generation
        val type = parser.readToken()
        if (type == "n" && offset > 0) entries.putIfAbsent(start + index, XrefEntry.InFile(offset))
      }
    }
  }

  private fun readXrefStream(stream: PdfObject.Stream, entries: MutableMap<Int, XrefEntry>) {
    val dictionary = stream.dictionary
    val widths = (dictionary["W"] as? PdfObject.Array)?.items?.map { (it as? PdfObject.Number)?.intValue ?: -1 }
    if (widths == null || widths.size < 3 || widths.any { it !in 0..8 }) throw PdfFormatException("Invalid /W")
    val size = (dictionary["Size"] as? PdfObject.Number)?.intValue ?: 0
    val index = (dictionary["Index"] as? PdfObject.Array)?.items?.map { (it as? PdfObject.Number)?.intValue ?: 0 }
      ?: listOf(0, size)
    val data = StreamDecoder.decode(dictionary, source.read(stream.dataOffset, stream.dataLength)) { it }
    val rowLength = widths.sum()
    if (rowLength == 0) return
    var row = 0
    for (pair in index.chunked(2)) {
      val start = pair.getOrNull(0) ?: break
      val count = pair.getOrNull(1) ?: break
      if (start < 0 || count !in 0..MAX_XREF_ENTRIES) throw PdfFormatException("Invalid /Index")
      for (number in start until start + count) {
        val at = row * rowLength
        if (at + rowLength > data.size) return
        row++
        fun field(which: Int, default: Long): Long {
          if (widths[which] == 0) return default
          var value = 0L
          val begin = at + widths.take(which).sum()
          for (i in 0 until widths[which]) value = (value shl 8) or (data[begin + i].toLong() and 0xff)
          return value
        }
        when (field(0, 1)) {
          1L -> field(1, 0).takeIf { it > 0 }?.let { entries.putIfAbsent(number, XrefEntry.InFile(it)) }
          2L -> entries.putIfAbsent(number, XrefEntry.Compressed(field(1, 0).toInt(), field(2, 0).toInt()))
        }
      }
    }
  }

  /**
   * Rebuilds the cross-reference data by scanning for `n g obj`. Later
   * definitions win, as in incremental updates. Bounded by file size.
   */
  private fun rebuildCrossReferences(): Pair<Map<Int, XrefEntry>, PdfObject.Dictionary> {
    if (source.size > MAX_REBUILD_BYTES) throw PdfFormatException("Cross-reference data is broken")
    val entries = HashMap<Int, XrefEntry>()
    val text = source.read(0, source.size.toInt()).toString(Charsets.ISO_8859_1)
    val pattern = Regex("""(?<![0-9])(\d{1,10})\s+(\d{1,5})\s+obj\b""")
    for (match in pattern.findAll(text)) {
      val number = match.groupValues[1].toIntOrNull() ?: continue
      entries[number] = XrefEntry.InFile(match.range.first.toLong())
    }
    // The newest trailer dictionary, or a catalog found among the objects.
    var trailerDictionary: PdfObject.Dictionary? = null
    var at = text.lastIndexOf("trailer")
    while (at >= 0 && trailerDictionary == null) {
      trailerDictionary = try {
        PdfParser(source, at + "trailer".length.toLong()).readObject() as? PdfObject.Dictionary
      } catch (error: PdfFormatException) {
        null
      }?.takeIf { it["Root"] != null }
      at = if (at > 0) text.lastIndexOf("trailer", at - 1) else -1
    }
    val root = trailerDictionary?.get("Root") ?: findCatalog(entries)
      ?: throw PdfFormatException("No document catalog")
    indexObjectStreams(entries)
    return entries to (trailerDictionary ?: PdfObject.Dictionary(mapOf("Root" to root)))
  }

  /** Reads an object during the rebuild, before [xref] exists. */
  private fun readDuringRebuild(entries: Map<Int, XrefEntry>, number: Int): PdfObject? {
    val entry = entries[number] as? XrefEntry.InFile ?: return null
    return try {
      val parser = PdfParser(source, entry.offset)
      parser.readIndirectHeader() ?: return null
      parser.readIndirectBody { (it as? PdfObject.Number)?.intValue }
    } catch (error: PdfFormatException) {
      null
    }
  }

  private fun findCatalog(entries: Map<Int, XrefEntry>): PdfObject.Reference? =
    entries.keys.sorted().firstOrNull { number ->
      (readDuringRebuild(entries, number) as? PdfObject.Dictionary)?.get("Type") == PdfObject.Name("Catalog")
    }?.let { PdfObject.Reference(it, 0) }

  /** Objects inside object streams are invisible to the scan; list them too. */
  private fun indexObjectStreams(entries: MutableMap<Int, XrefEntry>) {
    for (number in entries.keys.toList()) {
      val stream = readDuringRebuild(entries, number) as? PdfObject.Stream ?: continue
      if (stream.dictionary["Type"] != PdfObject.Name("ObjStm")) continue
      val count = (stream.dictionary["N"] as? PdfObject.Number)?.intValue ?: continue
      if (count !in 0..MAX_OBJECTS_PER_STREAM) continue
      val data = try {
        StreamDecoder.decode(stream.dictionary, source.read(stream.dataOffset, stream.dataLength)) { it }
      } catch (error: PdfFormatException) {
        continue
      }
      val header = PdfParser(ByteArraySource(data), 0)
      for (index in 0 until count) {
        val objectNumber = header.readToken()?.toIntOrNull() ?: break
        header.readToken()
        entries.putIfAbsent(objectNumber, XrefEntry.Compressed(number, index))
      }
    }
  }

  // endregion

  private companion object {
    const val TAIL_BYTES = 2048
    const val MAX_REFERENCE_HOPS = 16
    const val MAX_NESTED_READS = 32
    const val MAX_XREF_SECTIONS = 64
    const val MAX_XREF_ENTRIES = 10_000_000
    const val MAX_OBJECTS_PER_STREAM = 100_000
    const val MAX_CACHED_OBJECTS = 50_000
    const val MAX_REBUILD_BYTES = 64L * 1024 * 1024
  }
}
