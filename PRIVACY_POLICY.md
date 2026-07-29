# Systems Go — Privacy Policy

**⚠️ DRAFT — fill in the bracketed placeholders `[...]` before publishing.**
This was written directly from the app's actual permissions and code (not a
generic template), but a few facts only you can supply are marked below.

_Last updated: [DATE]_

## Summary

Systems Go is a remote-desktop client (RDP / VNC / SSH). It connects **directly**
from your device to servers **you** specify — your credentials and session
data go to those servers, not to us. We do not run any backend that your
connection data passes through, and we do not sell or share your data with
third parties for advertising.

## What Systems Go stores, and where

| Data | Where it lives | Leaves your device? |
|---|---|---|
| Connection profiles (hostname, username, password, SSH keys, gateway settings) | Locally, in a SQLCipher-encrypted (AES-256) database on your device | No — never transmitted to us. Passwords/keys are additionally encrypted per-field with a key held in the Android Keystore before being written to that database. |
| Session logs (which server you connected to and when) | Locally on your device | No |
| App lock PIN / biometric enrollment | Handled by Android's own Keystore/BiometricPrompt APIs | No — Systems Go never sees your biometric data itself, only a yes/no result from the OS |
| Encrypted backups you create | Wherever you choose to save the file (device storage / your own cloud provider) | Only if you choose to upload it somewhere yourself |

**We do not operate any server that stores your connection profiles,
credentials, or session content.** Everything above stays on your device
unless you explicitly export/back it up yourself.

## What Systems Go transmits, and to whom

- **To the remote server you configure** (RDP/VNC/SSH/gateway host): your
  login credentials and the screen/input/file/audio/printer/smart-card data
  needed to run that protocol — this is the app's core function and is
  between you and the server you chose, the same as any remote-desktop
  client (e.g. Microsoft Remote Desktop).
- **To [Sentry / crash-reporting provider], only if you have this enabled**:
  automatic crash/error diagnostics (stack traces, app version, device
  model). This is off unless the maintainer has configured it for a given
  build — see `SENTRY_SETUP.md`. Crash reports are configured to:
  - never attach a screenshot or view hierarchy of your screen,
  - never include device identifiers or IP address,
  - have password-like strings (`password=...`) and private-key blocks
    redacted from error messages before being sent.
  [If you are not using Sentry / have not set `SENTRY_DSN`, delete this
  bullet — no diagnostic data leaves the device at all.]
- **Nothing else.** Systems Go has no analytics SDK, no advertising SDK, and no
  telemetry beyond the optional crash reporting above.

## Permissions this app requests, and why

- **Camera** — only to scan a QR code for quick connection setup. The camera
  feed is processed on-device and never recorded or transmitted.
- **Microphone** — only used if you turn on "Redirect Microphone" for a
  specific connection, to send your mic audio to that remote session (same
  purpose as Microsoft's own audio-redirection feature). Not used otherwise,
  not recorded, not sent anywhere except the remote server you're connected
  to.
- **Storage / Photos & Videos** — to browse and transfer files between your
  device and a remote session (drive/file redirection), and to save
  encrypted backups you create.
- **USB / NFC** — only used if you enable smart-card redirection, to talk to
  a physical smart-card reader (USB) or a tapped contactless card (NFC) for
  authenticating a remote session. Card data is relayed to the remote server
  exactly as a physical smart-card reader would; Systems Go does not store it.
- **Network state / Wi-Fi state / multicast** — to discover devices on your
  local network (e.g. Wake-on-LAN) and to show connection-quality
  information during a session.
- **Notifications / foreground service** — to keep a remote session running
  reliably in the background and show its ongoing-session/reset-countdown
  notification.
- **Biometric** — to let you lock the app with your device's fingerprint/
  face unlock instead of (or in addition to) a PIN. Handled entirely by the
  Android BiometricPrompt API; no biometric data is ever available to
  Systems Go itself.

## Data retention and deletion

All of the data described above lives in local app storage. Uninstalling
Systems Go removes it. The in-app "Reset Application Data" / secure-wipe feature
removes it immediately without uninstalling.

## Children's privacy

Systems Go is a technical/administrative tool not directed at children and is
not knowingly used by children.

## Changes to this policy

[Describe how you'll notify users of changes — e.g. an updated date here
plus an in-app notice on the next Play Store update.]

## Contact

[Your support email / contact method here.]
