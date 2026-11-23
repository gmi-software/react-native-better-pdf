package com.margelo.nitro.betterpdf.outline

/** Line breaks as `\n`: files and renderers store `\r\n` or `\r` too. */
fun String.withLineFeeds(): String = replace("\r\n", "\n").replace('\r', '\n')

/** The kind of a form field: its `/FT` and, for buttons and choices, its flags. */
enum class FormFieldKind { TEXT, CHECKBOX, RADIO, COMBO_BOX, LIST_BOX, SIGNATURE, PUSH_BUTTON }

/**
 * Where a widget of a form field is shown: zero-based page and a rectangle
 * in PDF points from the top-left corner of the page as displayed (crop
 * box, rotation applied), as `onPageTap` reports points.
 */
data class FormWidgetEntry(
  val page: Int,
  val left: Float,
  val top: Float,
  val width: Float,
  val height: Float,
  /** A checkbox or radio button's "on" state (its option), else null. */
  val onState: String?,
)

/** A form field as the file stores it (ISO 32000-1 §12.7). */
data class FormFieldEntry(
  /** Fully qualified: parent names first, joined by periods. */
  val name: String,
  val kind: FormFieldKind,
  /** `/Ff`, inherited. */
  val flags: Int,
  /** Text field: its value; combo box: the chosen option or typed text. */
  val text: String,
  val isChecked: Boolean,
  /** Radio: each widget's option; choices: the options' display text. */
  val options: List<String>,
  val selectedOptions: List<String>,
  /** 0 when unlimited. */
  val maxLength: Int,
  val isSigned: Boolean,
  val widgets: List<FormWidgetEntry>,
) {
  val isReadOnly: Boolean get() = flags and FLAG_READ_ONLY != 0
  val isRequired: Boolean get() = flags and FLAG_REQUIRED != 0

  companion object {
    const val FLAG_READ_ONLY = 1
    const val FLAG_REQUIRED = 1 shl 1
    const val FLAG_MULTILINE = 1 shl 12
    const val FLAG_NO_TOGGLE_TO_OFF = 1 shl 14
    const val FLAG_RADIO = 1 shl 15
    const val FLAG_PUSH_BUTTON = 1 shl 16
    const val FLAG_COMBO = 1 shl 17
    const val FLAG_EDIT = 1 shl 18
    const val FLAG_MULTI_SELECT = 1 shl 21
  }
}

/**
 * Reads the document's interactive form: every terminal field of the
 * AcroForm's `/Fields` tree, in order, with its inherited type, flags and
 * value and its widgets' places. Pages are found through their `/Annots`
 * (a widget's `/P` is optional). Cycles are broken; the walk is limited to
 * [MAX_DEPTH] levels, [MAX_FIELDS] fields, [MAX_OPTIONS] options and
 * selected values of a field, and what a read may keep ([ScanBudget]).
 */
class PdfFormReader(private val document: PdfDocumentReader) {
  private val catalog = document.resolve(document.trailer["Root"]) as? PdfObject.Dictionary
    ?: throw PdfFormatException("No document catalog")

