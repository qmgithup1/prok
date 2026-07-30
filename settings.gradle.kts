pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // Needed for com.microsoft.device.display:display-mask, a transitive
        // dependency of com.microsoft.identity:common (pulled in by MSAL,
        // com.microsoft.identity.client:msal). This artifact is NOT on
        // Google's or Maven Central's repos — Microsoft only publishes it to
        // their own Azure DevOps feed. See Microsoft's own MSAL Android
        // setup docs and AzureAD/microsoft-authentication-library-for-android
        // issues #1027/#1720/#2383 for the same "Could not find
        // com.microsoft.device.display:display-mask:0.3.0" failure.
        maven {
            url = uri("https://pkgs.dev.azure.com/MicrosoftDeviceSDK/DuoSDK-Public/_packaging/Duo-SDK-Feed/maven/v1")
            name = "Duo-SDK-Feed"
            content {
                includeGroup("com.microsoft.device.display")
            }
        }
        // NOTE: net.zetetic:android-database-sqlcipher is published directly on
        // mavenCentral() (including its Gradle module metadata), so the old
        // "https://www.zetetic.net/maven/" repository entry has been removed.
        // That host is no longer a reliable/maintained Maven repo for this
        // artifact and was causing dependency resolution to fail in CI
        // (manifesting as "Unresolved reference 'zetetic'" /
        // "Unresolved reference 'SQLiteDatabase'" / "Unresolved reference
        // 'SupportFactory'" during :app:compileDebugKotlin, even though the
        // dependency itself was correctly declared in libs.versions.toml).
    }
}

rootProject.name = "Systems Go"
include(":app")
// PERF-FIX: موديول instrumentation-test فقط (com.android.test) — يولّد
// baseline-prof.txt عبر Macrobenchmark على جهاز حقيقي متصل، ولا يُحزَّم أبداً
// داخل الـ APK النهائي. شغّله من Android Studio: Run ▶ على
// BaselineProfileGenerator.kt (يتطلب جهاز فعلي أو Android 13+ بدون root).
include(":baselineprofile")
