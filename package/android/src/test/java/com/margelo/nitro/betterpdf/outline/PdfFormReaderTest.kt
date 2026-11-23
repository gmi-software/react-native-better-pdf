package com.margelo.nitro.betterpdf.outline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures: benchmarks/generate-fixtures.mjs `forms*`. Names, order, values
 * and widget places were cross-checked with PDFKit (`fieldName`,
 * `widgetStringValue`, bounds converted to the displayed page).
 */
class PdfFormReaderTest {
  private fun read(name: String): List<FormFieldEntry> {
    val bytes = javaClass.getResourceAsStream("/outline/$name")!!.use { it.readBytes() }
    return PdfFormReader(PdfDocumentReader(ByteArraySource(bytes))).read()
  }

  private fun List<FormFieldEntry>.named(name: String) = single { it.name == name }

  @Test
  fun `every terminal field in the field tree's order`() {
    assertEquals(
      listOf(
        "name", "email", "notes", "agree", "plan", "country", "colours", "id",
        "phone", "address.city", "postcode", "date", "signature",
      ),
      read("forms.pdf").map { it.name },
    )
  }

  @Test
  fun `types, flags and values`() {
    val fields = read("forms.pdf")
    with(fields.named("name")) {
      assertEquals(FormFieldKind.TEXT, kind)
      assertTrue(isRequired)
      assertEquals("", text)
    }
    assertEquals("jan@example.com", fields.named("email").text)
    assertTrue(fields.named("notes").flags and FormFieldEntry.FLAG_MULTILINE != 0)
    with(fields.named("agree")) {
      assertEquals(FormFieldKind.CHECKBOX, kind)
      assertEquals(false, isChecked)
    }
    with(fields.named("plan")) {
      assertEquals(FormFieldKind.RADIO, kind)
      assertEquals(listOf("basic", "pro"), options)
      assertEquals(listOf("basic"), selectedOptions)
      assertEquals(2, widgets.size)
    }
    with(fields.named("country")) {
      assertEquals(FormFieldKind.COMBO_BOX, kind)
      assertEquals(listOf("Poland", "Germany", "France"), options)
      assertEquals("Poland", text)
    }
    with(fields.named("colours")) {
      assertEquals(FormFieldKind.LIST_BOX, kind)
      assertEquals(listOf("Green"), selectedOptions)
    }
    with(fields.named("id")) {
      assertTrue(isReadOnly)
      assertEquals("A-1234", text)
    }
    with(fields.named("signature")) {
      assertEquals(FormFieldKind.SIGNATURE, kind)
      assertEquals(false, isSigned)
      assertEquals(1, widgets.single().page)
    }
  }

  @Test
  fun `widgets in the displayed page's top-left coordinates`() {
    val fields = read("forms.pdf")
    // /Rect [200 750 500 776] on an A4 page.
    assertEquals(FormWidgetEntry(0, 200f, 66f, 300f, 26f, onState = null), fields.named("name").widgets.single())
    assertEquals(
      listOf(FormWidgetEntry(0, 200f, 320f, 20f, 20f, "basic"), FormWidgetEntry(0, 300f, 320f, 20f, 20f, "pro")),
      fields.named("plan").widgets,
    )
    assertEquals("Yes", fields.named("agree").widgets.single().onState)
  }

  @Test
  fun `cropped and rotated pages, widgets found through the pages' annotations`() {
    val fields = read("forms-boxes.pdf")
    assertEquals(FormWidgetEntry(0, 50f, 70f, 200f, 30f, null), fields.named("cropped").widgets.single())
    assertEquals("on a cropped page", fields.named("cropped").text)
    assertEquals(FormWidgetEntry(1, 700f, 100f, 30f, 200f, null), fields.named("rotated").widgets.single())
  }

  @Test
  fun `object streams and a cross-reference stream`() {
    assertEquals(read("forms.pdf"), read("forms-compressed.pdf"))
  }

  @Test
  fun `documents without a form`() {
    assertEquals(emptyList<FormFieldEntry>(), read("outline.pdf"))
  }

  @Test
  fun `encrypted documents are read from an unprotected copy`() {
    assertThrows(PdfFormatException::class.java) { read("forms-encrypted.pdf") }
  }

  @Test
  fun `a choice of 10,001 options lists 10,000`() {
    val pdf = TestPdfWriter()
    repeat(4) { pdf.reserve() }
    pdf.add(1, "<< /Type /Catalog /Pages 2 0 R /AcroForm << /Fields [4 0 R] >> >>")
    pdf.add(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
    pdf.add(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 300] /Annots [4 0 R] >>")
    val options = (0..10_000).joinToString(" ") { "[(v$it) (Option $it)]" }
    pdf.add(4, "<< /T (choice) /FT /Ch /Opt [$options] /V [(v9999) (v10000)] /Type /Annot /Subtype /Widget /Rect [0 0 100 20] >>")
    val field = PdfFormReader(PdfDocumentReader(ByteArraySource(pdf.build("/Root 1 0 R")))).read().single()
    assertEquals(List(10_000) { "Option $it" }, field.options)
    // The export value of an option that is not listed stands for itself.
    assertEquals(listOf("Option 9999", "v10000"), field.selectedOptions)
  }
}
