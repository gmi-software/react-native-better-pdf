package com.margelo.nitro.betterpdf.outline

/**
 * What parsed objects take on the heap, in bytes, as [ScanBudget.hold]
 * counts them: estimates for a 64-bit runtime with 32-bit references,
 * rounded up, so the budget's limit is a limit in bytes.
 */
internal object HeldBytes {
  /** A number, a reference, a boolean. */
  const val SCALAR = 24

  /** A stream's place, besides its dictionary. */
  const val STREAM = 32

  /** A string, and each of its bytes. */
  const val STRING = 40

  /** A name or a dictionary's key, and each of its characters. */
  const val NAME = 64

  /** An array, and [ITEM] for each of its items: the list grows by half. */
  const val ARRAY = 96
  const val ITEM = 8

  /** A dictionary, and [ENTRY] for each of its entries: the table doubles. */
  const val DICTIONARY = 152
  const val ENTRY = 56

  /** A string a reader decodes, and two bytes for each of its characters. */
  const val TEXT = 48
}

/** What this object and all it contains take on the heap ([HeldBytes]). */
internal fun PdfObject.heldBytes(): Long = when (this) {
  PdfObject.Null -> 0
  is PdfObject.Bool, is PdfObject.Number, is PdfObject.Reference -> HeldBytes.SCALAR.toLong()
  is PdfObject.Name -> HeldBytes.NAME.toLong() + value.length
  is PdfObject.Str -> HeldBytes.STRING.toLong() + bytes.size
  is PdfObject.Array -> HeldBytes.ARRAY + items.sumOf { HeldBytes.ITEM + it.heldBytes() }
  is PdfObject.Dictionary ->
    HeldBytes.DICTIONARY + entries.entries.sumOf { (key, value) -> HeldBytes.ENTRY + HeldBytes.NAME + key.length + value.heldBytes() }
  is PdfObject.Stream -> HeldBytes.STREAM + dictionary.heldBytes()
}
