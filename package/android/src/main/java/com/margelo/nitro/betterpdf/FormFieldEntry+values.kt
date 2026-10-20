package com.margelo.nitro.betterpdf

import com.margelo.nitro.betterpdf.PdfMethodException.Code
import com.margelo.nitro.betterpdf.outline.FormFieldEntry
import com.margelo.nitro.betterpdf.outline.FormFieldKind
import com.margelo.nitro.betterpdf.outline.withLineFeeds

/** A renderer widget of a field and the page it is on. */
internal data class PlacedWidget(val page: Int, val widget: FormWidget)

/**
 * The field with its value as the renderer has it now. [widgets] holds the
 * renderer widget of each of the field's widgets, null where its page was
 * not read; values stay as the file has them until it is.
 */
internal fun FormFieldEntry.withRendererValues(widgets: List<FormWidget?>): FormFieldEntry {
  val known = widgets.filterNotNull()
  val first = known.firstOrNull() ?: return this
  return when (kind) {
    // PDFium reports line breaks as \r\n.
    FormFieldKind.TEXT -> copy(text = first.textValue.withLineFeeds())
    FormFieldKind.CHECKBOX -> copy(isChecked = first.isOn)
    FormFieldKind.RADIO -> {
      val on = widgets.indexOfFirst { it?.isOn == true }
      when {
        on >= 0 -> copy(selectedOptions = listOfNotNull(options.getOrNull(on)))
        known.size == widgets.size -> copy(selectedOptions = emptyList())
        else -> this
      }
    }
    FormFieldKind.COMBO_BOX -> copy(text = first.textValue, selectedOptions = listOfNotNull(first.textValue.ifEmpty { null }))
    FormFieldKind.LIST_BOX -> copy(selectedOptions = first.items.filter { it.isSelected }.map { it.label })
    FormFieldKind.SIGNATURE, FormFieldKind.PUSH_BUTTON -> this
  }
}

/**
 * The renderer edits that give the field [value], from its widgets as the
 * renderer has them ([widgets] as in [withRendererValues], with pages).
 *
 * @throws PdfMethodException for a read-only field or a value its type does
 *   not take.
 */
internal fun FormFieldEntry.plannedEdits(value: NativePdfFormValue, widgets: List<PlacedWidget?>): List<FormEdit> {
  fun reject(reason: String, code: PdfMethodException.Code = Code.INVALID_ARGUMENT): Nothing =
    throw PdfMethodException(code, "The form field \"$name\" $reason")
  if (isReadOnly) reject("is read-only", Code.NOT_PERMITTED)
  if (kind == FormFieldKind.SIGNATURE || kind == FormFieldKind.PUSH_BUTTON) reject("has no value to set")
  val first = widgets.firstOrNull { it != null } ?: reject("could not be found on its page", Code.INVALID_PDF)
  val text = value.text
  val isChecked = value.isChecked
  val options = value.options
  return when (kind) {
    FormFieldKind.TEXT -> {
      if (isChecked != null || options != null) reject("(text) takes a string or null")
      val newText = text.orEmpty()
      if (maxLength > 0 && newText.length > maxLength) reject("takes at most $maxLength characters")
      listOf(FormEdit.SetText(first.page, first.widget.index, newText))
    }
    FormFieldKind.CHECKBOX -> {
      if (text != null || options != null) reject("(checkbox) takes a boolean or null")
      if (first.widget.isOn == (isChecked == true)) emptyList() else listOf(first.click())
    }
    FormFieldKind.RADIO -> {
      if (isChecked != null || options != null) reject("(radio) takes an option or null")
      if (text != null) {
        val index = this.options.indexOf(text)
        if (index < 0) reject("has no option \"$text\"")
        val target = widgets.getOrNull(index) ?: reject("could not be found on its page", Code.INVALID_PDF)
        if (target.widget.isOn) emptyList() else listOf(target.click())
      } else {
        val on = widgets.firstOrNull { it?.widget?.isOn == true } ?: return emptyList()
        if (flags and FormFieldEntry.FLAG_NO_TOGGLE_TO_OFF != 0) reject("(radio) cannot be cleared on Android", Code.NOT_SUPPORTED)
        listOf(on.click())
      }
    }
    FormFieldKind.COMBO_BOX -> {
      if (isChecked != null || options != null) reject("(combo-box) takes an option or null")
      val labels = first.widget.items.map { it.label }
      val index = text?.let(labels::indexOf) ?: -1
      when {
        text.isNullOrEmpty() && first.widget.isEditableText -> listOf(FormEdit.SetText(first.page, first.widget.index, ""))
        text.isNullOrEmpty() -> listOf(FormEdit.SetIndices(first.page, first.widget.index, IntArray(0)))
        index >= 0 -> listOf(FormEdit.SetIndices(first.page, first.widget.index, intArrayOf(index)))
        first.widget.isEditableText -> listOf(FormEdit.SetText(first.page, first.widget.index, text))
        else -> reject("has no option \"$text\"")
      }
    }
    FormFieldKind.LIST_BOX -> {
      if (isChecked != null) reject("(list-box) takes options or null")
      val selection = options?.toList() ?: listOfNotNull(text)
      val labels = first.widget.items.map { it.label }
      val indices = selection.map { option ->
        labels.indexOf(option).takeIf { it >= 0 } ?: reject("has no option \"$option\"")
      }
      if (indices.size > 1 && !first.widget.isMultiSelect) reject("takes one option")
      listOf(FormEdit.SetIndices(first.page, first.widget.index, indices.toIntArray()))
    }
    FormFieldKind.SIGNATURE, FormFieldKind.PUSH_BUTTON -> emptyList()
  }
}

/** A tap in the middle of the widget. */
internal fun PlacedWidget.click(): FormEdit.Click =
  FormEdit.Click(page, widget.index, ((widget.rect.left + widget.rect.right) / 2).toInt(), ((widget.rect.top + widget.rect.bottom) / 2).toInt())
