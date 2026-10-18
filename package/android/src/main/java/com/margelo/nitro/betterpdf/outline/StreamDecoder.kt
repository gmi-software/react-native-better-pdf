package com.margelo.nitro.betterpdf.outline

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Decodes stream data for the filters that carry document structure in
 * practice: FlateDecode, optionally with PNG predictors (xref and object
 * streams). A stream's data, read or decoded, is capped: a small compressed
 * stream can expand to gigabytes ("zip bomb").
 */
object StreamDecoder {
  /** The most a stream's data may be, read or decoded. */
  const val MAX_DECODED_BYTES = 32 * 1024 * 1024

  /**
   * Reads [stream]'s data from [source] and decodes it; it and what it
   * decodes to are charged to [budget], as it decodes.
   */
  internal fun read(
    source: PdfByteSource,
    stream: PdfObject.Stream,
    budget: ScanBudget,
    resolve: (PdfObject?) -> PdfObject?,
  ): ByteArray {
    if (stream.dataLength > MAX_DECODED_BYTES) throw PdfFormatException("Stream longer than $MAX_DECODED_BYTES bytes")
    return decode(stream.dictionary, source.read(stream.dataOffset, stream.dataLength), budget, resolve)
  }

  private fun decode(
    dictionary: PdfObject.Dictionary,
    data: ByteArray,
    budget: ScanBudget,
    resolve: (PdfObject?) -> PdfObject?,
  ): ByteArray {
    budget.spendOnStream(data.size)
    val filters = when (val filter = resolve(dictionary["Filter"])) {
      null, PdfObject.Null -> emptyList()
      is PdfObject.Name -> listOf(filter.value)
      is PdfObject.Array -> filter.items.map { (resolve(it) as? PdfObject.Name)?.value ?: "" }
      else -> throw PdfFormatException("Invalid /Filter")
    }
    val parameters = when (val params = resolve(dictionary["DecodeParms"])) {
      is PdfObject.Dictionary -> listOf(params)
      is PdfObject.Array -> params.items.map { resolve(it) as? PdfObject.Dictionary }
      else -> emptyList()
    }
    var bytes = data
    filters.forEachIndexed { index, filter ->
      bytes = when (filter) {
        "FlateDecode", "Fl" -> unpredict(inflate(bytes, budget), parameters.getOrNull(index), resolve)
        else -> throw PdfFormatException("Unsupported filter /$filter")
      }
    }
    return bytes
  }

  private fun inflate(data: ByteArray, budget: ScanBudget): ByteArray {
    val inflater = Inflater()
    try {
      inflater.setInput(data)
      val output = ByteArrayOutputStream(data.size * 4)
      val buffer = ByteArray(64 * 1024)
      while (!inflater.finished()) {
        val count = try {
          inflater.inflate(buffer)
        } catch (error: DataFormatException) {
          // Truncated or corrupt data: keep what decoded, as viewers do.
          break
        }
        if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
        output.write(buffer, 0, count)
        budget.spendOnStream(count)
        if (output.size() > MAX_DECODED_BYTES) throw PdfFormatException("Stream expands beyond $MAX_DECODED_BYTES bytes")
      }
      return output.toByteArray()
    } finally {
      inflater.end()
    }
  }

  /** Reverses a PNG predictor (/Predictor 10–15); TIFF predictor 2 is not supported. */
  private fun unpredict(data: ByteArray, parameters: PdfObject.Dictionary?, resolve: (PdfObject?) -> PdfObject?): ByteArray {
    fun int(key: String, default: Int): Int = (resolve(parameters?.get(key)) as? PdfObject.Number)?.intValue ?: default
    val predictor = int("Predictor", 1)
    if (predictor < 10) {
      if (predictor == 2) throw PdfFormatException("Unsupported TIFF predictor")
      return data
    }
    val colors = int("Colors", 1).coerceIn(1, 32)
    val bitsPerComponent = int("BitsPerComponent", 8).coerceIn(1, 16)
    val columns = int("Columns", 1).coerceIn(1, 1 shl 20)
    val bytesPerPixel = maxOf(1, colors * bitsPerComponent / 8)
    val rowLength = (colors * bitsPerComponent * columns + 7) / 8
    // Before allocating a row of the length the file declares (up to 64 MiB).
    if (data.size < 1 + rowLength) return ByteArray(0)
    val output = ByteArrayOutputStream(data.size)
    var previous = ByteArray(rowLength)
    var at = 0
    while (at + 1 + rowLength <= data.size) {
      val type = data[at].toInt() and 0xff
      val row = data.copyOfRange(at + 1, at + 1 + rowLength)
      for (i in 0 until rowLength) {
        val left = if (i >= bytesPerPixel) row[i - bytesPerPixel].toInt() and 0xff else 0
        val up = previous[i].toInt() and 0xff
        val upLeft = if (i >= bytesPerPixel) previous[i - bytesPerPixel].toInt() and 0xff else 0
        val raw = row[i].toInt() and 0xff
        val value = when (type) {
          0 -> raw
          1 -> raw + left
          2 -> raw + up
          3 -> raw + (left + up) / 2
          4 -> raw + paeth(left, up, upLeft)
          else -> throw PdfFormatException("Invalid PNG predictor row type $type")
        }
        row[i] = value.toByte()
      }
      output.write(row)
      previous = row
      at += 1 + rowLength
    }
    return output.toByteArray()
  }

  private fun paeth(left: Int, up: Int, upLeft: Int): Int {
    val estimate = left + up - upLeft
    val toLeft = Math.abs(estimate - left)
    val toUp = Math.abs(estimate - up)
    val toUpLeft = Math.abs(estimate - upLeft)
    return when {
      toLeft <= toUp && toLeft <= toUpLeft -> left
      toUp <= toUpLeft -> up
      else -> upLeft
    }
  }
}
