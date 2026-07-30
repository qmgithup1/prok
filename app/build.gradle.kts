import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // PERF-FIX: يستهلك الملف الذي يولّده موديول :baselineprofile ويحزمه
    // تلقائياً داخل الـ APK وقت البناء (release فقط). لا تأثير على البناء
    // إن لم يوجد ملف مولَّد بعد — يعمل build عادي بدون أي baseline profile.
    alias(libs.plugins.androidx.baselineprofile)
}

// CLOUD-SYNC-CONFIG: local.properties isn't read by Gradle automatically the
// way gradle.properties is — load it by hand so google.drive.client.id /
// dropbox.app.key placed there (see local.properties, gitignored) are picked
// up by project.findProperty(...) below exactly like a normal Gradle property.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
localProperties.forEach { (key, value) -> project.ext.set(key.toString(), value.toString()) }

android {
    namespace = "com.systemsgo.hex"
    compileSdk = 35
    // تثبيت إصدار NDK صراحةً — يمنع Gradle من اختيار أحدث NDK مثبّت على الجهاز/CI
    // ✅ r27d LTS — يطابق NDK_VERSION في main.yml (27.3.13750724)
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.systemsgo.hex"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        resourceConfigurations += listOf("en", "ar")

        // ── aFreeRDP native bridge (RDP) ─────────────────────────────────────
        // ABIs to build the native FreeRDP bridge for.
        // arm64-v8a   : modern 64-bit ARM devices.
        // armeabi-v7a : older / budget 32-bit ARM devices — added so the app
        //               installs and runs the native FreeRDP path on them too.
        //               Requires a 32-bit ARM build of the FreeRDP + OpenSSL
        //               prebuilt libraries (see freerdp-prebuilt/armeabi-v7a
        //               and ANDROID_OPENSSL_ROOT/openssl-armeabi-v7a in CI).
        // x86_64 / x86: 64-bit and 32-bit emulators (x86 also covers a small
        //               number of legacy Intel Atom tablets).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        // CRASH-REPORTING FIX: DSN is not a secret in the same sense as a signing
        // key (it's meant to be embedded in the shipped APK — Sentry's own docs
        // say so), but it's still project-specific and shouldn't be hardcoded in
        // source. Same pattern as the signing secrets in SECURITY.md: read from
        // an env var (CI) or a Gradle property (local `sentry.dsn=` in
        // ~/.gradle/gradle.properties, never committed). Empty string if unset —
        // SystemsGoApp.kt checks for that and skips Sentry.init() entirely, so a
        // build with no DSN configured just runs with crash reporting off
        // instead of crashing on a blank DSN.
        val sentryDsn = System.getenv("SENTRY_DSN")
            ?: (project.findProperty("sentry.dsn") as String? )
            ?: ""
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")

        // CLOUD-SYNC-CONFIG FIX: same externalized-config pattern as SENTRY_DSN
        // above — read from an env var (CI) or a Gradle property (local
        // `google.drive.client.id=` / `dropbox.app.key=` in
        // ~/.gradle/gradle.properties, never committed to this repo) instead of
        // a hardcoded literal in source. Placeholder default keeps matching the
        // "TODO_" prefix that GoogleDriveSyncProvider.isConfigured /
        // DropboxSyncProvider.isConfigured already check for, so a build with
        // no key configured still just disables the "Connect" button with an
        // explanatory message instead of failing at build time or crashing at
        // runtime — nothing else needs to change to fill these in later, just
        // set the property/env var and rebuild.
        val googleDriveClientId = System.getenv("GOOGLE_DRIVE_WEB_CLIENT_ID")
            ?: (project.findProperty("google.drive.client.id") as String?)
            ?: "TODO_REPLACE_WITH_WEB_OAUTH_CLIENT_ID.apps.googleusercontent.com"
        buildConfigField("String", "GOOGLE_DRIVE_WEB_CLIENT_ID", "\"$googleDriveClientId\"")

        val dropboxAppKey = System.getenv("DROPBOX_APP_KEY")
            ?: (project.findProperty("dropbox.app.key") as String?)
            ?: "TODO_REPLACE_WITH_DROPBOX_APP_KEY"
        buildConfigField("String", "DROPBOX_APP_KEY", "\"$dropboxAppKey\"")

        // The Dropbox PKCE redirect URI's scheme must literally be
        // "db-<APP_KEY>" (see DropboxSyncProvider's APP_KEY doc) and
        // AndroidManifest.xml can't read BuildConfig fields directly, so this
        // manifest placeholder is what keeps the two from drifting apart —
        // change dropboxAppKey above (via property/env var) and this follows
        // automatically; nothing in the manifest itself needs editing.
        manifestPlaceholders["dropboxRedirectScheme"] = "db-$dropboxAppKey"

        // عندما تُبنى FreeRDP فعلياً (submodule موجود)، find_package(OpenSSL)
        // التقليدي في CMake يبحث في مسارات النظام (Linux/host) بدل sysroot
        // الخاص بـ NDK، فيجد OpenSSL غير متوافق مع target Android ويفشل
        // تكوين CMake بالكامل (هذا سبب فشل configureCMakeDebug في الـ CI).
        // الحل: نمرر جذر OpenSSL المخصص لأندرويد (مبني مسبقاً في CI عبر
        // سكربت FreeRDP الرسمي android-build-openssl.sh، انظر main.yml)
        // إلى CMake عبر متغير البيئة ANDROID_OPENSSL_ROOT.
        // نمرر ANDROID_OPENSSL_ROOT فقط — CMakeLists.txt يحتسب المسار الكامل لكل ABI.
        // FreeRDP يُبنى كـ prebuilt منفصل في CI (انظر main.yml)، ولا يُبنى داخل Gradle.
        val androidOpenSslRoot = System.getenv("ANDROID_OPENSSL_ROOT")
        if (!androidOpenSslRoot.isNullOrBlank()) {
            externalNativeBuild {
                cmake {
                    arguments += "-DANDROID_OPENSSL_ROOT=$androidOpenSslRoot"
                }
            }
        }
    }

    // ── Native build via CMake ────────────────────────────────────────────────
    // DOC FIX: this comment previously described a "FreeRDP submodule" check
    // and claimed VNC always throws VncNotImplementedException — neither is
    // true. CMakeLists.txt guards against missing *prebuilt* FreeRDP/OpenSSL
    // libraries under app/src/main/cpp/freerdp-prebuilt/<ABI>/ (built by CI or
    // manually — see app/src/main/cpp/SETUP.md), not a source submodule; if
    // they're absent for a given ABI it prints a warning and skips the native
    // build for that ABI only. SSH (JSch, pure Kotlin/Java) and VNC
    // (com.undatech.opaque.RfbConnectable, pure Kotlin RFB client) are
    // unaffected either way — neither needs any native build. RDP does NOT
    // have a Kotlin fallback — see RdpRemoteAdapter.kt ("the pure-Kotlin
    // hand-written RDP parser has been removed; FreeRDP is the only supported
    // backend"); without the native .so, RDP connections fail with an error
    // directing the user to app/src/main/cpp/SETUP.md.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // ── ABI-SPLIT: 3 APKs من بناء Gradle واحد ───────────────────────────────
    // ينتج هذا البلوك تلقائياً (داخل app/build/outputs/apk/<type>/):
    //   1) app-universal-<type>.apk      → armeabi-v7a + arm64-v8a مدموجتان معاً
    //   2) app-armeabi-v7a-<type>.apk    → armeabi-v7a فقط (أصغر حجماً)
    //   3) app-arm64-v8a-<type>.apk      → arm64-v8a فقط (أصغر حجماً)
    // x86/x86_64 (المحاكي) تبقى مبنية للتطوير المحلي لكن بلا split خاص بها —
    // ستكون موجودة فقط داخل الـ universal APK إن وُجدت مكتباتها الأصلية.
    //
    // ملاحظة عن versionCode: لم أضِف override تلقائي للـ versionCode لكل
    // split (Gradle/AGP يوفر ذلك عبر internal API غير مضمون الاستقرار بين
    // إصدارات AGP). إن كنت ستوزّع الـ 3 APKs على متجر يفرض versionCode فريد
    // لكل ملف، فاضبطه يدوياً (مثلاً عبر CI: armeabi-v7a=1xxx, arm64-v8a=2xxx,
    // universal=3xxx) — أخبرني إن أردت ذلك وسأضيفه باستخدام طريقة مضمونة
    // أكثر (post-processing على اسم ملف APK الناتج بدل internal AGP classes).
    splits {
        abi {
            isEnable = true
            reset()                                  // تجاهل أي قائمة افتراضية سابقة
            include("armeabi-v7a", "arm64-v8a")      // الـ splits المطلوبة فقط
            isUniversalApk = true                     // + نسخة مدموجة لكل المعماريات
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // SIZE FIX: never package native (.so) debug symbols in the release
            // APK. AGP already strips them by default, but pinning this
            // explicitly guards against a future AGP default change silently
            // bloating release builds with FreeRDP/OpenSSL debug symbols.
            ndk {
                debugSymbolLevel = "NONE"
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // bcprov-jdk18on و jsch كلاهما يشحن نفس مسار MANIFEST.MF
            // داخل multi-release jar (versions/9 و versions/11)، ما يسبب
            // فشل mergeDebugJavaResource. هذا الملف مجرد بيان OSGi
            // وغير ضروري وقت التشغيل على أندرويد، فنستثنيه بالكامل
            // بدلاً من استثناء نسخة واحدة فقط.
            excludes += "META-INF/versions/*/OSGI-INF/MANIFEST.MF"

            // ── SIZE FIX: استثناء ملفات لا قيمة لها وقت التشغيل على أندرويد
            // لكنها تُشحن افتراضياً من تبعيات JSch/BouncyCastle/Kotlin coroutines
            // وتضيف عشرات/مئات الـ KB بلا أي فائدة فعلية للمستخدم النهائي.
            excludes += listOf(
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "kotlin/**",
                "DebugProbesKt.bin",
                "META-INF/com.android.tools/**",
            )
        }
        jniLibs {
            // SIZE FIX: AGP's default (useLegacyPackaging = false, available since
            // AGP 8 when minSdk >= 23) stores native .so files *uncompressed* and
            // page-aligned inside the APK so they can be mmap()'d directly from the
            // zip at install — this trades a noticeably larger download size for
            // faster install/startup. For this app the native footprint
            // (FreeRDP + OpenSSL across up to 4 ABIs) is the single biggest
            // contributor to APK size, so we deliberately go back to legacy
            // (compressed) packaging to minimise the download size users (and
            // GitHub Release bandwidth) actually pay for — the extraction-on-install
            // cost is a one-time, sub-second cost on any device this app targets.
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        // All detectors below crash with IncompatibleClassChangeError because
        // the Kotlin Analysis API interfaces they depend on changed from classes
        // to interfaces between the Kotlin version used to compile lint jars
        // and the one used by the compiler plugin. Disabling them globally
        // until the upstream libraries ship updated lint artifacts.
        disable += setOf(
            // ── androidx.compose.runtime lint ─────────────────────────────────
            // All detectors below crash with IncompatibleClassChangeError because
            // the Kotlin Analysis API changed classes to interfaces between the
            // version used to compile the lint JARs and the active compiler plugin.
            // Disabling the entire compose.runtime.lint suite until upstream ships
            // updated artifacts.
            "AutoboxingStateCreation",
            "ComposableLambdaParameterNaming",
            "ComposableLambdaParameterPosition",
            "CompositionLocalNaming",
            "CoroutineCreationDuringComposition",
            "FlowOperatorInvokedInComposition",
            "FrequentlyChangingValue",
            "ProduceStateDoesNotAssignValue",
            "RememberInComposition",
            "RememberReturnType",
            "UnrememberedAnimatable",
            "UnrememberedMutableInteractionSource",
            "UnrememberedMutableState",
            // ── androidx.compose.ui lint ──────────────────────────────────────
            "ModifierFactoryExtensionFunction",
            "ModifierFactoryReturnType",
            "ModifierFactoryUnreferencedReceiver",
            "ModifierNodeInspectableProperties",
            "SuspiciousCompositionLocalModifierRead",
            "UnnecessaryCompositionLocalUsage",
            // ── androidx.compose.foundation lint ─────────────────────────────
            "FrequentlyChangedStateReadInComposition",
            // ── androidx.navigation.compose lint ─────────────────────────────
            "ComposableDestinationInComposeNavigator",
            // ── androidx.lifecycle lint ───────────────────────────────────────
            "NullSafeMutableLiveData",
            "StateFlowValueCalledInComposition",
            "LifecycleWhenChecks",
        )
    }
}

// ── PERF: Compose compiler diagnostics ──────────────────────────────────
// المشكل: كل التوصيات ديال "رتّب الدوال/الملفات باش تولّي fluid" اللي
// كتقراها فالمقالات (List<T> غير stable، unstable lambdas، إلخ) خمّنية —
// بلا بيانات حقيقية من الكومبايلر ديال هاد المشروع بالضبط، أي تعديل يدوي
// عشوائي فـ 37 ألف سطر كود احتمال يبدّل حاجة ماشي هي المشكلة الحقيقية،
// أو حتى يخرب حاجة خدّامة.
//
// هذا البلوك كيخلي Kotlin Compose compiler (2.1.21) يطلّع ملفين حقيقيين
// عند كل build: build/compose_metrics (ملخص عددي: كم composable stable/
// unstable، كم skippable) وbuild/compose_compiler_reports (تفصيل composable
// composable — بالضبط وين موجودة unstable parameters وليش). هادو كيبانو
// أوتوماتيك أي مشكلة recomposition حقيقية موجودة فـ HomeScreen.kt (3658
// سطر) أو غيرها — بدل التخمين. Strong Skipping Mode مفعّل افتراضياً مع
// Kotlin 2.x's Compose compiler Gradle plugin (منذ Compose compiler
// 1.5.4+) — هذا فقط يثبّته صراحة كي ما يتبدّلش صدفة بترقية مستقبلية.
composeCompiler {
    featureFlags = setOf(
        org.jetbrains.kotlin.compose.compiler.gradle.ComposeFeatureFlag.StrongSkipping
    )
    metricsDestination = layout.buildDirectory.dir("compose_metrics")
    reportsDestination = layout.buildDirectory.dir("compose_compiler_reports")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    // ENTRA-ID-AUTH FEATURE: MSAL for Android — Microsoft's official library
    // for signing in against Microsoft Entra ID. See EntraIdAuthManager.kt.
    implementation(libs.msal)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // AUTO-LOCK-FIX: ProcessLifecycleOwner for real app-level foreground/background detection.
    implementation(libs.androidx.lifecycle.process)
    // PERF-FIX: enables collectAsStateWithLifecycle() across all screens.
    implementation(libs.androidx.lifecycle.runtime.compose)
    // PERF-FIX (Baseline Profile): يثبّت أي baseline profile — سواء المولَّد
    // من :baselineprofile أو تلك المرفقة أصلاً داخل مكتبات AndroidX/Compose
    // نفسها (Jetpack Compose يشحن profile rules خاصة به) — على جهاز
    // المستخدم عبر ART. بدون هذه المكتبة، أي baseline-prof.txt (حتى لو
    // موجود في الـ APK) لن يُستخدم إطلاقاً على أغلب الأجهزة.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    // CLOUD-SYNC (الجزء 3/3): @HiltWorker لـ CloudSyncWorker — يحتاج مُصنِّف
    // androidx.hilt المنفصل بالإضافة إلى hilt.compiler (com.google.dagger) أعلاه.
    implementation(libs.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Room Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    // HIGH-3 FIX: SQLCipher — AES-256 encryption for the Room database at rest.
    // Pass SupportFactory(passphrase) to Room.databaseBuilder().openHelperFactory().
    implementation(libs.sqlcipher)
    // 🔴 CRITICAL FIX: net.zetetic:sqlcipher-android does not transitively pull in
    // androidx.sqlite — it must be declared explicitly or SupportFactory fails to resolve.
    implementation(libs.androidx.sqlite)
    // MED-2 FIX: EncryptedFile for AES-256-GCM screenshot thumbnail encryption
    implementation(libs.security.crypto)

    // DataStore
    implementation(libs.datastore.preferences)

    // Coroutines
    implementation(libs.coroutines.android)

    // Image Loading
    implementation(libs.coil.compose)

    // Lottie Animation
    implementation(libs.lottie.compose)

    // Gson
    implementation(libs.gson)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Splash Screen
    implementation(libs.splashscreen)

    // CRASH-REPORTING FIX: was completely absent — see HexApplication.kt for
    // init + the PII-scrubbing beforeSend hook, and SENTRY_SETUP.md for the DSN.
    implementation(libs.sentry.android)

    // BouncyCastle for TLS/NLA
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bctls)
    // FIX-HTTPS: bcpkix needed for JcaX509v3CertificateBuilder (self-signed cert for HTTPS server)
    implementation(libs.bouncycastle.bcpkix)

    // JSch — pure-Java SSH2 client
    implementation(libs.jsch)
    // FTP-FEATURE: Apache Commons Net — FTP/FTPS/TFTP client support
    implementation(libs.commons.net)
    // SMB-FEATURE: SMB2/SMB3 client (jcifs-ng)
    implementation(libs.jcifs.ng)
    // WEBDAV-FEATURE: WebDAV client (sardine-android)
    implementation(libs.sardine.android)
    // RDP-OVER-WEBSOCKET FEATURE: RdpWebSocketTransport.kt's RFC 6455 client —
    // see okhttp's version comment in gradle/libs.versions.toml for why this
    // is pinned explicitly instead of relying on sardine-android's transitive
    // version.
    implementation(libs.okhttp)

    // PAC-SUPPORT FEATURE: Rhino — pure-JVM JS engine for PacFileParser's
    // FindProxyForURL() execution (see gradle/libs.versions.toml "rhino"
    // entry for why this library/version).
    implementation(libs.rhino)

    // Biometric — مصادقة بصمة الإصبع ورمز الجهاز (BiometricManager/BiometricPrompt)
    implementation(libs.androidx.biometric)

    // QR-SCANNER-REDESIGN: "Scan QR Code" option in the new-connection chooser
    // (see AddOptionsDialog in Components.kt) is now a custom Compose screen
    // (QrScannerActivity) instead of zxing-android-embedded's built-in
    // CaptureActivity, so it can offer a front/back camera flip button, a
    // custom rounded viewfinder design, and scanning a QR out of a picked
    // gallery photo — see QrScannerActivity.kt for details.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    // QR-SHARE FEATURE: encodes a connection profile back into a QR bitmap
    // ("Share via QR" — the missing reverse direction of QR-SCANNER-REDESIGN
    // above). zxing-core is a small pure-Java module (no Android/camera code,
    // unlike zxing-android-embedded which QR-SCANNER-REDESIGN already moved
    // away from) — only its QRCodeWriter/BitMatrix encoder is used, from
    // ShareConnectionQrDialog in Components.kt.
    implementation(libs.zxing.core)

    // CLOUD-SYNC FEATURE (Part 1/3): dependencies for the "Sync connections
    // to Google Drive / Dropbox" feature (Settings → Cloud Sync). Nothing in
    // Part 1's own code imports these yet — CloudSyncProvider.kt is a plain
    // interface with no provider implementation behind it until Part 2 —
    // but they're added now so Part 2 can start writing
    // GoogleDriveSyncProvider/DropboxSyncProvider directly instead of first
    // having to come back and edit this file.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.google.api.client)
    implementation(libs.google.api.client.android)
    implementation(libs.google.api.services.drive)
    implementation(libs.dropbox.android.sdk)

    // bVNC (com.undatech.opaque) — استُعيض عنها بـ local stubs في:
    // app/src/main/java/com/undatech/opaque/
    // السبب: بناء bVNC عبر JitPack يفشل لأنها تعتمد على NDK+FreeRDP+sqlcipher prebuilt.

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// ════════════════════════════════════════════════════════════════════
// PERF-FIX: Baseline Profile — إعداد استهلاك الملف داخل :app
// ════════════════════════════════════════════════════════════════════
// المصدر: developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles
//
// - automaticGenerationDuringBuild = false عمداً: توليد الملف يتطلب تشغيل
//   instrumentation test حقيقي على جهاز/محاكي متصل (لا يمكن أن يحدث تلقائياً
//   داخل CI عادي بدون جهاز). لو فعّلناها true، كل ./gradlew assembleRelease
//   سيحاول تشغيل اختبار مُجهزَة (instrumented) ويفشل في أي بيئة بدون جهاز.
// - لتوليد الملف فعلياً (مرة واحدة، أو كلما تغيّرت مسارات التنقل الأساسية
//   بشكل كبير): من Android Studio مع جهاز حقيقي متصل (أو Android 13+ بدون
//   root)، شغّل Gradle task التالية من الطرفية:
//       ./gradlew :app:generateReleaseBaselineProfile
//   أو ببساطة اضغط ▶ على BaselineProfileGenerator.generate() في
//   baselineprofile/src/main/java/.../BaselineProfileGenerator.kt من داخل
//   Android Studio نفسها. الناتج يُحفظ تلقائياً في
//   app/src/main/generated/baselineProfiles/baseline-prof.txt ويُحزَّم في
//   أي release APK لاحق تلقائياً — بدون أي خطوة يدوية أخرى.
baselineProfile {
    // نولّد فقط على متغيّر release (المطابق لما يستلمه المستخدم فعلياً من
    // Play/الـ APK النهائي) — لا داعي لبروفايل لـ debug.
    automaticGenerationDuringBuild = false
    // ملاحظة: useConnectedDevices ليست خاصية على extension الـ"consumer" هنا
    // في app — هي فقط على extension الـ"producer" داخل موديول
    // :baselineprofile (انظر baselineprofile/build.gradle.kts:85 حيث هي
    // مضبوطة فعلاً بشكل صحيح). وجودها هنا كان يسبب خطأ
    // "Unresolved reference: useConnectedDevices".
}

// ════════════════════════════════════════════════════════════════════
// CRIT-3-B FIX: Raw-socket coverage gate (network_security_config.xml
// scope gap for Telnet/VNC/RDP/IPMI/AMT/SNMP/rlogin/Mosh/NFS/serial).
// ════════════════════════════════════════════════════════════════════
// network_security_config.xml (app/src/main/res/xml/) only ever governs
// Android's HTTP stack — HttpURLConnection/HttpsURLConnection, WebView,
// and anything that consults android.security.NetworkSecurityPolicy
// (OkHttp does). It cannot see, and therefore cannot enforce anything
// about, a directly-instantiated java.net.Socket/DatagramSocket/
// ServerSocket — which is exactly how every non-HTTP remote-access
// protocol this app implements (Telnet, VNC, RDP's native FreeRDP
// bridge, IPMI, AMT SOL/IDER/KVM, SNMP, rlogin, Mosh, the serial
// console, NFS/ONC-RPC) talks to the network. That's a platform
// limitation, not a bug an XML edit can close — see the SCOPE NOTE at
// the top of network_security_config.xml itself.
//
// The actual, buildable fix: tools/verify_socket_security.py cross-
// checks every raw-socket call site under app/src/main/java against
// tools/socket_security_manifest.json, where each one is on record with
// a reviewed justification (protocol-inherent cleartext, TOFU-wrapped
// TLS, loopback-only, own crypto layer, etc.). Any new, unreviewed raw
// socket call site — a regression, a copy-pasted protocol, a refactor
// that drops a TLS wrap — now fails the build instead of silently
// shipping unguarded, since there is no NetworkSecurityPolicy signal
// that would ever have caught it.
val verifySocketSecurity by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fails the build if any raw Socket/DatagramSocket/ServerSocket " +
        "construction in app/src/main/java is not accounted for in " +
        "tools/socket_security_manifest.json (see CRIT-3-B comment above)."
    workingDir = rootProject.projectDir
    commandLine("python3", "tools/verify_socket_security.py")
}

tasks.named("check") {
    dependsOn(verifySocketSecurity)
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(verifySocketSecurity)
}

