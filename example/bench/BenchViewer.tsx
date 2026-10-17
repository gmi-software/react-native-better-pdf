import { useImperativeHandle, useRef, useState, type Ref } from 'react';
import { StyleSheet } from 'react-native';
import Pdf, { type PdfRef } from 'react-native-pdf';
import {
  PdfView,
  type PdfViewRef,
  type PdfViewStats,
} from 'react-native-better-pdf';

export type Library = 'better' | 'react-native-pdf';

/** Both viewers' `maxScale`. */
export const MAX_SCALE = 4;

export interface BenchViewerHandle {
  goToPage(page: number): void;
  zoomTo(scale: number): void;
  /** Better PDF only; `null` for react-native-pdf. */
  getStats(): PdfViewStats | null;
}

interface Props {
  library: Library;
  uri: string;
  /** 1 unless a zoomed-out variant runs. */
  minScale: number;
  onLoad: (pageCount: number) => void;
  /** Zero-based. */
  onPageChange: (page: number) => void;
  onError: (message: string) => void;
  ref: Ref<BenchViewerHandle>;
}

/**
 * The two viewers behind one interface, configured as equivalently as their
 * APIs allow: vertical continuous scrolling, fit width, 1x-4x zoom (from
 * `minScale` in a zoomed-out variant), 8pt gaps.
 */
export function BenchViewer({
  library,
  uri,
  minScale,
  onLoad,
  onPageChange,
  onError,
  ref,
}: Props) {
  const better = useRef<PdfViewRef>(null);
  const legacy = useRef<PdfRef>(null);
  // react-native-pdf has no imperative zoom; its `scale` prop is the only way.
  const [legacyScale, setLegacyScale] = useState(1);

  useImperativeHandle(ref, () => ({
    goToPage(page) {
      if (library === 'better')
        better.current?.goToPage(page, { animated: false });
      else legacy.current?.setPage(page + 1);
    },
    zoomTo(scale) {
      if (library === 'better')
        better.current?.zoomTo(scale, { animated: false });
      else setLegacyScale(scale);
    },
    getStats() {
      return library === 'better' ? (better.current?.getStats() ?? null) : null;
    },
  }));

  if (library === 'better') {
    return (
      <PdfView
        ref={better}
        style={styles.fill}
        source={{ uri }}
        minScale={minScale}
        maxScale={MAX_SCALE}
        pageSpacing={8}
        onLoad={(event) => onLoad(event.pageCount)}
        onPageChange={(event) => onPageChange(event.page)}
        onError={(error) => onError(`${error.code}: ${error.message}`)}
      />
    );
  }
  return (
    <Pdf
      ref={legacy}
      style={styles.fill}
      source={{ uri, cache: false }}
      // With the default (true), react-native-blob-util 0.25 throws on
      // Android ("Use of own trust manager but none defined") and every
      // remote load fails. Better PDF always validates certificates.
      trustAllCerts={false}
      minScale={minScale}
      maxScale={MAX_SCALE}
      scale={legacyScale}
      spacing={8}
      fitPolicy={0}
      enablePaging={false}
      onLoadComplete={(pageCount) => onLoad(pageCount)}
      onPageChanged={(page) => onPageChange(page - 1)}
      onError={(error) => onError(String(error))}
    />
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1, backgroundColor: '#E4E4E7' },
});
