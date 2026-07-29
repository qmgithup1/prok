package com.systemsgo.hex.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

/**
 * SECURITY: reference-counted FLAG_SECURE controller.
 *
 * FLAG_SECURE tells the platform compositor to blank this window out of
 * every *system-level* capture path: the OS screenshot mechanism, the
 * Recents/App-Switcher live thumbnail, AND — because both draw from the
 * same protected compositor surface — the MediaProjection API that
 * third-party screen-recording apps rely on. A malicious app holding a
 * MediaProjection grant simply gets a black frame for as long as this flag
 * is set, regardless of what's actually on screen underneath (a PIN pad,
 * tap positions on it, a typed password, etc.).
 *
 * A plain "set true / set false" per screen isn't enough because a single
 * window can have more than one sensitive field visible at once (e.g. an
 * RDP profile editor showing both a login password and a gateway password
 * together) — clearing the flag when *one* of them leaves composition would
 * wrongly expose the other. Counting requests per Activity fixes that:
 * protection is only released once every caller that asked for it has left
 * composition.
 */
private val secureRequestCounts = WeakHashMap<Activity, Int>()

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun applySecure(activity: Activity, secure: Boolean) {
    if (secure) {
        activity.window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    } else {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/**
 * Marks the current screen as sensitive for as long as this composable
 * stays in composition. While active it sets FLAG_SECURE on the hosting
 * Activity's window (see class doc above for exactly what that blocks),
 * and clears it again the moment the last caller leaves composition.
 *
 * Deliberately scoped, not global: SystemsGo is a remote-desktop/SSH/VNC
 * client, and users rely on being able to screenshot or screen-record their
 * *own* RDP/VNC/SSH session content (that's the whole point of BUGFIX #5 in
 * MainActivity/RdpSessionActivity — see those comments). This helper is
 * only ever called around the app's own sensitive input surfaces — the PIN
 * lock/setup screens and password/passphrase fields — never around session
 * content, so normal screenshot/recording of a live session is unaffected.
 *
 * Safe to call from multiple composables in the same window simultaneously
 * (reference-counted) and safe to call with a toggling [active] value.
 */
@Composable
fun SecureScreen(active: Boolean = true) {
    val context = LocalContext.current
    DisposableEffect(active) {
        val activity = if (active) context.findActivity() else null
        if (activity == null) {
            onDispose { }
        } else {
            synchronized(secureRequestCounts) {
                val count = (secureRequestCounts[activity] ?: 0) + 1
                secureRequestCounts[activity] = count
                if (count == 1) applySecure(activity, true)
            }
            onDispose {
                synchronized(secureRequestCounts) {
                    val count = (secureRequestCounts[activity] ?: 1) - 1
                    if (count <= 0) {
                        secureRequestCounts.remove(activity)
                        applySecure(activity, false)
                    } else {
                        secureRequestCounts[activity] = count
                    }
                }
            }
        }
    }
}
