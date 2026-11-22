# Type tests

Compile-only checks of the published declarations as an app sees them, under
`exactOptionalPropertyTypes` (as in `@tsconfig/strictest`). They import
`react-native-better-pdf`, which resolves to `lib/`, so build first:

```sh
bun run build && bun run typetest
```
