import { Pressable, StyleSheet, Text } from 'react-native';

export const colors = {
  canvas: '#E4E4E7',
  /** Around dark pages (`colorScheme="dark"`). */
  darkCanvas: '#27272A',
  card: '#FFFFFF',
  text: '#18181B',
  muted: '#71717A',
  accent: '#2563EB',
  danger: '#DC2626',
};

export function Button({
  title,
  onPress,
  onLongPress,
  selected = false,
}: {
  title: string;
  onPress: () => void;
  onLongPress?: () => void;
  selected?: boolean;
}) {
  return (
    <Pressable
      onPress={onPress}
      onLongPress={onLongPress}
      style={({ pressed }) => [
        styles.button,
        selected && styles.selected,
        pressed && styles.pressed,
      ]}
    >
      <Text style={[styles.title, selected && styles.selectedTitle]}>
        {title}
      </Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: { paddingHorizontal: 10, paddingVertical: 7, borderRadius: 8 },
  selected: { backgroundColor: colors.accent },
  pressed: { opacity: 0.5 },
  title: { color: colors.accent, fontSize: 14, fontWeight: '600' },
  selectedTitle: { color: 'white' },
});