  /** `[]` without a form. */
  fun read(): List<FormFieldEntry> {
    val form = document.resolve(catalog["AcroForm"]) as? PdfObject.Dictionary ?: return emptyList()
    val fields = document.resolve(form["Fields"]) as? PdfObject.Array ?: return emptyList()
    val tree = PdfPageTree(document, catalog)
    val pages = tree.pages
    val pageOfAnnotation = HashMap<Int, Int>()
    pages.forEachIndexed { index, page ->
      val annotations = page.annotations
      document.keep(ANNOTATION_BYTES * annotations.size)
      annotations.forEach { pageOfAnnotation.putIfAbsent(it, index) }
    }
    val pageIndexByObject = tree.indexByObject

    val result = ArrayList<FormFieldEntry>()
    val visited = HashSet<Int>()
    fun visit(node: PdfObject, parentName: String?, inherited: Inherited, depth: Int) {
      if (depth > MAX_DEPTH || result.size >= MAX_FIELDS) return
      val reference = node as? PdfObject.Reference
      if (reference != null && !visited.add(reference.number)) return
      document.keep(NODE_BYTES)
      // A field's objects are let go once it and its children are listed.
      document.releasing {
        val dictionary = document.resolve(node) as? PdfObject.Dictionary ?: return
        val partial = (document.resolve(dictionary["T"]) as? PdfObject.Str)?.let(::textOf)
        val name = when {
          partial == null -> parentName
          parentName == null -> partial
          else -> "$parentName.$partial".also { document.hold(heldBytes(it)) }
        } ?: return
        val own = inherited.with(dictionary)
        val kids = (document.resolve(dictionary["Kids"]) as? PdfObject.Array)?.items.orEmpty()
        val (childFields, widgetKids) = kids.partition { kid ->
          document.releasing { (document.resolve(kid) as? PdfObject.Dictionary)?.get("T") != null }
        }
        childFields.forEach { visit(it, name, own, depth + 1) }
        if (childFields.isNotEmpty() && widgetKids.isEmpty()) return
        // A field without kids is its own widget.
        val widgetNodes = if (kids.isEmpty()) listOf(node) else widgetKids
        val widgets = widgetNodes.mapNotNull { widgetNode ->
          document.releasing {
            val widget = (if (widgetNode === node) dictionary else document.resolve(widgetNode) as? PdfObject.Dictionary)
              ?: return@releasing null
            val number = (widgetNode as? PdfObject.Reference)?.number
            val pageIndex = number?.let(pageOfAnnotation::get)
              ?: (widget["P"] as? PdfObject.Reference)?.number?.let(pageIndexByObject::get)
              ?: return@releasing null
            widgetEntry(widget, pageIndex, pages[pageIndex])
          }?.also { document.hold(heldBytes(it)) }
        }
        field(name, own, widgets, widgetNodes)?.let {
          document.keep(heldBytes(it))
          result.add(it)
        }
      }
    }
    fields.items.forEach { visit(it, null, Inherited(), 0) }
    return result
  }

  /** Attributes a field takes from its ancestors (§12.7.3.1). */
  private data class Inherited(
    val type: String? = null,
    val flags: Int = 0,
    val value: PdfObject? = null,
    val options: PdfObject? = null,
    val maxLength: Int = 0,
  ) {
    fun with(dictionary: PdfObject.Dictionary) = copy(
      type = (dictionary["FT"] as? PdfObject.Name)?.value ?: type,
      flags = (dictionary["Ff"] as? PdfObject.Number)?.intValue ?: flags,
      value = dictionary["V"] ?: value,
      options = dictionary["Opt"] ?: options,
      maxLength = (dictionary["MaxLen"] as? PdfObject.Number)?.intValue ?: maxLength,
    )
  }

  private fun field(
    name: String,
    attributes: Inherited,
    widgets: List<FormWidgetEntry>,
    widgetNodes: List<PdfObject>,
  ): FormFieldEntry? {
    val flags = attributes.flags
    val kind = when (attributes.type) {
      "Tx" -> FormFieldKind.TEXT
      "Btn" -> when {
        flags and FormFieldEntry.FLAG_PUSH_BUTTON != 0 -> FormFieldKind.PUSH_BUTTON
        flags and FormFieldEntry.FLAG_RADIO != 0 -> FormFieldKind.RADIO
        else -> FormFieldKind.CHECKBOX
      }
      "Ch" -> if (flags and FormFieldEntry.FLAG_COMBO != 0) FormFieldKind.COMBO_BOX else FormFieldKind.LIST_BOX
      "Sig" -> FormFieldKind.SIGNATURE
      else -> return null
    }
    val value = document.resolve(attributes.value)
    var text = ""
    var isChecked = false
    var options = emptyList<String>()
    var selected = emptyList<String>()
    when (kind) {
      FormFieldKind.TEXT -> text = (value as? PdfObject.Str)?.let(::textOf)?.withLineFeeds().orEmpty()
      FormFieldKind.CHECKBOX -> {
        val state = (value as? PdfObject.Name)?.value ?: appearanceState(widgetNodes.firstOrNull())
        isChecked = state != null && state != OFF
      }
      FormFieldKind.RADIO -> {
        options = widgets.map { it.onState.orEmpty() }
        val state = (value as? PdfObject.Name)?.value
          ?: widgetNodes.firstNotNullOfOrNull { node -> appearanceState(node)?.takeIf { it != OFF } }
        selected = listOfNotNull(state?.takeIf { it != OFF && it in options })
      }
      FormFieldKind.COMBO_BOX, FormFieldKind.LIST_BOX -> {
        val choices = choicesOf(attributes.options)
        options = choices.map { it.second }
        // /V holds export values; report what the reader sees.
        val values = when (value) {
          is PdfObject.Str -> listOf(textOf(value))
          is PdfObject.Array -> value.items.take(MAX_OPTIONS).mapNotNull { (document.resolve(it) as? PdfObject.Str)?.let(::textOf) }
          else -> emptyList()
        }
        val displayed = HashMap<String, String>()
        for ((exported, display) in choices) displayed.putIfAbsent(exported, display)
        selected = values.map { exported -> displayed[exported] ?: exported }
        if (kind == FormFieldKind.COMBO_BOX) text = selected.firstOrNull().orEmpty()
      }
      FormFieldKind.SIGNATURE, FormFieldKind.PUSH_BUTTON -> Unit
    }
    return FormFieldEntry(
      name = name,
      kind = kind,
      flags = flags,
      text = text,
      isChecked = isChecked,
      options = options,
      selectedOptions = selected,
      maxLength = attributes.maxLength.coerceAtLeast(0),
      isSigned = kind == FormFieldKind.SIGNATURE && value != null && value != PdfObject.Null,
      widgets = widgets,
    )
  }

