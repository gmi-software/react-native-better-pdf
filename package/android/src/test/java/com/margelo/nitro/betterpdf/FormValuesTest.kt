package com.margelo.nitro.betterpdf

import com.margelo.nitro.betterpdf.PdfMethodException.Code
import com.margelo.nitro.betterpdf.outline.FormFieldEntry
import com.margelo.nitro.betterpdf.outline.FormFieldKind
import com.margelo.nitro.betterpdf.outline.FormWidgetEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FormValuesTest {
  private fun widget(index: Int, type: FormWidget.Type, value: String = "", items: List<FormWidget.Item> = emptyList(), left: Float = 0f) =
    FormWidget(index, type, PageRect(left, 0f, left + 20f, 20f), false, type == FormWidget.Type.TEXT, false, false, -1, 12f, value, items)

  private fun field(kind: FormFieldKind, flags: Int = 0, options: List<String> = emptyList(), widgets: Int = 1, maxLength: Int = 0) =
    FormFieldEntry("f", kind, flags, "", false, options, emptyList(), maxLength, false, List(widgets) { FormWidgetEntry(0, 0f, 0f, 20f, 20f, null) })

  private fun value(text: String? = null, isChecked: Boolean? = null, options: Array<String>? = null) =
    NativePdfFormValue("f", text, isChecked, options)

  @Test
  fun `widgets pair by rectangle within rounding, the rest in order`() {
    val expected = listOf(PageRect(200f, 66f, 500f, 92f), PageRect(10f, 10f, 30f, 30f), PageRect(40f, 40f, 60f, 60f))
    // The renderer's rectangles are whole points; its second widget is the first expected one.
    val actual = listOf(PageRect(99f, 99f, 120f, 120f), PageRect(200f, 66f, 500f, 92f), PageRect(41f, 40f, 60f, 61f))
    assertArrayEquals(intArrayOf(1, 0, 2), FormWidgetMatcher.match(expected, actual))
    assertArrayEquals(intArrayOf(0, -1), FormWidgetMatcher.match(expected.take(2), listOf(PageRect(0f, 0f, 1f, 1f))))
  }

  @Test
  fun `values follow the renderer's widgets`() {
    val radio = field(FormFieldKind.RADIO, options = listOf("basic", "pro"), widgets = 2)
    val on = widget(1, FormWidget.Type.RADIO, "true")
    val off = widget(0, FormWidget.Type.RADIO, "false")
    assertEquals(listOf("pro"), radio.withRendererValues(listOf(off, on)).selectedOptions)
    assertEquals(emptyList<String>(), radio.withRendererValues(listOf(off, off)).selectedOptions)
    // A button whose page was not read keeps the file's value.
    assertEquals(radio, radio.withRendererValues(listOf(off, null)))
    assertEquals("a\nb", field(FormFieldKind.TEXT).withRendererValues(listOf(widget(0, FormWidget.Type.TEXT, "a\r\nb"))).text)
    val list = widget(0, FormWidget.Type.LIST_BOX, items = listOf(FormWidget.Item("Red", false), FormWidget.Item("Blue", true)))
    assertEquals(listOf("Blue"), field(FormFieldKind.LIST_BOX).withRendererValues(listOf(list)).selectedOptions)
  }

  @Test
  fun `edits for each kind of value`() {
    val text = PlacedWidget(3, widget(2, FormWidget.Type.TEXT))
    assertEquals(listOf(FormEdit.SetText(3, 2, "Anna")), field(FormFieldKind.TEXT).plannedEdits(value(text = "Anna"), listOf(text)))
    // A checkbox is clicked only when it changes.
    val unchecked = PlacedWidget(0, widget(0, FormWidget.Type.CHECKBOX, "false"))
    assertEquals(listOf(FormEdit.Click(0, 0, 10, 10)), field(FormFieldKind.CHECKBOX).plannedEdits(value(isChecked = true), listOf(unchecked)))
    assertEquals(emptyList<FormEdit>(), field(FormFieldKind.CHECKBOX).plannedEdits(value(isChecked = false), listOf(unchecked)))
    // A radio button: the option's own widget.
    val buttons = listOf(PlacedWidget(0, widget(0, FormWidget.Type.RADIO, "true")), PlacedWidget(0, widget(1, FormWidget.Type.RADIO, "false", left = 100f)))
    val radio = field(FormFieldKind.RADIO, options = listOf("basic", "pro"), widgets = 2)
    assertEquals(listOf(FormEdit.Click(0, 1, 110, 10)), radio.plannedEdits(value(text = "pro"), buttons))
    val combo = PlacedWidget(0, widget(4, FormWidget.Type.COMBO_BOX, items = listOf(FormWidget.Item("Poland", true), FormWidget.Item("France", false))))
    val edit = field(FormFieldKind.COMBO_BOX).plannedEdits(value(text = "France"), listOf(combo)).single() as FormEdit.SetIndices
    assertArrayEquals(intArrayOf(1), edit.indices)
  }

  @Test
  fun `values a field does not take are rejected before any edit`() {
    fun rejection(plan: () -> Unit) = assertThrows(PdfMethodException::class.java) { plan() }.code
    val text = listOf(PlacedWidget(0, widget(0, FormWidget.Type.TEXT)))
    assertEquals(
      Code.NOT_PERMITTED,
      rejection { field(FormFieldKind.TEXT, flags = FormFieldEntry.FLAG_READ_ONLY).plannedEdits(value(text = "x"), text) },
    )
    assertEquals(Code.INVALID_ARGUMENT, rejection { field(FormFieldKind.TEXT).plannedEdits(value(isChecked = true), text) })
    assertEquals(Code.INVALID_ARGUMENT, rejection { field(FormFieldKind.TEXT, maxLength = 2).plannedEdits(value(text = "abc"), text) })
    val buttons = listOf(PlacedWidget(0, widget(0, FormWidget.Type.RADIO, "true")))
    assertEquals(
      Code.INVALID_ARGUMENT,
      rejection { field(FormFieldKind.RADIO, options = listOf("basic")).plannedEdits(value(text = "gold"), buttons) },
    )
    // PDFium keeps a selected button of a group that cannot be turned off.
    assertEquals(
      Code.NOT_SUPPORTED,
      rejection { field(FormFieldKind.RADIO, flags = FormFieldEntry.FLAG_NO_TOGGLE_TO_OFF, options = listOf("basic")).plannedEdits(value(), buttons) },
    )
    assertEquals(Code.INVALID_ARGUMENT, rejection { field(FormFieldKind.SIGNATURE).plannedEdits(value(text = "x"), text) })
  }
}
