package com.margelo.nitro.betterpdf.outline

/**
 * A valid one-page document in which [references] links, as many form
 * fields and as many outline titles, the crop box and an information entry
 * are the objects [add] writes, whose numbers it returns: each under
 * another number ([TestPdfWriter.alias]) or, when [aliased] says so, its
 * own.
 */
internal fun aliasedDocument(
  references: Int,
  aliased: () -> Boolean = { true },
  add: (TestPdfWriter) -> List<Int>,
): ByteArray {
  val pdf = TestPdfWriter()
  val catalog = pdf.reserve()
  val pages = pdf.reserve()
  val page = pdf.reserve()
  val info = pdf.reserve()
  val outline = pdf.reserve()
  val targets = add(pdf)
  var next = 0
  fun alias(): Int {
    val target = targets[next++ % targets.size]
    return if (aliased()) pdf.reserve().also { pdf.alias(it, target) } else target
  }
  fun references(numbers: List<Int>) = numbers.joinToString(" ") { "$it 0 R" }

  val links = List(references) { alias() }
  val fields = List(references) { alias() }
  val items = List(references) { pdf.reserve() }
  items.forEachIndexed { index, item ->
    val following = items.getOrNull(index + 1)?.let { "/Next $it 0 R" }.orEmpty()
    pdf.add(item, "<< /Title ${alias()} 0 R $following >>")
  }
  pdf.add(outline, "<< /Type /Outlines /First ${items.first()} 0 R >>")
  pdf.add(
    catalog,
    "<< /Type /Catalog /Pages $pages 0 R /Outlines $outline 0 R /AcroForm << /Fields [${references(fields)}] >> >>",
  )
  pdf.add(pages, "<< /Type /Pages /Kids [$page 0 R] /Count 1 >>")
  pdf.add(
    page,
    "<< /Type /Page /Parent $pages 0 R /MediaBox [0 0 200 300] /CropBox ${alias()} 0 R /Annots [${references(links)}] >>",
  )
  pdf.add(info, "<< /Title ${alias()} 0 R >>")
  return pdf.build("/Root $catalog 0 R /Info $info 0 R")
}
