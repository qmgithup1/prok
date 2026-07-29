package com.systemsgo.hex

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.systemsgo.hex.cloudsync.CloudSyncScheduler
import com.systemsgo.hex.data.repository.CloudSyncPreferences
import com.systemsgo.hex.data.repository.ConnectionLogRepository
import com.systemsgo.hex.data.repository.RdpProfileRepository
import com.systemsgo.hex.security.openEncryptedPrefs
import dagger.hilt.android.HiltAndroidApp
import io.sentry.android.core.SentryAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class SystemsGoApp : Application(), Configuration.Provider {

    // PERF-FIX (startup jank): these used to be injected as plain
    // `lateinit var` fields. Hilt performs Application field injection
    // synchronously inside super.onCreate() below — i.e. on the MAIN THREAD,
    // before MainActivity even exists. Because RdpProfileRepository and
    // ConnectionLogRepository ultimately depend on the SystemsGoDatabase
    // singleton (AppModule.provideDatabase()), and that provider forces the
    // SQLCipher-encrypted database open *eagerly and synchronously*
    // (DatabaseOpenRecovery.openWithRecovery() calls
    // `database.openHelper.writableDatabase` before returning) — including a
    // real Android Keystore round-trip plus SQLCipher's PBKDF2 key
    // derivation — the very first field injection here used to block the
    // main thread for the entire DB-open+decrypt cost before a single frame
    // of UI could be produced. That is the actual "loading" moment users
    // feel at cold start.
    //
    // Wrapping them in dagger.Lazy<T> makes the field injection itself
    // instant (it just wraps an unstarted Provider — no database work
    // happens yet). We then call .get() ourselves, explicitly, from inside
    // appScope (Dispatchers.IO) below, so the expensive singleton
    // construction — and therefore the SQLCipher open — happens on a
    // background thread instead. Because SystemsGoDatabase is still a
    // @Singleton, this also *warms* it: by the time MainActivity's
    // MainViewModel asks for the same repositories a few dozen ms later on
    // the main thread, the singleton is either already built (instant) or
    // Dagger's DoubleCheck lock makes the main thread wait for the
    // background thread to finish rather than doing the work itself either
    // way, the main thread is never the one paying the SQLCipher cost.
    @Inject lateinit var connectionLogRepository: dagger.Lazy<ConnectionLogRepository>
    // FIX B3: نحتاج إلى RdpProfileRepository لإعادة تهيئة حالة الاتصال عند بدء التطبيق
    @Inject lateinit var profileRepository: dagger.Lazy<RdpProfileRepository>

    // CLOUD-SYNC FEATURE (Part 3/3): required so WorkManager can construct
    // CloudSyncWorker (a real @HiltWorker with an injected CloudSyncManager
    // dependency) instead of failing with "no default constructor" the way
    // reflection-based instantiation would. See getWorkManagerConfiguration()
    // below and CloudSyncWorker's doc comment for why this wasn't needed by
    // the two pre-existing workers.
    @Inject lateinit var hiltWorkerFactory: HiltWorkerFactory
    @Inject lateinit var cloudSyncPreferences: CloudSyncPreferences

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // CRASH-REPORTING FIX: was entirely absent — every crash a real user hit
    // after Google Play release was invisible unless they filed a support
    // ticket. Initialized first, before super.onCreate(), so it's active for
    // the earliest possible crash (including one thrown by the locale/prefs
    // block right below).
    //
    // Why a beforeSend scrubber is mandatory here (not just nice-to-have):
    // this app's crash context is unusually sensitive for a Sentry integration
    // — RdpProfile/VncCredentials/SSH fields, hostnames, usernames, and
    // exception messages from FreeRDP/JSch/BouncyCastle can legitimately
    // contain a password, private key material, or a passphrase in the
    // message text itself (e.g. a JSch auth-failure exception embeds the
    // attempted username). sendDefaultPii defaults to false (device
    // identifiers/IP are not attached), but that alone does nothing about
    // secrets living inside breadcrumb/exception *message strings* — so those
    // are redacted here before every event leaves the device.
    private fun initCrashReporting() {
        if (BuildConfig.SENTRY_DSN.isBlank()) return // not configured — stay off, don't crash on a blank DSN
        try {
            SentryAndroid.init(this) { options ->
                options.dsn = BuildConfig.SENTRY_DSN
                options.environment = if (BuildConfig.DEBUG) "debug" else "release"
                options.isSendDefaultPii = false
                // Session Replay / screenshot attachment would capture on-screen
                // credential fields and live RDP/VNC frame buffers — never enable.
                options.isAttachScreenshot = false
                options.isAttachViewHierarchy = false
                options.tracesSampleRate = 0.1
                options.beforeSend = io.sentry.SentryOptions.BeforeSendCallback { event, _ ->
                    scrubSecrets(event)
                    event
                }
                options.beforeBreadcrumb = io.sentry.SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ ->
                    breadcrumb.message = breadcrumb.message?.let(::redact)
                    breadcrumb
                }
            }
        } catch (_: Exception) {
            // Best-effort, same as the locale block below — crash reporting
            // failing to init must never itself crash the app.
        }
    }

    /** Very small denylist-pattern redactor — not a substitute for keeping secrets
     *  out of exception messages in the first place, but a last line of defense. */
    private fun redact(text: String): String {
        var out = text
        // password=..., pass:..., pwd=... up to the next whitespace/quote
        out = Regex("(?i)(pass(word)?|pwd)\\s*[:=]\\s*\\S+").replace(out, "$1=[redacted]")
        out = Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----")
            .replace(out, "[redacted-private-key]")
        return out
    }

    private fun scrubSecrets(event: io.sentry.SentryEvent) {
        event.message?.formatted = event.message?.formatted?.let(::redact)
        // Throwable.message itself is read-only in Kotlin/Java, but Sentry
        // serializes each exception's message into event.exceptions[].value,
        // which IS mutable — redact it there before the event is sent.
        event.exceptions?.forEach { it.value = it.value?.let(::redact) }
    }

    override fun onCreate() {
        initCrashReporting()
        // LANG-FLASH-FIX: previously the saved language was only applied from
        // inside MainActivity's Compose tree, in a LaunchedEffect that only
        // runs *after* settingsRepository.settingsFlow has emitted (async).
        // That meant every cold start (and every Activity recreate) briefly
        // rendered the very first frame — including the PIN lock screen —
        // in whatever locale the system/AppCompatDelegate already had
        // (often the device's system language), before "remembering" the
        // saved language a moment later and visibly switching. Applying it
        // here, synchronously, before super.onCreate() and therefore before
        // any Activity (or the splash screen) is created, means the correct
        // locale is already active for the first frame every single time —
        // no flash, and no longer any need for a locale-triggered recreate
        // on cold start. The same fast EncryptedSharedPreferences store used
        // for the dark-mode-flash fix in MainActivity is used here for the
        // same reason: it's a synchronous read cheap enough for this point
        // in the app lifecycle, unlike the old async DataStore.
        try {
            val language = openEncryptedPrefs("systemsgo_settings").getString("language", "system") ?: "system"
            val locales = if (language == "system")
                LocaleListCompat.getEmptyLocaleList()
            else
                LocaleListCompat.forLanguageTags(language)
            AppCompatDelegate.setApplicationLocales(locales)
        } catch (_: Exception) {
            // Best-effort — falls back to whatever locale AppCompatDelegate/
            // the system already has (e.g. first run, corrupt Keystore).
        }
        super.onCreate()
        // DELAYED-RESET FEATURE: the system clears all notifications on
        // reboot, but a pending "Reset Application Data" schedule (persisted
        // by DataResetManager, and the underlying WorkManager job that
        // performs the actual wipe) survives it. Re-show the persistent
        // countdown notification here so the user still sees it after a
        // reboot or any other process restart while a reset is pending.
        com.systemsgo.hex.security.DataResetManager.repostNotificationIfScheduled(this)
        // CLOUD-SYNC FEATURE (Part 3/3): a single long-lived collector for
        // the app's entire process lifetime, not a one-shot read at
        // startup — this is what makes toggling auto-sync (or changing its
        // interval, or disconnecting) in Settings take effect immediately.
        // collectLatest (not collect) so a settings change that arrives
        // while CloudSyncScheduler.applySettings() from the *previous*
        // emission is still running cancels that stale call first —
        // applySettings() only does synchronous WorkManager enqueue/cancel
        // calls today, but this keeps a future suspending version safe by
        // construction rather than by convention.
        appScope.launch {
            cloudSyncPreferences.settingsFlow.collectLatest { settings ->
                CloudSyncScheduler.applySettings(this@SystemsGoApp, settings)
            }
        }
        appScope.launch {
            // PERF-FIX: .get() is where the actual (Lazy-deferred) Dagger
            // singleton construction happens — this is the line that now
            // pays the SQLCipher database-open cost, on Dispatchers.IO,
            // instead of that cost landing on the main thread back in
            // SystemsGoApp's field-injection step above.
            val profiles = profileRepository.get()
            val connectionLogs = connectionLogRepository.get()
            // FIX B3: إعادة تعيين isConnected=false لجميع البطاقات عند بدء التطبيق.
            // بدون هذا تظل البطاقات مُعلَّمة كـ "متصل" بعد أي كراش أو إغلاق مفاجئ.
            profiles.resetAllConnectionStates()
            // تنظيف سجلات الاتصال المعلقة وحذف الإدخالات القديمة (أكثر من 30 يوماً)
            connectionLogs.closeOrphanedLogs()
            connectionLogs.purgeOld()
            // CALL-HOME FEATURE (RFC 8071, Part 12): a device can call home
            // at any time, independent of whether the user has this app's
            // UI open — the listener has to already be running by the time
            // that happens, not started reactively when a Session screen
            // opens. See NetconfCallHomeService.ensureRunningIfNeeded's doc
            // comment for why this is a cheap no-op on every cold start
            // that doesn't use Call Home at all.
            com.systemsgo.hex.netconf.service.NetconfCallHomeService.ensureRunningIfNeeded(
                this@SystemsGoApp, profiles, appScope,
            )
        }
    }

    // BUG-L FIX: Implement onTrimMemory so the OS can reclaim the large-heap
    // double-buffer bitmaps (up to 15 MB per session) on low-memory devices
    // (2-3 GB RAM). Without this, the OOM Killer terminates the app silently.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            // Notify active sessions to release non-visible frame buffers.
            // The bitmaps will be re-allocated on the next frame update.
            TrimMemoryBus.notifyTrim(level)
        }
    }

    // CLOUD-SYNC FEATURE (Part 3/3): implementing Configuration.Provider is
    // picked up automatically by WorkManager's own androidx-startup
    // initializer (no AndroidManifest change needed to disable the default
    // one) — from this point on, every WorkManager.getInstance(context) call
    // anywhere in the app (including the pre-existing DataResetWorker/
    // NotificationUpdateWorker call sites in DataResetManager) is built with
    // this Configuration. hiltWorkerFactory only knows how to construct
    // @HiltWorker classes (currently just CloudSyncWorker); for any other
    // worker class it delegates back to WorkManager's default
    // reflection-based factory, so those two existing workers are
    // unaffected by this change.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(hiltWorkerFactory)
            .build()
}