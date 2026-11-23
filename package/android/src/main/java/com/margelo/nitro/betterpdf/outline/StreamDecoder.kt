package com.margelo.nitro.betterpdf.outline

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
  private const val BLOCK_BYTES = 64 * 1024
  private const val MAX_JOINED_BYTES = 4 * 1024 * 1024

  /**
   * Reads [stream]'s data from [source] and decodes it; it and what it
   * decodes to are charged to [budget], as it decodes, and held
   * ([ScanBudget.hold]).
   */
  internal fun read(
    source: PdfByteSource,
    stream: PdfObject.Stream,
    budget: ScanBudget,
    resolve: (PdfObject?) -> PdfObject?,
  ): ByteArray {
    if (stream.dataLength > MAX_DECODED_BYTES) throw PdfFormatException("Stream longer than $MAX_DECODED_BYTES bytes")
    // Before it is read: resolving the filters can read other streams meanwhile.
    budget.hold(stream.dataLength)
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
        "FlateDecode", "Fl" -> unpredict(inflate(bytes, budget), parameters.getOrNull(index), budget, resolve)
        else -> throw PdfFormatException("Unsupported filter /$filter")
      }
    }
    return bytes
  }

  /**
   * Inflates [data] block by block: its size is known only at the end.
   * Joining the blocks takes twice the size for a moment, so beyond
   * [MAX_JOINED_BYTES] they are dropped, the rest is only measured, and the
   * data is inflated again into an array of its size.
   */
  private fun inflate(data: ByteArray, budget: ScanBudget): ByteArray {
    val inflater = Inflater()
    try {
      inflater.setInput(data)
      val blocks = ArrayList<ByteArray>()
      var block = ByteArray(BLOCK_BYTES)
      var filled = 0
      var size = 0
      var measuring = false
      while (!inflater.finished()) {
        if (filled == block.size) {
          if (size < MAX_JOINED_BYTES) {
            blocks += block
            block = ByteArray(BLOCK_BYTES)
          } else {
            blocks.clear()
            measuring = true
          }
          filled = 0
        }
        val count = try {
          inflater.inflate(block, filled, block.size - filled)
        } catch (error: DataFormatException) {
          // Truncated or corrupt data: keep what decoded, as viewers do.
          break
        }
        if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
        filled += count
        size += count
        budget.spendOnStream(count)
        if (size > MAX_DECODED_BYTES) throw PdfFormatException("Stream expands beyond $MAX_DECODED_BYTES bytes")
      }
      budget.hold(size)
      val output = ByteArray(size)
      if (!measuring) {
        var at = 0
        for (full in blocks) {
          full.copyInto(output, at)
          at += full.size
        }
        block.copyInto(output, at, 0, filled)
        return output
      }
      inflater.reset()
      inflater.setInput(data)
      var at = 0
      while (at < size) {
        val count = try {
          inflater.inflate(output, at, size - at)
        } catch (error: DataFormatException) {
          0
        }
        if (count == 0) break
        at += count
      }
      return if (at == size) output else output.copyOf(at)
    } finally {
      inflater.end()
    }
  }

  /** Reverses a PNG predictor (/Predictor 10–15); TIFF predictor 2 is not supported. */
  private fun unpredict(
    data: ByteArray,
    parameters: PdfObject.Dictionary?,
    budget: ScanBudget,
    resolve: (PdfObject?) -> PdfObject?,
  ): ByteArray {
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
    // A row is as long as the file declares, up to 64 MiB: nothing is allocated for less than one.
    val rows = data.size / (1 + rowLength)
    budget.hold(rows.toLong() * rowLength)
    val output = ByteArray(rows * rowLength)
    for (row in 0 until rows) {
      val from = row * (1 + rowLength)
      val to = row * rowLength
      val type = data[from].toInt() and 0xff
      for (i in 0 until rowLength) {
        val left = if (i >= bytesPerPixel) output[to + i - bytesPerPixel].toInt() and 0xff else 0
        val up = if (row > 0) output[to - rowLength + i].toInt() and 0xff else 0
        val upLeft = if (row > 0 && i >= bytesPerPixel) output[to - rowLength + i - bytesPerPixel].toInt() and 0xff else 0
        val raw = data[from + 1 + i].toInt() and 0xff
        val value = when (type) {
          0 -> raw
          1 -> raw + left
          2 -> raw + up
          3 -> raw + (left + up) / 2
          4 -> raw + paeth(left, up, upLeft)
          else -> throw PdfFormatException("Invalid PNG predictor row type $type")
        }
        output[to + i] = value.toByte()
      }
    }
    return output
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
