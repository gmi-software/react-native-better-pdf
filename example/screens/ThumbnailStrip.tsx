import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { PdfView } from 'react-native-better-pdf';
import type { DocumentSource } from '../documents';
import { colors } from '../ui';

const MAX_THUMBNAILS = 12;

/**
 * One `singlePage` viewer per page. The viewers ignore touches, so the
 * Pressable receives taps and the ScrollView scrolls from on top of them.
 */
export function ThumbnailStrip({
  source,
  pageCount,
  currentPage,
  onSelect,
}: {
  source: DocumentSource;
  pageCount: number;
  currentPage: number;
  onSelect: (page: number) => void;
}) {
  const pages = Array.from(
    { length: Math.min(pageCount, MAX_THUMBNAILS) },
    (_, index) => index,
  );
  return (
    <ScrollView
      horizontal
      testID="thumbnails"
      style={styles.strip}
      contentContainerStyle={styles.content}
      showsHorizontalScrollIndicator={false}
    >
      {pages.map((page) => (
        <Pressable
          key={page}
          testID={`thumb-${page + 1}`}
          onPress={() => onSelect(page)}
          style={[styles.thumb, page === currentPage && styles.current]}
        >
          <PdfView
            source={source}
            singlePage
            initialPage={page}
            style={styles.pdf}
          />
          <View style={styles.badge} pointerEvents="none">
            <Text style={styles.badgeText}>{page + 1}</Text>
          </View>
        </Pressable>
      ))}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  strip: { flexGrow: 0, backgroundColor: colors.card },
  content: { padding: 6, gap: 6 },
  thumb: {
    width: 56,
    height: 76,
    borderRadius: 4,
    borderWidth: 2,
    borderColor: 'transparent',
    overflow: 'hidden',
    backgroundColor: colors.canvas,
  },
  current: { borderColor: colors.accent },
  pdf: { flex: 1 },
  badge: {
    position: 'absolute',
    right: 2,
    bottom: 2,
    paddingHorizontal: 3,
    borderRadius: 3,
    backgroundColor: 'rgba(0,0,0,0.6)',
  },
  badgeText: { color: 'white', fontSize: 9, fontVariant: ['tabular-nums'] },
});
