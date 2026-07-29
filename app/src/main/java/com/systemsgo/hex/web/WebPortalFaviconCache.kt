package com.systemsgo.hex.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * WEB-PORTAL-FAVICON FEATURE: small disk cache of each Web-portal profile's
 * favicon, captured via WebPortalActivity's WebChromeClient.onReceivedIcon
 * and shown by ProtocolIconBadge (Components.kt) on the connection card in
 * place of the generic [com.systemsgo.hex.data.model.ProtocolType.WEB] icon,
 * once one has actually been captured — mirroring how a real browser's tab
 * strip/bookmark bar shows a site's own favicon rather than a generic globe.
 *
 * Deliberately a plain on-disk file cache keyed by profile id, *not* a new
 * RdpProfile/Room column: this is regenerable presentation data (re-fetched
 * for free the next time the portal is opened), not something a user
 * configures or that needs to survive a profile export/cloud-sync round
 * trip, so it doesn't carry the migration weight [RdpProfile]'s other
 * WEB-PORTAL fields do.
 *
 * [faviconVersion] is a cheap invalidation signal ProtocolIconBadge collects
 * so a freshly-captured icon shows up next time the connection list
 * recomposes, without plumbing a full reactive per-file StateFlow.
 */
object WebPortalFaviconCache {

    private val memoryCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

    private val _faviconVersion = MutableStateFlow(0)
    /** Bumped every time [save] writes a new favicon — observe to know when to re-check [get]. */
    val faviconVersion: StateFlow<Int> = _faviconVersion.asStateFlow()

    private fun dir(context: Context): File =
        File(context.filesDir, "web_favicons").apply { if (!exists()) mkdirs() }

    private fun file(context: Context, profileId: String): File =
        File(dir(context), "$profileId.png")

    /** Synchronous, memory-cached read — safe to call from a composable. Returns null if none captured yet. */
    fun get(context: Context, profileId: String): Bitmap? {
        memoryCache[profileId]?.let { return it }
        val f = file(context, profileId)
        if (!f.exists()) return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath)?.also { memoryCache[profileId] = it }
        } catch (_: Exception) {
            null
        }
    }

    /** Called from WebPortalActivity's WebChromeClient — off the main thread, so a disk write never jank the WebView. */
    suspend fun save(context: Context, profileId: String, icon: Bitmap) {
        withContext(Dispatchers.IO) {
            try {
                FileOutputStream(file(context, profileId)).use { out ->
                    icon.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                memoryCache[profileId] = icon
                _faviconVersion.value++
            } catch (_: Exception) {
                // Best-effort cache — a failed write just means the generic
                // WEB icon keeps showing, same as if none had been captured.
            }
        }
    }

    /** Called when a profile is deleted, so a stale favicon never resurfaces if the id is ever reused. */
    fun clear(context: Context, profileId: String) {
        memoryCache.remove(profileId)
        file(context, profileId).delete()
    }
}
