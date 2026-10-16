package com.margelo.nitro.betterpdf.outline

/**
 * Where each object of a PDF file is, and the file's trailer: read from its
 * cross-reference tables and streams (with `/Prev` chains and hybrid
 * `/XRefStm` files), or rebuilt by scanning the file when they are broken —
 * the same recovery viewers perform. The costly part of opening a file:
 * read once per file ([CrossReferenceCache]) and given to each of its
 * [PdfDocumentReader]s.
 */
class PdfCrossReferences internal constructor(
  internal val entries: Map<Int, XrefEntry>,
  val trailer: PdfObject.Dictionary,
) {
  companion object {
    /**
     * Reads [source]'s cross-reference data, or rebuilds it.
     *
     * @throws PdfFormatException when neither finds the document catalog.
     */
    fun read(source: PdfByteSource): PdfCrossReferences = CrossReferenceReader(source).read()
  }
}

private class CrossReferenceReader(private val source: PdfByteSource) {
  private val budget = ScanBudget(source.size)

  fun read(): PdfCrossReferences {
    val parsed = try {
      readCrossReferences().takeIf(::leadToCatalog)
    } catch (error: ScanBudget.Exceeded) {
      throw error
    } catch (error: PdfFormatException) {
      null
    }
    return parsed ?: rebuild()
  }

  private fun parser(position: Long) = PdfParser(source, position, budget)

  /**
   * Whether [crossReferences] lead to the catalog their trailer names: a
   * table can be readable and still lose or misplace the catalog's entry.
   * An encrypted document is not checked; its readers reject it anyway.
   */
  private fun leadToCatalog(crossReferences: PdfCrossReferences): Boolean {
    val trailer = crossReferences.trailer
    val root = trailer["Root"] ?: return false
    if (trailer["Encrypt"] != null && trailer["Encrypt"] != PdfObject.Null) return true
    val catalog = reader(crossReferences.entries).resolve(root) as? PdfObject.Dictionary ?: return false
    return catalog["Type"] == PdfObject.Name("Catalog") || catalog["Pages"] != null
  }

  /** A reader of [entries], before a trailer is chosen. */
  private fun reader(entries: Map<Int, XrefEntry>) =
    PdfDocumentReader(source, PdfCrossReferences(entries, PdfObject.Dictionary(emptyMap())), budget)

  private fun readCrossReferences(): PdfCrossReferences {
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
    return PdfCrossReferences(entries, newestTrailer ?: throw PdfFormatException("No trailer"))
  }

  /** The last `startxref`'s offset, looked for in the last [TAIL_BYTES]: files may end in junk. */
  private fun findStartXref(): Long? {
    val tailStart = maxOf(0L, source.size - TAIL_BYTES)
    val tail = source.read(tailStart, TAIL_BYTES).toString(Charsets.ISO_8859_1)
    val index = tail.lastIndexOf("startxref").takeIf { it >= 0 } ?: return null
    return parser(tailStart + index + "startxref".length).readToken()?.toLongOrNull()?.takeIf { it in 0 until source.size }
  }

