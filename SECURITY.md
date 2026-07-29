# Security Policy

This repository is private and is used solely as the build source for the
Systems Go Android app (distributed via Google Play). This document is internal
notes, not a public disclosure policy.

## Reporting a problem

If you (or a collaborator with repo access) find a security issue in this
codebase or its CI pipeline:

1. Do **not** open a public GitHub issue for it.
2. Note the affected file/workflow step and a short description.
3. Fix it directly on a branch, or flag it to the repo owner before merging
   to `main`/`master`/`develop` (those branches trigger CI and, on a `v*.*.*`
   tag, a signed release build).

## Scope notes specific to this project

- Native dependencies (FreeRDP, OpenSSL, PCSC-lite, CUPS, openh264) are
  pulled and built in `.github/workflows/main.yml`. Version/tag bumps for
  any of them should be treated as security-relevant changes — check
  upstream release notes before bumping `FREERDP_TAG` / `OPENSSL_VERSION`
  etc.
- Release signing secrets (`KEYSTORE_BASE64`, `KEY_ALIAS`, `KEY_PASSWORD`,
  `STORE_PASSWORD`) live in **Settings → Secrets and variables → Actions**.
  Rotate them if a collaborator with access is removed, or if they may have
  leaked (e.g. printed in a log).
- `SENTRY_DSN` (crash reporting, see `SENTRY_SETUP.md`) is set the same way.
  It is not secret in the sense of granting write access — it only lets a
  client *submit* events to the project — but keep it out of source anyway,
  same as any other project-specific config. If it's ever removed/rotated
  in the Sentry project settings, update the repo secret to match.
- CodeQL static analysis runs automatically on pushes/PRs to
  `main`/`master`/`develop` (see `.github/workflows/codeql.yml`) — check its
  results under the repo's **Security → Code scanning alerts** tab.
