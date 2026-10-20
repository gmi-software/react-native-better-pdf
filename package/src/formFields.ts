import { PdfMethodError } from './PdfMethodError';
import type { NativePdfFormField } from './types/NativePdfFormField';
import type { NativePdfFormValue } from './types/NativePdfFormValue';
import type { PdfFormField } from './types/PdfFormField';
import type { PdfFormFieldValue } from './types/PdfFormFieldValue';

/** A field as sent by the native view, as the type-discriminated `PdfFormField`. */
export function toFormField(native: NativePdfFormField): PdfFormField {
  const base = {
    name: native.name,
    isReadOnly: native.isReadOnly,
    isRequired: native.isRequired,
    widgets: native.widgets,
  };
  switch (native.type) {
    case 'text':
      return {
        ...base,
        type: 'text',
        value: native.text,
        isMultiline: native.isMultiline,
        ...(native.maxLength > 0 ? { maxLength: native.maxLength } : {}),
      };
    case 'checkbox':
      return { ...base, type: 'checkbox', isChecked: native.isChecked };
    case 'radio':
      return {
        ...base,
        type: 'radio',
        value: native.selectedOptions[0] ?? null,
        options: native.options,
      };
    case 'combo-box':
      return {
        ...base,
        type: 'combo-box',
        value: native.text === '' ? null : native.text,
        options: native.options,
        isEditable: native.isEditable,
      };
    case 'list-box':
      return {
        ...base,
        type: 'list-box',
        values: native.selectedOptions,
        options: native.options,
        isMultiSelect: native.isMultiSelect,
      };
    case 'signature':
      return { ...base, type: 'signature', isSigned: native.isSigned };
    case 'push-button':
      return { ...base, type: 'push-button' };
  }
}

/** Values by field name, as sent to the native view. */
export function toNativeFormValues(
  values: Readonly<Record<string, PdfFormFieldValue>>,
): NativePdfFormValue[] {
  return Object.entries(values).map(([name, value]) => {
    if (value === null) return { name };
    if (typeof value === 'string') return { name, text: value };
    if (typeof value === 'boolean') return { name, isChecked: value };
    if (
      Array.isArray(value) &&
      value.every((option) => typeof option === 'string')
    ) {
      return { name, options: [...(value as readonly string[])] };
    }
    throw new PdfMethodError(
      'invalid-argument',
      `Form field "${name}": expected a string, a boolean, an array of strings or null`,
    );
  });
}

/** A path for the native view: `file://` URIs decoded, '' for the default. */
export function toNativeFilePath(filePath: string | undefined): string {
  if (filePath == null) return '';
  return filePath.startsWith('file://')
    ? decodeURIComponent(filePath.slice('file://'.length))
    : filePath;
}
