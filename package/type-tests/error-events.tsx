// `onError`'s argument narrows `PdfError`, so handlers and state typed with
// the wider `PdfError` accept it.
import { useState } from 'react';
import {
  PdfView,
  type PdfError,
  type PdfLoadError,
  type PdfPageError,
} from 'react-native-better-pdf';

declare const loadError: PdfLoadError;
declare const pageError: PdfPageError;

export const errors: PdfError[] = [loadError, pageError];

function logError(error: PdfError) {
  console.log(error.code, error.page, error.sourceIndex);
}

export function Viewer() {
  const [, setError] = useState<PdfError | null>(null);
  return (
    <>
      <PdfView
        source={{ uri: 'https://example.com/a.pdf' }}
        onError={setError}
      />
      <PdfView
        source={{ uri: 'https://example.com/b.pdf' }}
        onError={logError}
      />
    </>
  );
}