  /** Reads one section into [entries] (older sections never override newer ones); returns its trailer. */
  private fun readSection(offset: Long, entries: MutableMap<Int, XrefEntry>): PdfObject.Dictionary {
    val parser = parser(offset)
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
    val data = StreamDecoder.read(source, stream, budget) { it }
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

  // region Rebuild

  /**
   * Rebuilds the cross-reference data from the `n g obj` headers in the
   * file ([scan]). Later definitions win, as in incremental updates.
   * Bounded by file size, and to [MAX_REBUILT_OBJECTS] objects: each is
   * kept until the rebuild ends.
   */
  private fun rebuild(): PdfCrossReferences {
    if (source.size > MAX_REBUILD_BYTES) throw PdfFormatException("Cross-reference data is broken")
    val entries = HashMap<Int, XrefEntry>()
    val trailers = ArrayDeque<Long>()
    scan(
      onObject = { number, offset ->
        entries[number] = XrefEntry.InFile(offset)
        checkRebuiltObjects(entries)
      },
      onTrailer = { offset ->
        if (trailers.size == MAX_TRAILERS) trailers.removeFirst()
        trailers.addLast(offset)
      },
    )
    val found = readFoundObjects(entries)
    // The newest trailer naming a catalog — a `trailer` dictionary or a
    // cross-reference stream's — or the newest catalog found among the objects.
    val trailer = trailers.asReversed().firstNotNullOfOrNull { offset -> readTrailer(offset)?.let { offset to it } }
    val newest = listOfNotNull(trailer, found.newestXrefStream).maxByOrNull { (offset) -> offset }?.second
    val root = newest?.get("Root") ?: found.catalog ?: findCompressedCatalog(entries)
      ?: throw PdfFormatException("No document catalog")
    return PdfCrossReferences(entries, newest ?: PdfObject.Dictionary(mapOf("Root" to root)))
  }

  /**
   * Calls [onObject] for every `n g obj` header — `n` of up to 10 digits and
   * not preceded by one, `g` of up to 5, separated by whitespace, `obj` as a
   * keyword — and [onTrailer] for every `trailer`, in one pass over the file
   * without holding it in memory.
   */
  private fun scan(onObject: (number: Int, offset: Long) -> Unit, onTrailer: (offset: Long) -> Unit) {
    var phase = Phase.NONE
    var numberStart = 0L
    var number = 0L
    var numberDigits = 0
    var generationStart = 0L
    var generation = 0L
    var generationDigits = 0
    var trailerMatched = 0
    fun found() {
      if (number <= Int.MAX_VALUE) onObject(number.toInt(), numberStart)
    }
    var at = 0L
    while (at < source.size) {
      val window = source.read(at, SCAN_WINDOW_BYTES)
      if (window.isEmpty()) break
      for (byte in window) {
        val c = byte.toInt() and 0xff
        // `trailer` has no prefix that is also its suffix: a mismatch restarts the match.
        trailerMatched = if (c == TRAILER[trailerMatched]) trailerMatched + 1 else if (c == TRAILER[0]) 1 else 0
        if (trailerMatched == TRAILER.size) {
          onTrailer(at + 1 - TRAILER.size)
          trailerMatched = 0
        }
        val digit = c - '0'.code
        phase = when {
          digit in 0..9 -> when (phase) {
            Phase.NUMBER -> {
              if (++numberDigits <= MAX_NUMBER_DIGITS) number = number * 10 + digit
              Phase.NUMBER
            }
            Phase.AFTER_NUMBER -> {
              generationStart = at
              generation = digit.toLong()
              generationDigits = 1
              Phase.GENERATION
            }
            Phase.GENERATION -> {
              if (++generationDigits <= MAX_NUMBER_DIGITS) generation = generation * 10 + digit
              Phase.GENERATION
            }
            // `a b c`: b may be the number, c its generation.
            Phase.AFTER_GENERATION -> {
              numberStart = generationStart
              number = generation
              numberDigits = generationDigits
              generationStart = at
              generation = digit.toLong()
              generationDigits = 1
              Phase.GENERATION
            }
            else -> {
              numberStart = at
              number = digit.toLong()
              numberDigits = 1
              Phase.NUMBER
            }
          }
          PdfParser.isWhitespace(c) -> when (phase) {
            Phase.NUMBER -> if (numberDigits <= MAX_NUMBER_DIGITS) Phase.AFTER_NUMBER else Phase.NONE
            Phase.GENERATION -> when {
              generationDigits <= MAX_GENERATION_DIGITS -> Phase.AFTER_GENERATION
              // Too long for a generation: perhaps the number.
              generationDigits <= MAX_NUMBER_DIGITS -> {
                numberStart = generationStart
                number = generation
                numberDigits = generationDigits
                Phase.AFTER_NUMBER
              }
              else -> Phase.NONE
            }
            Phase.AFTER_NUMBER, Phase.AFTER_GENERATION -> phase
            Phase.OBJ -> {
              found()
              Phase.NONE
            }
            else -> Phase.NONE
          }
          phase == Phase.AFTER_GENERATION && c == 'o'.code -> Phase.O
          phase == Phase.O && c == 'b'.code -> Phase.OB
          phase == Phase.OB && c == 'j'.code -> Phase.OBJ
          else -> {
            if (phase == Phase.OBJ && PdfParser.isDelimiter(c)) found()
            Phase.NONE
          }
        }
        at++
      }
    }
    if (phase == Phase.OBJ) found()
  }

  private enum class Phase { NONE, NUMBER, AFTER_NUMBER, GENERATION, AFTER_GENERATION, O, OB, OBJ }

  private fun readTrailer(offset: Long): PdfObject.Dictionary? =
    try {
      (parser(offset + "trailer".length).readObject() as? PdfObject.Dictionary)?.takeIf { it["Root"] != null }
    } catch (error: ScanBudget.Exceeded) {
      throw error
    } catch (error: PdfFormatException) {
      null
    }

  private fun checkRebuiltObjects(entries: Map<Int, XrefEntry>) {
    if (entries.size > MAX_REBUILT_OBJECTS) throw PdfFormatException("Too many objects to rebuild the cross-references")
  }

  /** The newest catalog in the file, and the newest cross-reference stream's dictionary naming one, with its offset. */
  private class FoundObjects(val catalog: PdfObject.Reference?, val newestXrefStream: Pair<Long, PdfObject.Dictionary>?)

  /**
   * Reads every object found once: for the newest catalog (used when no
   * trailer names one), for cross-reference streams (their dictionaries are
   * trailers) and for object streams, whose objects are invisible to the
   * scan, so they are listed too.
   */
  private fun readFoundObjects(entries: MutableMap<Int, XrefEntry>): FoundObjects {
    var catalog: PdfObject.Reference? = null
    var catalogOffset = -1L
    var newestXrefStream: Pair<Long, PdfObject.Dictionary>? = null
    for (number in entries.keys.sorted()) {
      val offset = (entries[number] as? XrefEntry.InFile)?.offset ?: continue
      val parser = parser(offset)
      val dictionary = try {
        parser.readIndirectHeader() ?: continue
        parser.readObject() as? PdfObject.Dictionary ?: continue
      } catch (error: ScanBudget.Exceeded) {
        throw error
      } catch (error: PdfFormatException) {
        continue
      }
      when (dictionary["Type"]) {
        PdfObject.Name("Catalog") -> if (offset > catalogOffset) {
          catalog = PdfObject.Reference(number, 0)
          catalogOffset = offset
        }
        PdfObject.Name("XRef") -> if (dictionary["Root"] != null && offset > (newestXrefStream?.first ?: -1)) {
          newestXrefStream = offset to dictionary
        }
        PdfObject.Name("ObjStm") -> indexObjectStream(number, parser, dictionary, entries)
        else -> Unit
      }
    }
    return FoundObjects(catalog, newestXrefStream)
  }

  /**
   * The newest catalog among the objects in object streams — the last in
   * the newest stream — read through the rebuilt [entries]: for files whose
   * trailers are all lost.
   */
  private fun findCompressedCatalog(entries: Map<Int, XrefEntry>): PdfObject.Reference? {
    val document = reader(entries)
    fun position(entry: XrefEntry.Compressed) = (entries[entry.streamNumber] as? XrefEntry.InFile)?.offset ?: -1L
    return entries.entries
      .filter { (number, entry) ->
        entry is XrefEntry.Compressed &&
          (document.resolve(PdfObject.Reference(number, 0)) as? PdfObject.Dictionary)?.get("Type") == PdfObject.Name("Catalog")
      }
      .maxWithOrNull(compareBy({ position(it.value as XrefEntry.Compressed) }, { (it.value as XrefEntry.Compressed).index }))
      ?.let { PdfObject.Reference(it.key, 0) }
  }

  /** Lists the objects of object stream [number], whose [dictionary] [parser] just read. */
  private fun indexObjectStream(
    number: Int,
    parser: PdfParser,
    dictionary: PdfObject.Dictionary,
    entries: MutableMap<Int, XrefEntry>,
  ) {
    val count = (dictionary["N"] as? PdfObject.Number)?.intValue ?: return
    if (count !in 0..ObjectStreamIndex.MAX_OBJECTS) return
    val data = try {
      // The length cannot be resolved before the cross-references exist.
      val stream = parser.readStream(dictionary) { (it as? PdfObject.Number)?.intValue } ?: return
      StreamDecoder.read(source, stream, budget) { it }
    } catch (error: ScanBudget.Exceeded) {
      throw error
    } catch (error: PdfFormatException) {
      return
    }
    budget.extend(data.size)
    val header = PdfParser(ByteArraySource(data), 0, budget)
    for (index in 0 until count) {
      val objectNumber = try {
        header.readToken()?.toIntOrNull()?.also { header.readToken() } // and its offset
      } catch (error: ScanBudget.Exceeded) {
        throw error
      } catch (error: PdfFormatException) {
        // A token too long: the objects listed before it stay.
        null
      } ?: break
      entries.putIfAbsent(objectNumber, XrefEntry.Compressed(number, index))
      checkRebuiltObjects(entries)
    }
  }

  // endregion

  private companion object {
    /** `startxref` is due in the last 1 KB; producers and downloads append junk after `%%EOF`. */
    const val TAIL_BYTES = 64 * 1024
    const val MAX_XREF_SECTIONS = 64
    const val MAX_XREF_ENTRIES = 10_000_000
    const val MAX_REBUILD_BYTES = 64L * 1024 * 1024
    const val MAX_REBUILT_OBJECTS = 1_000_000
    const val SCAN_WINDOW_BYTES = 64 * 1024
    const val MAX_NUMBER_DIGITS = 10
    const val MAX_GENERATION_DIGITS = 5
    /** The newest trailers tried: one per incremental update. */
    const val MAX_TRAILERS = 64
    val TRAILER = "trailer".toByteArray(Charsets.ISO_8859_1).map { it.toInt() }.toIntArray()
  }
}
