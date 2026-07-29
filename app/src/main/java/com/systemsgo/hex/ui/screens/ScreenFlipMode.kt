package com.systemsgo.hex.ui.screens

/**
 * TOOLBOX FEATURE (Stage 5): "قلب الشاشة" — how the remote frame is mirrored
 * on the local Android display only. [flipX]/[flipY] are the scale factors
 * (+1f / -1f) applied to the canvas draw transform in [RdpCanvas] — see the
 * `scale(scaleX = scale * flipMode.flipX, scaleY = scale * flipMode.flipY, ...)`
 * call there. A 180° rotation of a flat image is mathematically identical to
 * mirroring both axes at once ((x, y) -> (-x, -y) either way), so [ROTATE_180]
 * simply sets both factors to -1f instead of needing a separate rotation
 * transform.
 *
 * This is a purely local/visual setting — it never changes what coordinates
 * are sent to the remote device (see the flipX/flipY-aware delta inversion
 * in [RdpCanvas]'s pointer-input handling, which keeps clicks/taps mapped to
 * the mathematically correct remote position in all four states).
 */
enum class ScreenFlipMode(val flipX: Float, val flipY: Float) {
    NORMAL(1f, 1f),
    HORIZONTAL(-1f, 1f),   // Mirror: scaleX = -1
    VERTICAL(1f, -1f),     // Mirror: scaleY = -1
    ROTATE_180(-1f, -1f);  // 180° == mirroring both axes at once

    companion object {
        fun fromSetting(raw: String): ScreenFlipMode = when (raw) {
            "horizontal"  -> HORIZONTAL
            "vertical"    -> VERTICAL
            "rotate_180"  -> ROTATE_180
            else          -> NORMAL
        }
    }

    fun toSetting(): String = when (this) {
        NORMAL     -> "normal"
        HORIZONTAL -> "horizontal"
        VERTICAL   -> "vertical"
        ROTATE_180 -> "rotate_180"
    }
}
