# Releasing

Releases are published to npm from CI with
[provenance](https://docs.npmjs.com/generating-provenance-statements) by the
[Release workflow](.github/workflows/release.yml). Nothing is published from a
developer machine, and no long-lived npm token exists anywhere.

Publishing is triggered by pushing a version tag. CI never writes to git: the
version bump goes through a normal reviewed pull request instead of a release
commit pushed by a bot.

## What guards a release

Only a reviewed commit on `main` is meant to reach npm:

- **Branch protection.** `main` takes changes only through a pull request with
  an approving review and green `commitlint`, `quality` and `native-gate`
  checks. Administrators are held to it too.
- **Tag rule.** Only the maintainers on the bypass list of the `Release tags`
  ruleset can create, move or delete a `v*` tag.
- **Ancestry check.** The workflow stops within seconds when the tagged commit
  is not on `main`.
- **Environment.** The publish job runs in the `npm` environment, which admits
  only `v*` tags and holds the job until a maintainer approves it.

The ancestry check is a step in the workflow, and a run reads the workflow from
its own ref. It catches a tag pushed to the wrong commit; it cannot stop someone
who edits the workflow on a branch. The environment can, because its rules are
repository settings, but only if npm refuses a run that skipped it. That is why
the trusted publisher has to name the environment: see
[One-time setup](#one-time-setup).

## Cutting a release

**1. Open a release pull request.** Bump the version:

```bash
cd package
npm version 1.1.0 --no-git-tag-version --no-workspaces-update
cd .. && bun install
```

`--no-workspaces-update` stops npm from reifying the workspace, which fails on
the example app's `workspace:*` dependency; `bun install` then records the new
version in `bun.lock`. Open a pull request with both changes, then review and
merge it as usual.

**2. Rehearse (optional).** Run the **Release** workflow manually from the
Actions tab. A manual run is always a dry run: it validates the version and the
full gate without publishing. It stays outside the `npm` environment, so nobody
has to approve it, and on a commit that is not on `main` it warns instead of
failing.

**3. Push the tag.**

```bash
git checkout main && git pull
git tag -a v1.1.0 -m 'v1.1.0'
git push origin v1.1.0
```

The workflow checks that the tagged commit is on `main`, then compiles the
library for iOS and Android (the same jobs as the
[Native workflow](.github/workflows/native-build.yml)).

**4. Approve the publish.** When the native builds pass, the run waits for a
review of the `npm` environment. Open the run, check that it is for the tag and
commit you expect, and approve it under **Review deployments**.

The workflow then verifies the tag matches `package/package.json`, that the
version is not already on npm and that `package/nitrogen` matches the specs;
runs lint, typecheck, build and tests; publishes to npm with provenance; and
creates the GitHub Release with notes generated from the conventional commits
since the last tag.

The iOS podspec reads its version from `package.json`, so there is no second
version to keep in sync.

## Versioning

The bump is a judgement call, made when you open the release pull request. The
usual rules apply — `fix:` is a patch, `feat:` a minor, an incompatible API
change a major — but two cases are easy to get wrong:

- A commit that is not conventional is invisible to the generated release
  notes. commitlint rejects those on pull requests, but edit the GitHub Release
  body by hand if one slips through.
- A change in runtime behavior that keeps the same types — such as an event
  that starts firing once per gesture instead of continuously — breaks
  consumers even though their code still compiles. Either take the major, or
  ship it as a minor and add a prominent **Behavior changes** section to the
  GitHub Release body.

## Pre-releases

A version with a prerelease identifier works without extra configuration. Tagging
`v1.1.0-rc.1` publishes under the `rc` dist-tag rather than `latest`, so
`npm install react-native-better-pdf` is unaffected, and the GitHub Release is
marked as a pre-release. release-it derives both from the version itself.

Consumers opt in explicitly:

```bash
npm install react-native-better-pdf@rc
```

## Release notes

There is no `CHANGELOG.md`. The release notes are the GitHub Release body,
generated from the conventional commits since the previous tag by
`@release-it/conventional-changelog`.

Commit subjects cannot carry the parts of a release that matter most — behavior
changes, migration snippets, the reason a fix exists. When a release needs
them, edit the GitHub Release body by hand once the workflow has created it.

## One-time setup

### Repository settings

Apart from the ancestry check, the guards are settings of the GitHub
repository. Nothing in git records them, so check them when the maintainers
change:

- **Branch protection on `main`**: a pull request with one approval, the status
  checks `commitlint`, `quality` and `native-gate`, and **Do not allow
  bypassing the above settings**.
- **Ruleset `Release tags`**: targets tags matching `v*`, restricts creations,
  updates and deletions, and has the release maintainers on its bypass list.
- **Environment `npm`**: required reviewers, deployment tags limited to `v*`,
  and **Allow administrators to bypass configured protection rules** turned
  off.

```bash
gh api repos/gmi-software/react-native-better-pdf/branches/main/protection --jq .enforce_admins.enabled
gh api repos/gmi-software/react-native-better-pdf/rulesets --jq '.[].name'
gh api repos/gmi-software/react-native-better-pdf/environments --jq '.environments[].name'
```

### npm trusted publishing

Publishing uses OIDC, so it only works once npm knows which workflow may publish
this package. On npmjs.com, open the `react-native-better-pdf` package settings
→ **Trusted publishing** and add a **GitHub Actions** trusted publisher pointing
at:

- repository: `gmi-software/react-native-better-pdf`
- workflow: `release.yml`
- environment: `npm`
- allowed actions: `npm publish`

The workflow publishes directly, and a new trusted publisher only allows
`npm stage publish` unless `npm publish` is selected as well. Without it the
publish step fails after the approval.

A trusted publisher cannot be edited, only revoked and created again. A new one
expires unless a real (not dry-run) publish succeeds with it within 2 days, so
create it when the next release is about to be tagged, not earlier. Until it is
configured the publish step fails with an authentication error.

> **Pending.** The publisher in use since 1.0.1 names no environment, so npm
> still accepts a publish from any run of a workflow file called `release.yml`
> in this repository, approved or not. Replace it while cutting the next
> release, between merging the release pull request and pushing the tag, then
> delete this note.

Provenance also requires the repository to be public.

## The first release

npm only accepts a trusted publisher for a package that already exists, so
1.0.0 was published by hand from `package/`, and carries no provenance. Pushing
its `v1.0.0` tag started the Release workflow, which was cancelled: the version
was already on npm. Every later version goes through the workflow.

## Notes on the configuration

A few settings exist for non-obvious reasons:

- `npm.skipChecks: true` in `package/.release-it.json` — release-it otherwise
  runs `npm whoami` at startup, which fails under trusted publishing because the
  token is only minted at publish time.
- The workflow does **not** set `registry-url` on `actions/setup-node` — doing so
  writes an `_authToken` entry into `.npmrc`, which makes npm assume classic
  token auth and skip the OIDC flow. The registry is pinned by `publishConfig`.
- `release-it` runs with `--no-increment --no-git`: the version is already
  committed and the tag already pushed, so CI only publishes and creates the
  release.
- The workflow copies `README.md` into `package/` before publishing. npm reads
  the README shown on the registry page before any pack script runs, so the
  `prepack` copy alone never reaches npmjs.com.

## Inspecting a release locally

A local publish would produce a package without provenance, so it is not
supported. To see what a release would do:

```bash
cd package
bunx release-it --no-increment --no-git --dry-run
```
