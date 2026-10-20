package com.margelo.nitro.betterpdf

import com.margelo.nitro.betterpdf.outline.PdfFormatException

// The reads as the view and `PdfDocument` hand them to JS. Blocking.

internal fun DocumentReads.readOutlineEntries(): Array<PdfOutlineEntry> =
  try {
    readOutline().map { it.toPdfOutlineEntry() }.toTypedArray()
  } catch (error: PdfFormatException) {
    throw error.toMethodException("The outline could not be read: ${error.message}")
  }

internal fun DocumentReads.readMetadata(): PdfDocumentMetadata =
  try {
    readInfo().toPdfDocumentMetadata()
  } catch (error: PdfFormatException) {
    throw error.toMethodException("The metadata could not be read: ${error.message}")
  }

internal fun DocumentReads.readPdfPageSizes(pageCount: Int): Array<PdfPageSize> =
  readPageSizes(pageCount)
    .map { PdfPageSize(it.widthPoints.toDouble(), it.heightPoints.toDouble()) }
    .toTypedArray()
