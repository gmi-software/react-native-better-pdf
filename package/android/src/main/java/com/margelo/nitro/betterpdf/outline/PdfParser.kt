package com.margelo.nitro.betterpdf.outline

import java.io.ByteArrayOutputStream

/**
 * Reads PDF tokens and objects (ISO 32000-1 §7.2–7.3) from [source],
 * starting at [position]. Buffered, forward-only between seeks.
 *
 * The input is untrusted: nesting, string and dictionary sizes are capped
 * and every malformed construct fails with [PdfFormatException] instead of
 * looping or allocating without bound. Each byte examined is charged to
 * [budget], which the parsers of one read share.
 */
class PdfParser internal constructor(
  private val source: PdfByteSource,
  var position: Long,
  private val budget: ScanBudget?,
) {
  constructor(source: PdfByteSource, position: Long) : this(source, position, null)

  private var window = ByteArray(0)
  private var windowStart = 0L

  private fun byteAt(at: Long): Int {
    if (at < 0 || at >= source.size) return EOF
    budget?.spend()
    if (at < windowStart || at >= windowStart + window.size) {
      // Most objects are small: read a little at a new position, more and
      // more while reading on (a long string, a stream scanned to its end).
      val length = if (at == windowStart + window.size) window.size * 2 else 0
      windowStart = at
      window = source.read(at, length.coerceIn(MIN_WINDOW_BYTES, MAX_WINDOW_BYTES))
      if (window.isEmpty()) return EOF
    }
    return window[(at - windowStart).toInt()].toInt() and 0xff
  }

  fun peek(): Int = byteAt(position)

  private fun next(): Int {
    val value = byteAt(position)
    if (value != EOF) position++
    return value
  }

  fun skipWhitespaceAndComments() {
    while (true) {
      val c = peek()
      when {
        c == EOF -> return
        isWhitespace(c) -> position++
        c == '%'.code -> while (peek() != EOF && peek() != '\n'.code && peek() != '\r'.code) position++
        else -> return
      }
    }
  }

  /** The next run of regular characters (a keyword or number), or null at a delimiter / EOF. */
  fun readToken(): String? {
    skipWhitespaceAndComments()
    val builder = StringBuilder()
    while (true) {
      val c = peek()
      if (c == EOF || isWhitespace(c) || isDelimiter(c)) break
      builder.append(c.toChar())
      position++
      if (builder.length > MAX_TOKEN_LENGTH) throw PdfFormatException("Token too long at $position")
    }
    return builder.takeIf { it.isNotEmpty() }?.toString()
  }

  /** Consumes [keyword] if it is the next token; otherwise leaves the position unchanged. */
  fun matchKeyword(keyword: String): Boolean {
    val start = position
    if (readToken() == keyword) return true
    position = start
    return false
  }

  /** Reads `number generation obj`; null (position unchanged) when absent. */
  fun readIndirectHeader(): PdfObject.Reference? {
    val start = position
    val number = readToken()?.toIntOrNull()
    val generation = readToken()?.toIntOrNull()
    if (number == null || generation == null || !matchKeyword("obj")) {
      position = start
      return null
    }
    return PdfObject.Reference(number, generation)
  }

  /**
   * Reads the body of an indirect object (after its header). A dictionary
   * followed by `stream` becomes a [PdfObject.Stream]; its length comes from
   * `/Length`, resolved through [resolveLength] when indirect, or from the
   * `endstream` keyword when missing or wrong.
   */
  fun readIndirectBody(resolveLength: (PdfObject) -> Int?): PdfObject {
    val value = readObject()
    if (value !is PdfObject.Dictionary) return value
    return readStream(value, resolveLength) ?: value
  }

  /**
   * The stream whose [dictionary] was just read, when the `stream` keyword
   * follows; null (position unchanged) otherwise. See [readIndirectBody].
   */
  internal fun readStream(dictionary: PdfObject.Dictionary, resolveLength: (PdfObject) -> Int?): PdfObject.Stream? {
    val afterDictionary = position
    if (!matchKeyword("stream")) {
      position = afterDictionary
      return null
    }
    // The keyword is followed by CRLF or LF (tolerate a lone CR).
    if (peek() == '\r'.code) position++
    if (peek() == '\n'.code) position++
    val dataOffset = position
    val declared = dictionary["Length"]?.let(resolveLength)
    val length = declared?.takeIf { it >= 0 && endstreamFollows(dataOffset + it) } ?: findEndstream(dataOffset)
    position = dataOffset + length
    return PdfObject.Stream(dictionary, dataOffset, length)
  }

  private fun endstreamFollows(at: Long): Boolean {
    val saved = position
    position = at
    val found = matchKeyword("endstream")
    position = saved
    return found
  }

  /** Length of stream data by scanning for `endstream` (trailing EOL excluded). */
  private fun findEndstream(dataOffset: Long): Int {
    val marker = "endstream".toByteArray(Charsets.ISO_8859_1)
    var at = dataOffset
    var matched = 0
    while (at - dataOffset < MAX_STREAM_SCAN_BYTES) {
      val c = byteAt(at)
      if (c == EOF) break
      matched = if (c == marker[matched].toInt()) matched + 1 else if (c == marker[0].toInt()) 1 else 0
      at++
      if (matched == marker.size) {
        var end = at - marker.size
        if (end > dataOffset && byteAt(end - 1) == '\n'.code) end--
        if (end > dataOffset && byteAt(end - 1) == '\r'.code) end--
        return (end - dataOffset).toInt()
      }
    }
    throw PdfFormatException("Unterminated stream at $dataOffset")
  }

  fun readObject(depth: Int = 0): PdfObject {
    if (depth > MAX_NESTING) throw PdfFormatException("Objects nested too deeply at $position")
    skipWhitespaceAndComments()
    val c = peek()
    return when {
      c == EOF -> throw PdfFormatException("Unexpected end of file")
      c == '/'.code -> readName()
      c == '('.code -> readLiteralString()
      c == '<'.code -> if (byteAt(position + 1) == '<'.code) readDictionary(depth) else readHexString()
      c == '['.code -> readArray(depth)
      c == '+'.code || c == '-'.code || c == '.'.code || c in '0'.code..'9'.code -> readNumberOrReference()
      else -> when (val token = readToken()) {
        "true" -> PdfObject.Bool(true)
        "false" -> PdfObject.Bool(false)
        "null" -> PdfObject.Null
        else -> throw PdfFormatException("Unexpected token \"${token ?: c.toChar()}\" at $position")
      }
    }
  }

  private fun readNumberOrReference(): PdfObject {
    val first = parseNumber(readToken())
    val integer = first.intValue ?: return first
    if (integer < 0) return first
    // `number generation R`?
    val afterFirst = position
    val generation = readToken()?.toIntOrNull()
    if (generation != null && generation >= 0 && matchKeyword("R")) {
      return PdfObject.Reference(integer, generation)
    }
    position = afterFirst
    return first
  }

  private fun parseNumber(token: String?): PdfObject.Number {
    // Tolerate producer quirks such as "--5" or "5-": keep the valid prefix.
    val cleaned = token.orEmpty().let { raw ->
      val sign = if (raw.startsWith("-")) "-" else ""
      sign + raw.trimStart('+', '-').takeWhile { it.isDigit() || it == '.' }
    }
    return PdfObject.Number(cleaned.toDoubleOrNull() ?: 0.0)
  }

  private fun readName(): PdfObject.Name {
    position++ // '/'
    val bytes = ByteArrayOutputStream()
    while (true) {
      val c = peek()
      if (c == EOF || isWhitespace(c) || isDelimiter(c)) break
      position++
      if (c == '#'.code) {
        val high = hexValue(peek())
        val low = hexValue(byteAt(position + 1))
        if (high >= 0 && low >= 0) {
          position += 2
          bytes.write(high * 16 + low)
          continue
        }
      }
      bytes.write(c)
      if (bytes.size() > MAX_TOKEN_LENGTH) throw PdfFormatException("Name too long at $position")
    }
    return PdfObject.Name(bytes.toByteArray().toString(Charsets.ISO_8859_1))
  }

  private fun readLiteralString(): PdfObject.Str {
    position++ // '('
    val bytes = ByteArrayOutputStream()
    var nesting = 1
    while (true) {
      if (bytes.size() > MAX_STRING_BYTES) throw PdfFormatException("String too long at $position")
      val c = next()
      when (c) {
        EOF -> throw PdfFormatException("Unterminated string")
        '('.code -> {
          nesting++
          bytes.write(c)
        }
        ')'.code -> {
          nesting--
          if (nesting == 0) return PdfObject.Str(bytes.toByteArray())
          bytes.write(c)
        }
        '\\'.code -> readEscape(bytes)
        '\r'.code -> {
          // Any end-of-line inside a string reads as a single LF.
          if (peek() == '\n'.code) position++
          bytes.write('\n'.code)
        }
        else -> bytes.write(c)
      }
    }
  }

  private fun readEscape(bytes: ByteArrayOutputStream) {
    when (val c = next()) {
      'n'.code -> bytes.write('\n'.code)
      'r'.code -> bytes.write('\r'.code)
      't'.code -> bytes.write('\t'.code)
      'b'.code -> bytes.write(8)
      'f'.code -> bytes.write(12)
      '('.code, ')'.code, '\\'.code -> bytes.write(c)
      '\r'.code -> if (peek() == '\n'.code) position++ // line continuation
      '\n'.code -> Unit
      in '0'.code..'7'.code -> {
        var value = c - '0'.code
        repeat(2) {
          val digit = peek()
          if (digit in '0'.code..'7'.code) {
            value = value * 8 + (digit - '0'.code)
            position++
          }
        }
        bytes.write(value and 0xff)
      }
      EOF -> throw PdfFormatException("Unterminated string")
      else -> bytes.write(c) // unknown escape: the backslash is ignored
    }
  }

  private fun readHexString(): PdfObject.Str {
    position++ // '<'
    val bytes = ByteArrayOutputStream()
    var high = -1
    while (true) {
      val c = next()
      if (c == EOF) throw PdfFormatException("Unterminated hex string")
      if (c == '>'.code) break
      val value = hexValue(c)
      if (value < 0) continue // whitespace and junk are ignored
      if (high < 0) {
        high = value
      } else {
        bytes.write(high * 16 + value)
        high = -1
      }
      if (bytes.size() > MAX_STRING_BYTES) throw PdfFormatException("String too long at $position")
    }
    if (high >= 0) bytes.write(high * 16) // odd digit count: a trailing 0 is implied
    return PdfObject.Str(bytes.toByteArray())
  }

  private fun readArray(depth: Int): PdfObject.Array {
    position++ // '['
    val items = ArrayList<PdfObject>()
    while (true) {
      skipWhitespaceAndComments()
      if (peek() == ']'.code) {
        position++
        return PdfObject.Array(items)
      }
      items += readObject(depth + 1)
      if (items.size > MAX_COLLECTION_SIZE) throw PdfFormatException("Array too large at $position")
    }
  }

  private fun readDictionary(depth: Int): PdfObject.Dictionary {
    position += 2 // '<<'
    val entries = LinkedHashMap<String, PdfObject>()
    while (true) {
      skipWhitespaceAndComments()
      if (peek() == '>'.code && byteAt(position + 1) == '>'.code) {
        position += 2
        return PdfObject.Dictionary(entries)
      }
      val key = readObject(depth + 1) as? PdfObject.Name
        ?: throw PdfFormatException("Dictionary key is not a name at $position")
      entries[key.value] = readObject(depth + 1)
      if (entries.size > MAX_COLLECTION_SIZE) throw PdfFormatException("Dictionary too large at $position")
    }
  }

  // Internal for the rebuild's scan for objects (isWhitespace, isDelimiter).
  internal companion object {
    private const val EOF = -1
    private const val MIN_WINDOW_BYTES = 512
    private const val MAX_WINDOW_BYTES = 64 * 1024
    private const val MAX_NESTING = 64
    private const val MAX_TOKEN_LENGTH = 1024
    private const val MAX_STRING_BYTES = 1 shl 20
    private const val MAX_COLLECTION_SIZE = 1_000_000
    private const val MAX_STREAM_SCAN_BYTES = 64L * 1024 * 1024

    fun isWhitespace(c: Int): Boolean = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32

    fun isDelimiter(c: Int): Boolean =
      c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code || c == '['.code ||
        c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code

    private fun hexValue(c: Int): Int = when (c) {
      in '0'.code..'9'.code -> c - '0'.code
      in 'a'.code..'f'.code -> c - 'a'.code + 10
      in 'A'.code..'F'.code -> c - 'A'.code + 10
      else -> -1
    }
  }
}
