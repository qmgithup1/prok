package com.systemsgo.hex.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * PERF-FIX: run this ONCE from Android Studio (right-click ▶ next to
 * [generate], or `./gradlew :app:generateReleaseBaselineProfile` from a
 * terminal) with a connected device to produce
 * app/src/main/generated/baselineProfiles/baseline-prof.txt.
 *
 * Requires either:
 *  - a rooted device/emulator, or
 *  - any device running Android 13 (API 33) or higher (no root needed).
 *
 * This is NOT a benchmark itself — it doesn't measure timing, it just
 * *records* which code paths are exercised by the journey below so ART
 * can pre-compile them ahead of time on real user devices. See
 * StartupBenchmarks.kt in this same module for the benchmark that actually
 * measures the before/after improvement.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = "com.systemsgo.hex",
        // Re-running the profiled journey a few times gives ART's profile
        // matching a more representative/stable picture than a single run.
        maxIterations = 5,
        // The app's own AppLockScreen already fully repaints the screen
        // (see HomeScreen/AppLockScreen's StarfieldBackground comments in
        // :app), so a stable, deterministic starting state — a fresh
        // install with no PIN/biometric lock configured yet — is exactly
        // what BaselineProfileRule gives by default (it reinstalls the app
        // before each iteration). No extra unlock step is needed.
    ) {
        pressHome()
        startActivityAndWait()

        // ── Critical User Journey #1: cold start ────────────────────────
        // Deliberately kept to just startup for now — the single highest-
        // value, lowest-risk journey to profile (per Google's own guidance:
        // "pick your most critical user journey — even if it's just your
        // app startup"). Waiting here for the content to actually settle
        // (rather than returning immediately) is what lets the profiler
        // capture the *first real frame* of Home (profiles loaded from the
        // SQLCipher-encrypted database, list rendered), not just the empty
        // splash/shell.
        device.waitForIdle()

        // ── Extending this later ─────────────────────────────────────────
        // To also profile navigating into Settings (a very common next
        // journey worth precompiling too), add a `Modifier.testTag("...")`
        // to the relevant icon/button in HomeScreen.kt, then here:
        //
        //   device.findObject(By.desc("Settings")).click()
        //   device.wait(Until.hasObject(By.text("...")), 3_000)
        //
        // There are currently no Compose `testTag`/contentDescription
        // hooks in the UI for UiAutomator to reliably target, so rather
        // than guess at brittle text-based selectors (By.text() breaks the
        // moment a string changes language or wording), this generator
        // intentionally stays limited to the cold-start journey until
        // those hooks are added.
    }
}
