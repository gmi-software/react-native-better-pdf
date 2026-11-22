// Every optional input accepts an explicit `undefined`, as React Native's own
// types do, so values that may be missing can be passed straight through.
import type { ViewProps } from 'react-native';
import {
  PdfCache,
  PdfDocument,
  PdfPageOverlay,
  PdfView,
  type NavigationOptions,
  type PdfBase64Source,
  type PdfCacheMode,
  type PdfContentInset,
  type PdfDownloadOptions,
  type PdfExportOptions,
  type PdfFileSource,
  type PdfImageFormat,
  type PdfOpenOptions,
  type PdfPageOverlayProps,
  type PdfPrintOptions,
  type PdfRenderPageOptions,
  type PdfUriSource,
  type PdfViewProps,
  type PdfViewport,
  type PdfViewRef,
} from 'react-native-better-pdf';

type OptionalKeys<T> = {
  [K in keyof T]-?: object extends Pick<T, K> ? K : never;
}[keyof T];

/** `true` when every optional property of `T` takes `undefined`. */
type AcceptsUndefined<T> =
  {
    [K in OptionalKeys<T>]: undefined;
  } extends Pick<T, OptionalKeys<T>>
    ? true
    : false;

export const checks: [
  AcceptsUndefined<Omit<PdfViewProps, keyof ViewProps>>,
  AcceptsUndefined<Omit<PdfPageOverlayProps, keyof ViewProps>>,
  AcceptsUndefined<PdfUriSource>,
  AcceptsUndefined<PdfFileSource>,
  AcceptsUndefined<PdfBase64Source>,
  AcceptsUndefined<PdfOpenOptions>,
  AcceptsUndefined<PdfDownloadOptions>,
  AcceptsUndefined<PdfRenderPageOptions>,
  AcceptsUndefined<PdfPrintOptions>,
  AcceptsUndefined<PdfExportOptions>,
  AcceptsUndefined<PdfContentInset>,
  AcceptsUndefined<NavigationOptions>,
] = [true, true, true, true, true, true, true, true, true, true, true, true];

declare const storage: { get(key: string): PdfViewport | undefined };
declare const config: {
  uri: string;
  headers: Record<string, string> | undefined;
  cache: PdfCacheMode | undefined;
  maxAgeMs: number | undefined;
  cacheKey: string | undefined;
  password: string | undefined;
  filePath: string;
  base64: string;
  format: PdfImageFormat | undefined;
  quality: number | undefined;
  jobName: string | undefined;
  exportPath: string | undefined;
  animated: boolean | undefined;
  inset: number | undefined;
  scalesWithZoom: boolean | undefined;
  controller: AbortController | undefined;
};
declare const ref: PdfViewRef;

const uriSource: PdfUriSource = {
  uri: config.uri,
  headers: config.headers,
  cache: config.cache,
  maxAgeMs: config.maxAgeMs,
  cacheKey: config.cacheKey,
  password: config.password,
};
const fileSource: PdfFileSource = {
  filePath: config.filePath,
  password: config.password,
};
const base64Source: PdfBase64Source = {
  base64: config.base64,
  password: config.password,
};

// docs/guides/navigation.md, "Reading position".
export function ReadingPosition({ documentId }: { documentId: string }) {
  const saved = storage.get(documentId); // a PdfViewport, or undefined
  return <PdfView source={uriSource} initialViewport={saved} />;
}

export function Viewer() {
  return (
    <PdfView
      source={[uriSource, fileSource, base64Source]}
      password={config.password}
      contentInset={{ top: config.inset, bottom: config.inset }}
      onLoad={undefined}
      ref={undefined}
    >
      <PdfPageOverlay
        page={0}
        x={0}
        y={0}
        width={10}
        height={10}
        scalesWithZoom={config.scalesWithZoom}
      />
    </PdfView>
  );
}

export async function calls() {
  const signal = config.controller?.signal;
  const document = await PdfDocument.open(uriSource, {
    password: config.password,
    signal,
    onProgress: undefined,
  });
  await document.renderPage(0, {
    width: 300,
    format: config.format,
    quality: config.quality,
  });
  await PdfCache.download(uriSource, { signal, onProgress: undefined });
  ref.goToPage(1, { animated: config.animated });
  ref.zoomTo(2, { animated: config.animated });
  await ref.renderPage(0, { width: 300, format: config.format });
  await ref.print({ jobName: config.jobName });
  await ref.exportDocument({ filePath: config.exportPath });
}
