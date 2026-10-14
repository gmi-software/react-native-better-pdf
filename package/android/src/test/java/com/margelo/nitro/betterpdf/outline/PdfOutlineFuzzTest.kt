package com.margelo.nitro.betterpdf.outline

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Deterministic mutation fuzzing, and generated deep structures: the
 * readers parse untrusted files, so any corruption must end in a result or
 * a [PdfFormatException] — never another exception, a hang or a stack
 * overflow.
 */
class PdfOutlineFuzzTest {
  private fun load(vararg names: String) = names.map { name ->
    javaClass.getResourceAsStream("/outline/$name")!!.use { it.readBytes() }
  }

  /**
   * Reads 500 corrupted copies of [seeds] with [read]; each must end in a
   * result or a [PdfFormatException] within 2 s. Returns how many parsed.
   */
  private fun fuzz(seeds: List<ByteArray>, read: (PdfDocumentReader) -> Unit): Int {
    val random = Random(20260930)
    var parsed = 0
    repeat(500) { iteration ->
      val bytes = seeds[iteration % seeds.size].copyOf()
      val mutated = when (iteration % 3) {
        0 -> bytes.also { repeat(1 + random.nextInt(40)) { _ -> it[random.nextInt(it.size)] = random.nextInt(256).toByte() } }
        1 -> bytes.copyOf(random.nextInt(bytes.size))
        // Structural damage: overwrite a run with PDF delimiters.
        else -> bytes.also {
          val start = random.nextInt(it.size)
          val tokens = "<<[(/ 0 R >>]".toByteArray()
          for (i in start until minOf(it.size, start + 64)) it[i] = tokens[random.nextInt(tokens.size)]
        }
      }
      val started = System.nanoTime()
      try {
        read(PdfDocumentReader(ByteArraySource(mutated)))
        parsed++
      } catch (expected: PdfFormatException) {
        // Fine.
      }
      val millis = (System.nanoTime() - started) / 1_000_000
      assertTrue("iteration $iteration took $millis ms", millis < 2_000)
    }
    return parsed
  }

  @Test
  fun `corrupted files fail cleanly`() {
    val parsed = fuzz(load("outline.pdf", "outline-compressed.pdf", "outline-pypdf.pdf")) { PdfOutlineReader(it).read() }
    // Most single-byte damage is recoverable, as in real viewers.
    assertTrue("parsed $parsed of 500", parsed > 100)
  }

  @Test
  fun `corrupted information dictionaries fail cleanly`() {
    val parsed = fuzz(load("metadata.pdf", "metadata-compressed.pdf", "outline-pdfkit.pdf")) { PdfInfoReader(it).read() }
    assertTrue("parsed $parsed of 500", parsed > 100)
  }

  /** Links are read from every document the view opens. */
  @Test
  fun `corrupted links fail cleanly`() {
    val parsed = fuzz(load("links.pdf", "links-all.pdf", "outline.pdf")) { PdfLinkReader(it).read() }
    assertTrue("parsed $parsed of 500", parsed > 100)
  }

  @Test
  fun `corrupted forms fail cleanly`() {
    val parsed = fuzz(load("forms.pdf", "forms-compressed.pdf", "forms-boxes.pdf")) { PdfFormReader(it).read() }
    assertTrue("parsed $parsed of 500", parsed > 100)
  }

  /**
   * What byte flips cannot produce: chains of up to 2,000 objects of mixed
   * kinds, each needed to read the one before ([deepChainDocument]), below
   * up to 80 levels of outline, page tree and field tree, half of them with
   * a broken `startxref` (the rebuild). Every reader runs on a small stack
   * and must end in a result or a [PdfFormatException] within 2 s.
   */
  @Test
  fun `deep object chains fail cleanly`() {
    val readers = listOf<(PdfDocumentReader) -> Unit>(
      { PdfLinkReader(it).read() },
      { PdfOutlineReader(it).read() },
      { PdfFormReader(it).read() },
      { PdfInfoReader(it).read() },
      { PdfPageSizeReader(it).read() },
    )
    val random = Random(20261014)
    var parsed = 0
    repeat(60) { iteration ->
      val chain = List(random.nextInt(2_000)) { ChainStep.entries.random(random) }
      var bytes = deepChainDocument(chain, nesting = 1 + random.nextInt(80))
      if (iteration % 2 == 1) {
        bytes = bytes.toString(Charsets.ISO_8859_1)
          .replace(Regex("startxref\n\\d+"), "startxref\n999999999")
          .toByteArray(Charsets.ISO_8859_1)
      }
      readers.forEachIndexed { index, read ->
        val started = System.nanoTime()
        try {
          onSmallStack { read(PdfDocumentReader(ByteArraySource(bytes))) }
          parsed++
        } catch (expected: PdfFormatException) {
          // Fine.
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("iteration $iteration, reader $index took $millis ms", millis < 2_000)
      }
    }
    // Only a page tree deeper than the readers' limit fails.
    assertTrue("parsed $parsed of 300", parsed > 250)
  }
}
