import { Directory, File, Paths } from 'expo-file-system';
import { useState, type RefObject } from 'react';
import { StyleSheet, View } from 'react-native';
import type { PdfFormField, PdfViewRef } from 'react-native-better-pdf';
import { Button } from '../ui';

/**
 * Form filling: "Fill" sets values from code, "Fields" lists the current
 * values on the event line, "Bad" tries a read-only field, an export over
 * the displayed file, an export into a directory and, for a downloaded
 * document, an export beside its copy in the cache (all rejected; the
 * directory's file must survive), "Touch" turns filling by touch on and
 * off, "Export" writes the filled-in document and "Open" shows that copy.
 */
export function FormBar({
  pdf,
  sourcePath,
  isDownloaded,
  isFillingEnabled,
  onToggleFilling,
  onEvent,
  onOpen,
}: {
  /** Read on press: it is still unset while the viewer first renders. */
  pdf: RefObject<PdfViewRef | null>;
  /** The displayed file (`onLoad`'s `filePath`). */
  sourcePath: string | undefined;
  /** The source is a URL: `sourcePath` is the cache's copy. */
  isDownloaded: boolean;
  isFillingEnabled: boolean;
  onToggleFilling: () => void;
  onEvent: (text: string) => void;
  onOpen: (filePath: string) => void;
}) {
  const [exported, setExported] = useState<string | null>(null);
  const fail = (what: string) => (error: unknown) =>
    // Android's rejections carry the Java stack trace: first line only.
    onEvent(`${what}: ${String(error).split('\n')[0]}`);
  return (
    <View style={styles.row}>
      <Button
        title="Fill"
        onPress={() => {
          pdf.current
            ?.setFormFieldValues({
              name: 'Ewa',
              notes: 'First line\nSecond line',
              agree: true,
              plan: 'pro',
              country: 'France',
              colours: ['Blue'],
              'address.city': 'Kraków',
            })
            .then(() => onEvent('fill: done'))
            .catch(fail('fill'));
        }}
      />
      <Button
        title="Fields"
        onPress={() => {
          pdf.current
            ?.getFormFields()
            .then((fields) =>
              onEvent(
                `fields ${fields.length}: ${fields.map(describeField).join(' ')}`,
              ),
            )
            .catch(fail('fields'));
        }}
      />
      <Button
        title="Bad"
        onPress={() => {
          const message = (error: unknown) => String(error).split('\n')[0];
          const target = directoryWithFile();
          // First, while the form can still be as loaded (iOS then copies
          // the file instead of writing it anew).
          const intoDirectory = pdf.current
            ?.exportDocument({ filePath: target.path })
            .then(() => 'exported into a directory', message);
          // Where another document's copy would be.
          const besideCopy =
            isDownloaded && sourcePath != null
              ? [
                  pdf.current
                    ?.exportDocument({
                      filePath: sourcePath.replace(/[^/]*$/, 'beside.pdf'),
                    })
                    .then(() => 'exported beside the copy', message),
                ]
              : [];
          void Promise.all([
            pdf.current
              ?.setFormFieldValues({ name: 'Never', id: 'B-1' })
              .then(() => 'accepted', message),
            pdf.current
              ?.exportDocument({ filePath: sourcePath })
              .then(() => 'exported over the source', message),
            intoDirectory,
            ...besideCopy,
          ]).then((results) =>
            onEvent(
              `bad: ${results.join(' | ')}; keep.txt ${target.file.exists ? 'kept' : 'deleted'}`,
            ),
          );
        }}
      />
      <Button
        title={isFillingEnabled ? 'Touch on' : 'Touch off'}
        onPress={onToggleFilling}
      />
      <Button
        title="Export"
        onPress={() => {
          pdf.current
            ?.exportDocument()
            .then((result) => {
              setExported(result.filePath);
              onEvent(`export: ${result.filePath.split('/').pop()}`);
            })
            .catch(fail('export'));
        }}
      />
      <Button
        title="Open"
        onPress={() => {
          if (exported != null) onOpen(exported);
        }}
      />
    </View>
  );
}

/**
 * A directory holding a file, as an export target that must be rejected:
 * replacing it would delete the file. The path has no trailing slash, so
 * only the directory itself tells it apart from a file.
 */
function directoryWithFile(): { path: string; file: File } {
  const directory = new Directory(Paths.cache, 'export-target');
  if (!directory.exists) directory.create();
  const file = new File(directory, 'keep.txt');
  if (!file.exists) {
    file.create();
    file.write('keep');
  }
  return { path: directory.uri.replace(/\/$/, ''), file };
}

/** `name=value`, for the event line. */
export function describeField(field: PdfFormField): string {
  const value = (() => {
    switch (field.type) {
      case 'text':
        return field.value.replace(/\n/g, '/');
      case 'checkbox':
        return field.isChecked ? 'on' : 'off';
      case 'radio':
      case 'combo-box':
        return field.value ?? '-';
      case 'list-box':
        return field.values.join('+') || '-';
      case 'signature':
        return field.isSigned ? 'signed' : 'unsigned';
      case 'push-button':
        return 'button';
    }
  })();
  return `${field.name}=${value}`;
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    paddingHorizontal: 8,
  },
});
