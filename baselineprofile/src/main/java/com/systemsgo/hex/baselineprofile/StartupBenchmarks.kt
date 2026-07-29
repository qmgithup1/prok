package com.systemsgo.hex.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PERF-FIX: run this from Android Studio (with a connected device) to get
 * real, on-device numbers for HEX RDP's cold-start time — both with and
 * without the Baseline Profile applied. This is what actually *proves* the
 * win from BaselineProfileGenerator.kt rather than just trusting the
 * generic "20-30%" industry figure.
 *
 * Compare the two tests below:
 *  - [startupCompilationModeNone]  → the runtime with NO profile/AOT help
 *    at all (worst case — everything interpreted/JIT'd from scratch).
 *  - [startupWithBaselineProfile]  → the runtime with the generated
 *    baseline-prof.txt applied (this is the realistic "production" case
 *    once you've run BaselineProfileGenerator and shipped the profile).
 *
 * Each run prints timeToInitialDisplayMs to Android Studio's Run panel —
 * that's the number to compare between the two.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmarks {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupCompilationModeNone() = startup(CompilationMode.None())

    @Test
    fun startupWithBaselineProfile() = startup(CompilationMode.Partial())

    private fun startup(compilationMode: CompilationMode) = benchmarkRule.measureRepeated(
        packageName = "com.systemsgo.hex",
        metrics = listOf(StartupTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.COLD,
        compilationMode = compilationMode,
    ) {
        pressHome()
        startActivityAndWait()
    }
}
