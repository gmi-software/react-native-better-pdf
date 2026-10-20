import { PdfMethodError, PdfOpenError } from 'react-native-better-pdf';

/** A rejection for the event line: an open error's code, a method error's code and message. */
export function describeError(error: unknown): string {
  if (error instanceof PdfOpenError) return error.code;
  if (error instanceof PdfMethodError) return `${error.code}: ${error.message}`;
  return String(error);
}
