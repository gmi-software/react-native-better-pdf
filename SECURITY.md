# Security Policy

## Supported versions

Fixes land on the latest minor release. Older minors do not receive backports —
upgrading is the supported path.

| Version | Supported |
| ------- | --------- |
| 1.0.x   | ✅        |
| < 1.0   | ❌        |

## Reporting a vulnerability

**Do not open a public issue.**

Report it privately through
[GitHub Security Advisories](https://github.com/gmi-software/react-native-better-pdf/security/advisories/new).
If that is not possible, email <security@gmi.software>.

Please include:

- the version of `react-native-better-pdf`, React Native and
  `react-native-nitro-modules`
- the platform and OS version (on Android 12–14, whether the device has SDK
  extension 13)
- what an attacker can do with it, and a reproduction if you have one — for a
  crafted document, attach the file to the advisory, not to a public issue

We aim to acknowledge a report within five working days. If a fix is warranted
we will agree a disclosure timeline with you, credit you in the advisory unless
you prefer otherwise, and publish the advisory alongside the release that fixes
it.

## Untrusted documents

Documents are parsed inside the app's process: by PDFKit on iOS, by the
platform `PdfRenderer` on Android, and on Android also by this library's own
Kotlin readers (the outline, the page tree, links, form fields and the
information dictionary). The readers are memory-safe JVM code with limits on
recursion and sizes, fuzz tested with corrupted files and with generated,
deeply nested ones. See [the audit](docs/audit.md) (H1).

What we want to hear about:

- a document that crashes the app, hangs it, or exhausts its memory or storage
  through this library's own code
- the viewer opening a URL, following an external action or running a
  document's JavaScript by itself — it never should: URL links are only
  reported to `onLinkPress`, and JavaScript actions are reported as page taps
- a cached copy of a document becoming readable by another app, or outliving
  `PdfCache` removal

A flaw inside PDFKit or the platform `PdfRenderer` itself belongs to Apple or
Google. Report it to us anyway if this library makes it reachable in a way the
platform viewer would not.

## Supply chain

From 1.0.1 on, releases are built and published only by the
[Release workflow](.github/workflows/release.yml), never from a developer
machine:

- npm publishing uses OIDC trusted publishing, so no long-lived npm token exists
  in the repository or on any laptop
- every version published by the workflow carries
  [npm provenance](https://docs.npmjs.com/generating-provenance-statements),
  which you can verify with `npm audit signatures`
- every GitHub Action is pinned by commit SHA, not by tag
- workflows check out with `persist-credentials: false`, and CI never writes to
  git

1.0.0 was published by a maintainer, before trusted publishing could be
configured, and carries no provenance; its tarball matches the `v1.0.0` tag.

If you believe a published artifact does not match this repository, treat it as
a vulnerability and report it through the channel above.
