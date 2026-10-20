package com.margelo.nitro.betterpdf.outline

/** The file is not a PDF this reader can parse (or exceeds its safety limits). */
open class PdfFormatException(message: String) : Exception(message)

/** The file is encrypted, which this reader does not decrypt. */
class EncryptedPdfException : PdfFormatException("Encrypted documents are not supported")
