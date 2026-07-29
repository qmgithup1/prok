package com.systemsgo.hex.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * MANAGE-SPACE FEATURE (fixes the "24-hour vulnerability"):
 *
 * Declared as `android:manageSpaceActivity` in AndroidManifest.xml. Its mere
 * presence there tells Android to replace the default, completely
 * unauthenticated "Clear storage" / "Clear data" button shown in
 * Settings → Apps → SystemsGo → Storage with a "Manage space" button that
 * launches this Activity instead of performing the wipe itself — the same
 * approach apps like Telegram use so that clearing app data can never bypass
 * their own in-app passcode.
 * https://developer.android.com/reference/android/R.attr#manageSpaceActivity
 *
 * WHY THIS MATTERS: before this Activity existed, anyone with physical
 * access to an unlocked device could open the OS's own app-info screen and
 * instantly, silently erase every saved connection and credential with a
 * single tap. That completely bypassed both App Lock (PIN/biometric) and the
 * "Forgot PIN?" 24-hour delayed-reset safeguard (see DataResetManager) —
 * neither one ever ran, because it is the *system*, not our code, that clears
 * storage when the user taps that button. Declaring manageSpaceActivity
 * removes that button entirely.
 *
 * This Activity is intentionally a pure trampoline: it has no UI, reads no
 * data, and performs no destructive action itself. It forwards straight into
 * MainActivity via [MainActivity.EXTRA_OPEN_DATA_MANAGEMENT], which already
 * knows how to gate this behind App Lock and then land on
 * DataManagementScreen (the same reviewable cache / history / "erase
 * everything" screen reachable from Settings → Data → Clear Data — see
 * DataManagementScreen.kt). Whatever authentication and confirmation steps a
 * user already has to go through inside the app are therefore also required
 * when entering through this OS-provided door — there is no separate, weaker
 * code path to keep in sync.
 */
class ManageSpaceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val forward = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_DATA_MANAGEMENT, true)
            // The system Settings app starts this Activity in its own task.
            // NEW_TASK hands off into SystemsGo's task instead of stranding a
            // second instance there; CLEAR_TOP + SINGLE_TOP reuse the real
            // app instance (delivering via onNewIntent) if it's already
            // running, rather than spawning a duplicate MainActivity.
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        startActivity(forward)
        finish()
    }
}
