package com.systemsgo.hex.security

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DELAYED-RESET FEATURE: runs exactly once, 24 hours after the user confirmed
 * "Reset Application Data" (see DataResetManager.scheduleReset), unless the
 * work was cancelled first via DataResetManager.cancelReset (which requires
 * a successful biometric/PIN confirmation — see DataResetBanner.kt).
 *
 * WorkManager guarantees this runs even across process death / device
 * reboot / Doze, retrying automatically per its default backoff policy if
 * the process is killed mid-run before returning a Result.
 */
class DataResetWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // The notification is dismissed first: clearApplicationUserData()
            // (called inside wipeEverything) also clears it, but doing it
            // explicitly first means the user never sees a stuck "0:00"
            // countdown even in the unlikely case the process dies partway
            // through the wipe below.
            DataResetManager.dismissNotification(applicationContext)
            SecureWipe.wipeEverything(applicationContext)
            Result.success()
        } catch (e: Exception) {
            // Best-effort retry — WorkManager's default exponential backoff
            // applies. The 24h countdown notification intentionally stays
            // visible until the wipe actually succeeds.
            android.util.Log.e("DataResetWorker", "Secure wipe failed, will retry", e)
            Result.retry()
        }
    }
}
