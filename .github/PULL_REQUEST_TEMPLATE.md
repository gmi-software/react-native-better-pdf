## What does this change?

<!-- What the change does, and why. Link the issue it closes: "Closes #123". -->

## How was it verified?

<!--
Say what you actually ran — "builds" is not verification.
For native changes, name the platform, OS version and device you tested on,
and the end-to-end flows you ran (example/e2e).
-->

## Scope

- **Platforms:** <!-- iOS / Android / both / JS only -->
- **Android versions:** <!-- 15+ / 12–14 with SDK extension 13 / below / not Android specific -->

## Checklist

- [ ] `bun run lint`, `bun run typecheck` and `bun run test` pass
- [ ] New behavior is covered by a test (JS, Kotlin JVM or an end-to-end flow)
- [ ] Nitro specs changed? `cd package && bun run specs` was re-run and `package/nitrogen/` is committed with it
- [ ] Public API changed? `docs/api.md`, the feature's guide in `docs/guides/`, the README's capability matrix and `docs/api-design.md` are updated
- [ ] Native change? Validated on both iOS and Android
- [ ] Performance claim? A benchmark run on a release build is attached
- [ ] Commits follow [Conventional Commits](https://www.conventionalcommits.org/)
- [ ] Behavior changed without a type change? Say so explicitly above — it breaks consumers whose code still compiles
