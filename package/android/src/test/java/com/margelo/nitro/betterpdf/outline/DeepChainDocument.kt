package com.margelo.nitro.betterpdf.outline

import java.io.ByteArrayOutputStream

/** How one object of a chain needs the next one to be read. */
internal enum class ChainStep {
  /** A stream in the file whose `/Length` is the next object. */
  LENGTH,

  /** An object in an object stream whose `/Length` is the next object. */
  OBJECT_STREAM,

  /** An object in an object stream whose `/Filter` is the next object. */
  OBJECT_STREAM_FILTER,
}

/**
 * A one-page document whose parts the readers look at — a link's action,
 * an outline item's action, a form field's value, an information entry, the
 * page's crop box — all lead into [chain]: distinct objects, each of which
 * can only be read after the next one. The outline, the page tree and the
 * field tree are [nesting] levels deep, with the chain at the bottom. A
 * second link (a URL) and the rest of the document do not depend on it.
 */
internal fun deepChainDocument(chain: List<ChainStep>, nesting: Int = 1): ByteArray {
  val pdf = TestPdfWriter()
  // Each step's object and, for an object stream, the stream; the head is object 1.
  val objects = chain.map { step -> pdf.reserve() to if (step == ChainStep.LENGTH) null else pdf.reserve() }
  chain.forEachIndexed { index, step ->
    val (number, stream) = objects[index]
    val next = objects.getOrNull(index + 1)?.let { "${it.first} 0 R" }
    when (step) {
      ChainStep.LENGTH -> pdf.add(number, "<< ${next?.let { "/Length $it" }.orEmpty()} >>\nstream\nchain\nendstream")
      ChainStep.OBJECT_STREAM, ChainStep.OBJECT_STREAM_FILTER -> {
        val key = if (step == ChainStep.OBJECT_STREAM) "/Length" else "/Filter"
        pdf.objectStream(stream!!, next?.let { "$key $it" }.orEmpty())
        pdf.addCompressed(number, stream, "0")
      }
    }
  }
  val head = objects.firstOrNull()?.let { "${it.first} 0 R" } ?: "null"

  val catalog = pdf.reserve()
  val info = pdf.reserve()
  val page = pdf.reserve()
  val chainLink = pdf.reserve()
  val urlLink = pdf.reserve()

  // Outline: the deepest item's action is the chain.
  val outline = pdf.reserve()
  val items = List(nesting) { pdf.reserve() }
  pdf.add(outline, "<< /Type /Outlines /First ${items[0]} 0 R >>")
  items.forEachIndexed { level, item ->
    val child = items.getOrNull(level + 1)?.let { "/First $it 0 R" } ?: "/A $head"
    pdf.add(item, "<< /Title (Level $level) $child >>")
  }

  // Page tree: the page's crop box is the chain.
  val nodes = List(nesting) { pdf.reserve() }
  nodes.forEachIndexed { level, node ->
    val kid = nodes.getOrNull(level + 1) ?: page
    pdf.add(node, "<< /Type /Pages /Kids [$kid 0 R] /Count 1 >>")
  }

  // Field tree: the terminal field's value is the chain; it is its own widget.
  val fields = List(nesting) { pdf.reserve() }
  fields.forEachIndexed { level, field ->
    val body = fields.getOrNull(level + 1)?.let { "/Kids [$it 0 R]" }
      ?: "/FT /Tx /V $head /Type /Annot /Subtype /Widget /Rect [0 200 100 220]"
    pdf.add(field, "<< /T (f$level) $body >>")
  }

  pdf.add(
    page,
    "<< /Type /Page /Parent ${nodes.last()} 0 R /MediaBox [0 0 200 300] /CropBox $head " +
      "/Annots [$chainLink 0 R $urlLink 0 R ${fields.last()} 0 R] >>",
  )
  pdf.add(chainLink, "<< /Type /Annot /Subtype /Link /Rect [0 0 100 100] /A $head >>")
  pdf.add(urlLink, "<< /Type /Annot /Subtype /Link /Rect [0 100 100 200] /A << /S /URI /URI (https://example.com/deep) >> >>")
  pdf.add(info, "<< /Title (Deep) /Author $head >>")
  pdf.add(
    catalog,
    "<< /Type /Catalog /Pages ${nodes.first()} 0 R /Outlines $outline 0 R /AcroForm << /Fields [${fields.first()} 0 R] >> >>",
  )
  return pdf.build("/Root $catalog 0 R /Info $info 0 R")
}