  /** [value] as text, counted as work and as held ([ScanBudget]): many fields can share one long string, each with a copy. */
  private fun textOf(value: PdfObject.Str): String {
    document.spend(value.bytes.size)
    return value.toText().also { document.hold(heldBytes(it)) }
  }

  private fun heldBytes(text: String): Long = HeldBytes.TEXT + 2L * text.length

  private fun heldBytes(widget: FormWidgetEntry): Long = WIDGET_BYTES + heldBytes(widget.onState.orEmpty())

  private fun heldBytes(field: FormFieldEntry): Long =
    FIELD_BYTES + heldBytes(field.name) + heldBytes(field.text) +
      (field.options + field.selectedOptions).sumOf { heldBytes(it) } +
      field.widgets.sumOf { heldBytes(it) }

  /** `/Opt`: (export value, display text) pairs, up to [MAX_OPTIONS]; plain entries are both. */
  private fun choicesOf(options: PdfObject?): List<Pair<String, String>> {
    val items = (document.resolve(options) as? PdfObject.Array)?.items ?: return emptyList()
    return items.take(MAX_OPTIONS).mapNotNull { item ->
      when (val entry = document.resolve(item)) {
        is PdfObject.Str -> textOf(entry).let { it to it }
        is PdfObject.Array -> {
          val export = (document.resolve(entry.items.getOrNull(0)) as? PdfObject.Str)?.let(::textOf)
          val display = (document.resolve(entry.items.getOrNull(1)) as? PdfObject.Str)?.let(::textOf)
          if (export != null) export to (display ?: export) else null
        }
        else -> null
      }
    }
  }

  private fun appearanceState(widgetNode: PdfObject?): String? =
    document.releasing { ((document.resolve(widgetNode) as? PdfObject.Dictionary)?.get("AS") as? PdfObject.Name)?.value }

  private fun widgetEntry(widget: PdfObject.Dictionary, pageIndex: Int, page: PdfPageGeometry): FormWidgetEntry? {
    val rect = PdfPageTree.rectangleOf(document, widget["Rect"]) ?: return null
    val normal = ((document.resolve(widget["AP"]) as? PdfObject.Dictionary)?.get("N"))
      .let { document.resolve(it) as? PdfObject.Dictionary }
    val onState = normal?.entries?.keys?.firstOrNull { it != OFF }
    val (left, top) = page.topLeft(rect[0], rect[3])
    val (right, bottom) = page.topLeft(rect[2], rect[1])
    return FormWidgetEntry(
      page = pageIndex,
      left = minOf(left, right),
      top = minOf(top, bottom),
      width = kotlin.math.abs(right - left),
      height = kotlin.math.abs(bottom - top),
      onState = onState,
    )
  }

  private companion object {
    const val OFF = "Off"
    const val MAX_DEPTH = 32
    const val MAX_FIELDS = 10_000
    const val MAX_OPTIONS = 10_000

    /**
     * What a read keeps ([ScanBudget.keep]) for a node it has visited, a
     * field besides its texts, a widget, and an annotation's page.
     */
    const val NODE_BYTES = 64L
    const val FIELD_BYTES = 160L
    const val WIDGET_BYTES = 64L
    const val ANNOTATION_BYTES = 64L
  }
}
