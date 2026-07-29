package com.systemsgo.hex.util

import java.text.NumberFormat
import java.util.Locale

/**
 * Digit normalization helpers.
 *
 * Many numeric fields in the app (PIN, ports, IP/broadcast addresses, WoL MAC
 * bytes, OTP/TOTP codes, timeouts...) can be typed on an Arabic-locale
 * keyboard, which commonly inserts Eastern Arabic-Indic digits
 * (٠١٢٣٤٥٦٧٨٩ — U+0660..U+0669) or Extended Arabic-Indic / Persian digits
 * (۰۱۲۳۴۵۶۷۸۹ — U+06F0..U+06F9) instead of Western/ASCII digits (0-9).
 *
 * Several validators in this codebase compare against plain ASCII ranges
 * ('0'..'9') or feed straight into APIs that only understand ASCII decimal
 * digits (PBKDF2/PinHasher, InetAddress, Integer.parseInt of a MAC's hex
 * bytes formatted back out, etc). Normalizing at the input boundary — the
 * moment a value lands in a text field's state — keeps every one of those
 * ASCII-only code paths correct without having to special-case Unicode
 * digits in each of them individually.
 */
object DigitUtils {

    // Eastern Arabic-Indic digits used in Arabic locales: ٠١٢٣٤٥٦٧٨٩
    private const val ARABIC_INDIC = "٠١٢٣٤٥٦٧٨٩"

    // Extended Arabic-Indic (Persian/Urdu) digits: ۰۱۲۳۴۵۶۷۸۹
    private const val EXTENDED_ARABIC_INDIC = "۰۱۲۳۴۵۶۷۸۹"

    /**
     * Converts every Arabic-Indic or Extended Arabic-Indic digit in [input]
     * to its Western/ASCII equivalent. Any other character — including plain
     * ASCII digits, letters, and separators like ':', '.', '-' — is left
     * untouched, so this is safe to call on hostnames, IP addresses, MAC
     * addresses, or mixed free text, not just pure numbers.
     */
    fun normalizeDigits(input: String): String {
        if (input.none { it in ARABIC_INDIC || it in EXTENDED_ARABIC_INDIC }) return input
        val sb = StringBuilder(input.length)
        for (c in input) {
            val arabicIdx = ARABIC_INDIC.indexOf(c)
            val extendedIdx = if (arabicIdx < 0) EXTENDED_ARABIC_INDIC.indexOf(c) else -1
            sb.append(
                when {
                    arabicIdx >= 0   -> '0' + arabicIdx
                    extendedIdx >= 0 -> '0' + extendedIdx
                    else             -> c
                }
            )
        }
        return sb.toString()
    }

    /**
     * Formats [value] using the given (or current default) locale's native
     * digit style for display purposes — e.g. Eastern Arabic-Indic digits
     * under an Arabic locale. Internal state, storage, and validation should
     * always keep working on the normalized ASCII form; only use this right
     * before showing a number to the user.
     */
    fun formatForDisplay(value: Long, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getIntegerInstance(locale).format(value)

    fun formatForDisplay(value: Int, locale: Locale = Locale.getDefault()): String =
        formatForDisplay(value.toLong(), locale)
}

/** Convenience extension mirroring [DigitUtils.normalizeDigits]. */
fun String.normalizeDigits(): String = DigitUtils.normalizeDigits(this)
