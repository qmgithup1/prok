# Play Console → Data Safety form — how to answer it for Systems Go

This isn't something a file in the repo can fill in for you (it's a form in
Play Console, not app config), but here's how each section maps to what the
app actually does, based on the current code:

## Does your app collect or share any of the required user data types?

**Yes**, if you configure `SENTRY_DSN` (crash reporting) or ship a build
where storage/network permissions above are used for their stated purpose.
**Answer per the table below.**

| Play Console data type | Collected? | Shared with 3rd party? | Notes |
|---|---|---|---|
| Personal info (name, email, etc.) | No | No | Not requested or stored by the app itself. |
| Financial info | No | No | — |
| **Photos and videos** | No | No | Camera is used live for QR scanning only — no photo/video is captured, stored, or transmitted. Only declare this if you later add screenshot/recording features. |
| Files and docs | Depends on your answer | No | If you count files the user transfers *through* a session (drive redirection) as "collected", mark **Yes / not shared / used for App functionality only** — those files go only to the remote server the user configured, never to you. |
| **App activity → Crash logs** | **Yes, only if `SENTRY_DSN` is configured** | **Yes — shared with Sentry (or your chosen provider) for crash reporting** | Purpose: **App functionality / Analytics**. Mark "optional" if you ever add an opt-out toggle; currently it's controlled at build-config time by the maintainer, not per-user, so mark it as always-on for that build. |
| App activity → Diagnostics | Same as crash logs above | Same as above | — |
| **Device or other IDs** | No | No | `isSendDefaultPii = false` in Sentry config — no device identifier is attached to crash reports. |
| Approximate/precise location | No | No | App has no location permission. |
| Health/fitness | No | No | — |

## Is all of the user data collected by your app encrypted in transit?

**Yes** for connections to the remote server (RDP/VNC/SSH — see the VeNCrypt/
TLS and SSH-transport work already done) *when the user's server supports
it*; be honest here — if a user connects to a VNC server with no VeNCrypt
support, that specific session is not encrypted, which the in-app warning
(driven by `isEncrypted`) already surfaces to them.

Sentry crash reports go over HTTPS.

## Do you provide a way for users to request data deletion?

**Yes** — say so, and describe: uninstalling the app, or the in-app
"Reset Application Data" (secure wipe) feature, removes all locally-stored
data immediately. If Sentry is enabled, also note whether/how you'd honor a
request to delete a specific user's crash reports from the Sentry project
(this is a manual step in the Sentry dashboard, not something the app does
automatically).

## Before submitting

1. Decide whether `SENTRY_DSN` will actually be set for your shipped release
   build (see `SENTRY_SETUP.md`) — the Data Safety answers above assume it
   is. If you decide not to enable crash reporting, the "Crash logs" /
   "Diagnostics" rows both become **No** instead.
2. Cross-check this against `PRIVACY_POLICY.md` — the Play Console Data
   Safety form and your privacy policy need to describe the same behavior;
   a mismatch is a common cause of Play Store rejection.
