/** How `PdfViewRef.print` prints the loaded document. */
export interface PdfPrintOptions {
  /**
   * Name of the print job, shown in the print queue and used as the file
   * name when the user saves as PDF. Default (also for a blank name): the
   * source's file name, or `'Document'` when it has none (e.g. a
   * `content://` source).
   */
  jobName?: string | undefined;
}
