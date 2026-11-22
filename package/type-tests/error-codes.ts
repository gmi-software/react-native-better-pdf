// Exhaustive switches over the codes each call can reject with: a code
// missing from a union fails the `never` check, a case the union cannot hold
// fails as not comparable.
import {
  PdfCache,
  PdfDocument,
  PdfDownloadError,
  PdfOpenError,
  type PdfDownloadErrorCode,
  type PdfErrorCode,
  type PdfOpenErrorCode,
  type PdfSource,
  type PdfUriSource,
} from 'react-native-better-pdf';

function assertNever(value: never): never {
  throw new Error(`Unexpected ${String(value)}`);
}

export function describeOpenError(code: PdfOpenErrorCode): string {
  switch (code) {
    case 'invalid-source':
      return 'Check the source';
    case 'file-not-found':
      return 'The file is gone';
    case 'network-error':
      return 'Offline';
    case 'http-error':
      return 'The server refused';
    case 'invalid-pdf':
      return 'Not a PDF';
    case 'password-required':
    case 'invalid-password':
      return 'Ask for the password';
    case 'unsupported-pdf':
      return 'Cannot open this PDF on this device';
    case 'out-of-memory':
      return 'Too large';
    default:
      return assertNever(code);
  }
}

export function describeDownloadError(code: PdfDownloadErrorCode): string {
  switch (code) {
    case 'invalid-source':
      return 'Check the source';
    case 'network-error':
      return 'Offline';
    case 'http-error':
      return 'The server refused';
    case 'invalid-pdf':
      return 'Not a PDF';
    default:
      return assertNever(code);
  }
}

// Both are codes of the classes' wider `code` property.
export const openCode: PdfErrorCode = 'invalid-source' as PdfOpenErrorCode;
export const downloadCode: PdfErrorCode =
  'invalid-source' as PdfDownloadErrorCode;

export async function open(source: PdfSource) {
  try {
    return await PdfDocument.open(source);
  } catch (error) {
    if (error instanceof PdfOpenError) {
      return describeOpenError(error.code as PdfOpenErrorCode);
    }
    throw error;
  }
}

export async function download(source: PdfUriSource) {
  try {
    return await PdfCache.download(source);
  } catch (error) {
    if (error instanceof PdfDownloadError) {
      return describeDownloadError(error.code as PdfDownloadErrorCode);
    }
    throw error;
  }
}
