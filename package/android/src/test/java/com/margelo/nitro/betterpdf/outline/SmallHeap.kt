package com.margelo.nitro.betterpdf.outline

import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/** How a read ended. */
internal enum class ReadEnd { RESULT, FORMAT_EXCEPTION, OUT_OF_MEMORY }

internal val READERS = linkedMapOf<String, (PdfDocumentReader) -> Unit>(
  "links" to { PdfLinkReader(it).read() },
  "outline" to { PdfOutlineReader(it).read() },
  "forms" to { PdfFormReader(it).read() },
  "information" to { PdfInfoReader(it).read() },
  "page sizes" to { PdfPageSizeReader(it).read() },
)

internal fun endOf(read: () -> Unit): ReadEnd =
  try {
    read()
    ReadEnd.RESULT
  } catch (error: PdfFormatException) {
    ReadEnd.FORMAT_EXCEPTION
  } catch (error: OutOfMemoryError) {
    ReadEnd.OUT_OF_MEMORY
  }

/**
 * How reading [bytes] with each of [readers] ends in a JVM of its own with
 * a heap of [megabytes] MB. What a read holds at once shows only against a
 * limit: counting allocations counts garbage too. The file is read from
 * disk, as on a device, so its bytes are not on that heap.
 */
internal fun readInHeapOf(
  megabytes: Int,
  bytes: ByteArray,
  readers: Collection<String> = READERS.keys,
): Map<String, ReadEnd> {
  val file = File.createTempFile("read", ".pdf")
  try {
    file.writeBytes(bytes)
    return runInHeapOf(megabytes, "read", file.path, *readers.toTypedArray())
      .filter { '=' in it }
      .associate { it.substringBefore('=') to ReadEnd.valueOf(it.substringAfter('=')) }
  } finally {
    file.delete()
  }
}

/** Runs [main] with [arguments] in a JVM with a heap of [megabytes] MB and returns the lines it printed. */
internal fun runInHeapOf(megabytes: Int, vararg arguments: String): List<String> {
  val output = File.createTempFile("small-heap", ".txt")
  try {
    val process = ProcessBuilder(
      File(System.getProperty("java.home"), "bin/java").path,
      "-Xmx${megabytes}m",
      // One contiguous heap: a collector with regions can refuse an array that fits.
      "-XX:+UseSerialGC",
      "-cp",
      System.getProperty("java.class.path"),
      "com.margelo.nitro.betterpdf.outline.SmallHeapKt",
      *arguments,
    ).redirectErrorStream(true).redirectOutput(output).start()
    if (!process.waitFor(120, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      throw AssertionError("Still running after 120 s: ${output.readText()}")
    }
    val lines = output.readLines()
    if (process.exitValue() != 0) throw AssertionError("Exit code ${process.exitValue()}: $lines")
    return lines
  } finally {
    output.delete()
  }
}

/** The JVM [runInHeapOf] starts: `read <file> <reader>…` prints how each read ended; `fuzz` runs [readManyReferences]. */
fun main(arguments: Array<String>) {
  when (arguments[0]) {
    "read" -> FileInputStream(arguments[1]).channel.use { channel ->
      for (name in arguments.drop(2)) {
        val read = READERS.getValue(name)
        println("$name=${endOf { read(PdfDocumentReader(FileChannelByteSource(channel))) }}")
      }
    }
    "fuzz" -> println(readManyReferences() ?: "ok")
  }
}
