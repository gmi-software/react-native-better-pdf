# Example app

An Expo development build that shows every feature of
`react-native-better-pdf` on generated documents: forms, links, outlines,
scans of 1,000 pages, encrypted and damaged files. Its benchmark screen runs
react-native-pdf and this library on the same documents, and the Maestro
end-to-end flows live in [e2e](e2e).

## Running it

From the repository root:

```bash
bun install
bun run fixtures                        # test documents (~650 MB), served by the next line
node benchmarks/serve-fixtures.mjs      # on :8090; keep it running in its own terminal (Android: adb reverse tcp:8090 tcp:8090)
cd example && bunx expo run:ios         # or run:android
```

[CONTRIBUTING.md](../CONTRIBUTING.md) covers the toolchain and the
end-to-end flows; [Benchmarks](../docs/benchmarks.md) covers running the
benchmark.
