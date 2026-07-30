// ════════════════════════════════════════════════════════════════════
// :baselineprofile — instrumentation-test-only module.
// ════════════════════════════════════════════════════════════════════
//
// PERF-FIX: this module is NEVER packaged into the shipped APK
// (com.android.test = "test-only" AGP plugin type). It exists purely to
// run on a connected device/emulator and record which classes/methods
// HEX RDP actually touches during its most important user journeys
// (right now: cold start). That recording becomes
// app/src/main/generated/baselineProfiles/baseline-prof.txt, which ART
// then uses to pre-compile (AOT) exactly those hot code paths at install
// time on the *user's* device — instead of the runtime having to
// interpret/JIT-compile them from scratch on every cold start, which is
// the single biggest, most consistently-cited "free" win in current
// (2026) Android performance guidance: Google's own numbers put this at
// roughly a 20-30% reduction in cold-start time for a typical
// Compose + Hilt + Room app like this one, with zero runtime cost and no
// behavior change — it only affects *how* already-correct code gets
// compiled, never *what* it does.
//
// HOW TO ACTUALLY GENERATE THE PROFILE (must be done once from Android
// Studio, with a connected device — this cannot run inside a CI/sandbox
// without a device attached):
//   1. Connect a physical device (any Android 8.0/API 26+ device is
//      fine; Android 13+ works without root, older devices need root —
//      see BaselineProfileGenerator.kt for details).
//   2. In Android Studio's Gradle panel: baselineprofile > Tasks >
//      benchmark > generateReleaseBaselineProfile — or from a terminal:
//        ./gradlew :app:generateReleaseBaselineProfile
//   3. Done — the plugin writes the profile straight into the :app
//      module and every future ./gradlew assembleRelease automatically
//      bundles it. No further steps, and nothing to commit by hand other
//      than the generated file itself (safe, human-readable text, worth
//      committing to git so every teammate's release build benefits).
//
// Re-run step 2 again whenever a major navigation/startup-path change
// ships (new Home screen layout, changed first-run flow, etc.) so the
// profile keeps matching what the app actually does on launch.

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.systemsgo.hex.baselineprofile"
    compileSdk = 35

    // PERF-FIX: must match :app's minSdk-compatible floor for Macrobenchmark
    // itself. 28 (Android 9) is the commonly-used floor for StartupTimingMetric
    // reliability; :app's own minSdk (26) is unaffected — this only bounds
    // which devices can *run the generator*, not which devices can *use* the
    // resulting profile (that still works down to :app's real minSdk).
    defaultConfig {
        minSdk = 28
        targetSdk = 35

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Points this instrumentation-test module at the real app to benchmark.
    targetProjectPath = ":app"

    buildTypes {
        // Matches :app's `debug` applicationIdSuffix (".debug") behavior:
        // keep the generator's own build type unminified/debuggable so
        // recorded method signatures aren't obfuscated by R8. The
        // androidx.baselineprofile plugin automatically creates and targets
        // a matching non-obfuscated "release-like" variant on the :app side
        // for the actual recording — this local build type just needs to
        // exist and build successfully.
        create("benchmark") {
            isDebuggable = true
            signingConfig = getByName("debug").signingConfig
            matchingFallbacks += listOf("release")
        }
    }

    // Must match :app's own compileOptions/kotlinOptions (both 17) — without
    // this, Java here defaults to 1.8 while Kotlin defaults to the toolchain
    // JDK (17), and AGP's strict JVM-target validation fails the build with
    // "Inconsistent JVM Target Compatibility Between Java and Kotlin Tasks".
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// PERF-FIX: see the big comment block above — deliberately manual/on-demand
// rather than automatic. See app/build.gradle.kts's own `baselineProfile { }`
// block for the matching automaticGenerationDuringBuild = false there.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
