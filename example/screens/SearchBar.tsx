import { useState, type RefObject } from 'react';
import { StyleSheet, Text, TextInput, View } from 'react-native';
import type { PdfSearchChangeEvent, PdfViewRef } from 'react-native-better-pdf';
import { Button, colors } from '../ui';

/**
 * Find in document: query field, previous / next, "current / total", and a
 * toggle between the default and custom highlight colours.
 */
export function SearchBar({
  pdf,
  state,
  isCustomColor,
  onToggleColor,
}: {
  pdf: RefObject<PdfViewRef | null>;
  state: PdfSearchChangeEvent | null;
  isCustomColor: boolean;
  onToggleColor: () => void;
}) {
  const [query, setQuery] = useState('');
  return (
    <View style={styles.row}>
      <TextInput
        testID="search-input"
        style={styles.input}
        placeholder="Find in document"
        autoCapitalize="none"
        autoCorrect={false}
        returnKeyType="search"
        value={query}
        onChangeText={(text) => {
          setQuery(text);
          pdf.current?.search(text);
        }}
      />
      <Text testID="search-status" style={styles.status}>
        {formatStatus(state)}
      </Text>
      <Button title="Prev" onPress={() => pdf.current?.searchPrevious()} />
      <Button title="Next" onPress={() => pdf.current?.searchNext()} />
      <Button
        title={isCustomColor ? 'Blue' : 'Yellow'}
        onPress={onToggleColor}
      />
    </View>
  );
}

function formatStatus(state: PdfSearchChangeEvent | null): string {
  if (state == null || state.query === '') return '';
  if (!state.isSupported) return 'n/a';
  const position =
    state.currentMatch === undefined ? 0 : state.currentMatch + 1;
  return `${position} / ${state.matchCount}${state.isComplete ? '' : '…'}`;
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingHorizontal: 8,
    paddingVertical: 4,
    backgroundColor: colors.card,
  },
  input: {
    flex: 1,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.muted,
    borderRadius: 8,
    paddingHorizontal: 10,
    paddingVertical: 6,
    color: colors.text,
  },
  status: {
    minWidth: 56,
    textAlign: 'center',
    fontSize: 13,
    color: colors.muted,
    fontVariant: ['tabular-nums'],
  },
});
