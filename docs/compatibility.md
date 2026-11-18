# Compatibility

| Requirement | Version / note |
| --- | --- |
| React Native | `0.83+` |
| React Native architecture | New Architecture (Fabric); Nitro Views do not support the legacy renderer |
| Nitro Modules | `react-native-nitro-modules >=0.37.0` |
| iOS | `15.1+` |
| Android | `minSdkVersion 24+`; some features need a newer Android, see the [capability matrix](#capability-matrix) |
| Expo | SDK 55+ with a development build (verified with SDK 57); Expo Go is not supported |

Developed and tested with React Native 0.86.3 / Expo SDK 57, Xcode 27,
Kotlin 2.1, AGP 8.12 / Gradle 9.3, Node 24.

## Capability matrix

Several Android features come from the platform renderer's newer API: on
Android 15+, and on Android 12–14 when the device has SDK extension 13 (the
PDF module updated through Google Play system updates; the library then uses
`PdfRendererPreV`).

| Capability | iOS | Android 15+, or 12–14 with SDK extension 13 | Other Android versions |
| --- | --- | --- | --- |
| Viewing, zoom, layouts, offline, overlays, dark pages | Supported | Supported | Supported |
| Several sources as one document (reading only) | Supported | Supported | Supported |
| Outline, page sizes, page images, printing, `PdfDocument` | Supported | Supported (password-protected documents: no outline or metadata) | Supported |
| Links and page taps | Supported | Supported (password-protected documents: URL links and GoTo actions with an XYZ destination) | Supported |
| Password-protected documents | Supported | Supported | Unsupported: `unsupported-pdf` |
| Search | Supported | Supported | Unsupported: `isSupported: false` |
| Text selection | Supported | Supported | Unsupported: long presses are ignored |
| Forms: fill in, set from code, export | Supported | Supported | Read only: `getFormFields` works, `setFormFieldValues` rejects, `exportDocument` copies the file |
| Annotations drawn | All | Highlights, notes, free text and form fields; stamps from Android 17; no shapes, ink, underline or strike-out | Form fields |
| Screen readers read page text | Supported | Supported | The page label only |

Details are in each feature's guide and in [Limitations](limitations.md).