/**
 * Runs [block] on a thread with a 256 KB stack, a quarter of an Android
 * thread's, and returns its result or rethrows what it threw.
 */
internal fun <T> onSmallStack(block: () -> T): T {
  var result: Result<T>? = null
  val thread = Thread(null, { result = runCatching(block) }, "small-stack", 256L * 1024)
  thread.start()
  thread.join()
  return result!!.getOrThrow()
}

/**
 * Writes small PDFs: objects in the file and in uncompressed object
 * streams, listed by a cross-reference stream when there are object
 * streams and by a classic table otherwise.
 */
internal class TestPdfWriter {
  private var nextNumber = 1
  private val inFile = sortedMapOf<Int, String>()
  private val objectStreams = sortedMapOf<Int, ObjectStream>()

  private class ObjectStream(val entries: String) {
    val members = ArrayList<Pair<Int, String>>()
  }

  fun reserve(): Int = nextNumber++

  /** An object in the file: [body] goes between `obj` and `endobj`. */
  fun add(number: Int, body: String) {
    inFile[number] = body
  }

  /** An object stream; [entries] join its dictionary (a direct `/Length` is added unless they have one). */
  fun objectStream(number: Int, entries: String) {
    objectStreams[number] = ObjectStream(entries)
  }

  /** An object in object stream [stream]. */
  fun addCompressed(number: Int, stream: Int, body: String) {
    objectStreams.getValue(stream).members += number to body
  }

  /** The file; [trailer] entries (`/Root` …) join the trailer. */
  fun build(trailer: String): ByteArray {
    val out = ByteArrayOutputStream()
    fun write(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))
    write("%PDF-1.7\n")
    val offsets = HashMap<Int, Int>()
    for ((number, body) in inFile) {
      offsets[number] = out.size()
      write("$number 0 obj\n$body\nendobj\n")
    }
    val compressed = HashMap<Int, Pair<Int, Int>>()
    for ((number, stream) in objectStreams) {
      val header = StringBuilder()
      val objects = StringBuilder()
      stream.members.forEachIndexed { index, (member, body) ->
        header.append("$member ${objects.length} ")
        objects.append(body).append('\n')
        compressed[member] = number to index
      }
      val data = "$header$objects"
      val length = if ("/Length" in stream.entries) "" else "/Length ${data.length}"
      offsets[number] = out.size()
      write(
        "$number 0 obj\n<< /Type /ObjStm /N ${stream.members.size} /First ${header.length} $length ${stream.entries} >>\n" +
          "stream\n$data\nendstream\nendobj\n",
      )
    }
    val size = nextNumber
    val start = out.size()
    if (compressed.isEmpty()) {
      write("xref\n0 $size\n")
      for (number in 0 until size) {
        val offset = offsets[number]
        write(if (offset == null) "0000000000 65535 f\r\n" else "%010d 00000 n\r\n".format(offset))
      }
      write("trailer\n<< /Size $size $trailer >>\n")
    } else {
      // The cross-reference stream is the last object: /W [1 4 2].
      offsets[size] = start
      val rows = ByteArrayOutputStream()
      fun row(type: Int, field: Int, index: Int) {
        rows.write(type)
        for (shift in intArrayOf(24, 16, 8, 0)) rows.write(field ushr shift and 0xff)
        rows.write(index ushr 8 and 0xff)
        rows.write(index and 0xff)
      }
      for (number in 0..size) {
        val offset = offsets[number]
        val member = compressed[number]
        when {
          offset != null -> row(1, offset, 0)
          member != null -> row(2, member.first, member.second)
          else -> row(0, 0, 0)
        }
      }
      write("$size 0 obj\n<< /Type /XRef /Size ${size + 1} /W [1 4 2] /Length ${rows.size()} $trailer >>\nstream\n")
      out.write(rows.toByteArray())
      write("\nendstream\nendobj\n")
    }
    write("startxref\n$start\n%%EOF\n")
    return out.toByteArray()
  }
}
