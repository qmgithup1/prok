# Sentry crash reporting — setup

Referenced from `app/build.gradle.kts` (the `SENTRY_DSN` BuildConfig field)
and `SECURITY.md`. This file was missing from the repo, which is the actual
reason Sentry has stayed dormant: `Systems GoApp.initCrashReporting()` is written
correctly (it skips `SentryAndroid.init()` only when `BuildConfig.SENTRY_DSN`
is blank — see that file), but with no doc telling anyone *how* to set the
DSN, and no `.github/workflows/` in this checkout to inject it in CI, every
build produced from this repo has had an empty DSN by default. That's a
missing-configuration gap, not a code bug — the fix is to actually configure
it, in one (or both) of the two places the Gradle script reads from:

```kotlin
val sentryDsn = System.getenv("SENTRY_DSN")
    ?: (project.findProperty("sentry.dsn") as String?)
    ?: ""
```

## 1. Get a DSN

Create (or reuse) a project at <https://sentry.io> → **Settings → Projects →
(your project) → Client Keys (DSN)**. Copy the DSN — it looks like
`https://<key>@o<org>.ingest.sentry.io/<project-id>`.

The DSN is *not* a secret in the sense of granting write/read access to your
Sentry data (it only lets a client submit new events) — Sentry's own docs say
it's fine to ship inside a compiled APK. Keep it out of source control anyway,
same as any other project-specific config (see `SECURITY.md`).

## 2. Local builds (Android Studio / `./gradlew` on your machine)

Add one line to `~/.gradle/gradle.properties` (**not** the project's
`gradle.properties` — this file is per-machine and never committed):

```properties
sentry.dsn=https://<key>@o<org>.ingest.sentry.io/<project-id>
```

That's it — the next Gradle sync/build picks it up via
`project.findProperty("sentry.dsn")`. Leave it unset on a machine where you
don't want crash reporting active locally (e.g. a personal debug build); the
app runs fine with it blank, just with reporting off.

## 3. CI builds (GitHub Actions)

This repo has no `.github/workflows/` directory checked into this copy. If/when
one is added back (`main.yml` builds the app; `codeql.yml` runs static
analysis — both are referenced in `SECURITY.md` and in comments in
`app/build.gradle.kts`), wire the DSN in as a repo secret:

1. **Settings → Secrets and variables → Actions → New repository secret**
   Name: `SENTRY_DSN`, value: the DSN from step 1.
2. In the build job's `env:` block (or as a step-level env var immediately
   before the `./gradlew assemble...`/`./gradlew bundle...` step):

   ```yaml
   env:
     SENTRY_DSN: ${{ secrets.SENTRY_DSN }}
   ```

   `System.getenv("SENTRY_DSN")` in `build.gradle.kts` picks this up
   automatically — no other CI change needed.

Rotate the secret the same way you'd rotate the signing secrets
(`KEYSTORE_BASE64`, `KEY_ALIAS`, etc. — see `SECURITY.md`) if it's ever
regenerated in the Sentry project settings or a collaborator with access to
it is removed.

## 4. Verifying it's actually active

- Debug builds log nothing special either way (Sentry init is silent and
  best-effort — see the `catch (_: Exception)` in `initCrashReporting()`).
- Trigger a test crash (or use Sentry's own
  [`Sentry.captureMessage(...)`](https://docs.sentry.io/platforms/android/)
  from a debug menu) and confirm the event shows up in the Sentry project
  within a minute or two.
- If it never shows up, check for a typo in the property/secret name first
  (`sentry.dsn` locally, `SENTRY_DSN` in CI/env — case-sensitive) before
  assuming the SDK itself is broken.
