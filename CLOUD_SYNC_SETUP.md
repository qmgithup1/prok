# Cloud Sync Setup (Google Drive / Dropbox)

Systems Go ships with both cloud-sync providers fully wired in
(`GoogleDriveSyncProvider.kt`, `DropboxSyncProvider.kt`), but each stays
**cleanly disabled** — "Connect" button greyed out with an explanatory
subtitle in `CloudSyncSettingsScreen` — until its key is configured. No
Google Cloud project or Dropbox app was created as part of this change,
since both require an account only the repo owner can set up.

Nothing in the source or in `AndroidManifest.xml` needs to be touched to
turn either provider on — see "What changed" below for why.

## One-time setup — Google Drive

1. Create/select a project at https://console.cloud.google.com.
2. **APIs & Services → Library** → enable "Google Drive API".
3. **APIs & Services → Credentials → Create Credentials → OAuth client ID**:
   - Create an **Android** client: package name `com.systemsgo.hex` +
     your release/debug signing certificate's SHA-1 fingerprint. (Required
     so Play services will authorize the account — its client ID is not
     the one used in code.)
   - Create a **Web application** client. **This is the one you need the
     ID of** — `GetGoogleIdOption` needs a Web client ID as the token
     audience even though this is an Android app.
4. Add the Web client ID as a repo secret: **Settings → Secrets and
   variables → Actions → New repository secret**, name
   `GOOGLE_DRIVE_WEB_CLIENT_ID`, value = the ID (ends in
   `.apps.googleusercontent.com`).
5. For local debug builds, instead add one line to your own
   `~/.gradle/gradle.properties` (NOT this repo's `gradle.properties` —
   that file is committed):
   ```
   google.drive.client.id=XXXXXXXXXXXX.apps.googleusercontent.com
   ```

## One-time setup — Dropbox

1. Create an app at https://www.dropbox.com/developers/apps.
2. **Settings tab** → under "Permissions type", choose **App folder** (not
   "Full Dropbox") — this is what actually confines the app to
   `/Apps/Systems Go/...`, not anything in this app's code.
3. Copy the **App key** shown on the Settings tab.
4. Add it as a repo secret: name `DROPBOX_APP_KEY`, value = the app key.
5. For local debug builds, instead add one line to your own
   `~/.gradle/gradle.properties`:
   ```
   dropbox.app.key=your_app_key_here
   ```

That's it — no code or manifest change needed either way.

## What changed (CLOUD-SYNC-CONFIG fix)

Previously, the Google Web client ID and Dropbox app key were hardcoded
`"TODO_REPLACE_WITH_..."` placeholder literals duplicated across three
places (`GoogleDriveSyncProvider.kt`, `DropboxSyncProvider.kt`, and
`AndroidManifest.xml`'s Dropbox redirect `intent-filter`) — filling them in
meant hand-editing source and the manifest, and keeping the Dropbox app
key in sync between the code constant and the manifest scheme by hand.

`app/build.gradle.kts` now reads `GOOGLE_DRIVE_WEB_CLIENT_ID` /
`DROPBOX_APP_KEY` (env, CI) or `google.drive.client.id` / `dropbox.app.key`
(Gradle property, local) into `BuildConfig.GOOGLE_DRIVE_WEB_CLIENT_ID` /
`BuildConfig.DROPBOX_APP_KEY`, falling back to the same placeholder
strings if neither is set. Both provider classes read from `BuildConfig`
instead of a local constant, and the same `dropboxAppKey` value also
derives the `${dropboxRedirectScheme}` manifest placeholder used in
`AndroidManifest.xml`, so the code and manifest values can no longer drift
apart.

`GoogleDriveSyncProvider.isConfigured` / `DropboxSyncProvider.isConfigured`
(checked by `CloudSyncSettingsScreen` to enable/disable each "Connect"
button) still work exactly the same way — they just check the
`BuildConfig`-sourced value now instead of the old inline constant.

## Verifying it's live

After setting the property/secret and rebuilding, open Settings → Cloud
Sync — the corresponding "Connect Google Drive" / "Connect Dropbox" button
should now be enabled (no "not configured" subtitle), and tapping it should
proceed to the real account picker / OAuth flow instead of doing nothing.
