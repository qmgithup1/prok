package com.systemsgo.hex.smartcard

import android.content.Context
import android.util.Log

/**
 * SMARTCARD-REDIRECT FEATURE: the Kotlin half of this app's in-process
 * PC/SC "resource manager" replacement.
 *
 * ## The problem this solves
 * FreeRDP's smart-card channel (WinPR's `smartcard_pcsc.c`) talks to PC/SC
 * by `dlopen`-ing a library it expects to behave like PCSC-lite's
 * `libpcsclite.so` and calling the standard WinSCard API
 * (`SCardEstablishContext`, `SCardConnect`, `SCardTransmit`, ...) on it —
 * see this repo's earlier PCSC-lite cross-compile in `main.yml`. A real
 * PCSC-lite client library, though, still just talks to a separate resource-
 * manager *daemon* (`pcscd`) over a local socket; it doesn't touch hardware
 * itself. Android has no `pcscd` and no root-free way to run one.
 *
 * ## The fix: skip pcscd entirely
 * `app/src/main/cpp/pcsc_shim/pcsc_shim.c` is built as `libpcsclite.so`
 * (matching the SONAME FreeRDP's dlopen call looks for) and implements the
 * WinSCard API surface *directly* — no daemon, no socket, no real
 * PCSC-lite. Each `SCard*` call the shim receives from FreeRDP forwards,
 * via JNI, to a method on this object, which drives whichever
 * [PcscCardReader] is currently active — [UsbCcidReader] over
 * `android.hardware.usb` for a contact reader, or [NfcCcidReader] over the
 * built-in NFC radio for a tapped contactless card — to actually talk to
 * the card. `pcsc_shim.c` itself is entirely transport-agnostic: every
 * `CallStatic*Method` it makes is generic (list/connect/transmit/...), so
 * NFC support (NFC-READER FEATURE) needed zero native/C changes — only
 * this object gained a second way to populate [reader].
 *
 * ## Why this object also loads the shim itself
 * `pcsc_shim.so` is *not* loaded via `System.loadLibrary` by FreeRDP — it's
 * found later via `dlopen("libpcsclite.so")` from WinPR, usually from a
 * native worker thread with no Java call stack. `JNIEnv->FindClass` on such
 * a thread fails to see app classes (a well-known Android JNI pitfall: class
 * lookup without a Java stack frame falls back to the boot classloader).
 *
 * The fix is for *this* object to `System.loadLibrary` the exact same
 * shared object first, from ordinary Kotlin code (a normal JVM thread, full
 * classloader context) via [ensureShimInitialized]. That triggers the
 * shim's own `JNI_OnLoad`, which runs `FindClass`/`GetMethodID` against
 * *this* class successfully and caches the results as C statics. When
 * WinPR's later `dlopen("libpcsclite.so")` resolves to the *same already-
 * loaded module* (dlopen of an already-resident library just bumps its
 * refcount — it doesn't reload it), those cached JNI references are still
 * valid, and the exported `SCard*` symbols can use them regardless of which
 * thread calls them. See `pcsc_shim.c`'s top-of-file comment for the C side
 * of this trick.
 *
 * [ensureShimInitialized] must run before a session with smart-card
 * redirect enabled connects — see `RdpSessionActivity.onCreate`.
 */
object PcscUsbBridge {
    private const val TAG = "PcscUsbBridge"

    @Volatile private var shimLoaded = false
    @Volatile private var reader: PcscCardReader? = null

    /** True while the last-set [reader] was populated via NFC rather than USB — purely informational, used by [nativeListReaders]'s synthetic name. */
    @Volatile private var readerIsNfc = false

    /**
     * Loads `libpcsclite.so` (the shim, not real PCSC-lite — see class doc)
     * so its `JNI_OnLoad` runs now, on this (JVM) thread. Safe to call more
     * than once; idempotent. Call this before connecting a session that has
     * `enableSmartcardRedirect` on — calling it unconditionally on app
     * startup would load a native library every user pays the cost of, for
     * a feature most never enable.
     */
    @Synchronized
    fun ensureShimInitialized() {
        if (shimLoaded) return
        try {
            // "pcsclite" here (System.loadLibrary's short name convention)
            // resolves to the file "libpcsclite.so" — deliberately the
            // exact SONAME real PCSC-lite ships as, and the exact name
            // WinPR's smartcard module dlopen()s later. See
            // CMakeLists.txt's OUTPUT_NAME override for the pcsclite_shim
            // CMake target (its target name doesn't have to match the
            // output filename, and here it intentionally doesn't) and
            // pcsc_shim.c's top-of-file comment for why loading it from
            // here — rather than letting WinPR's dlopen be the first
            // loader — matters.
            System.loadLibrary("pcsclite")
            shimLoaded = true
            Log.i(TAG, "libpcsclite.so shim loaded — smart-card redirection channel is now backed by UsbCcidReader")
        } catch (e: UnsatisfiedLinkError) {
            // Expected on any build with SYSTEMSGO_SMARTCARD_BACKEND_AVAILABLE=0
            // (the shim target is only built when that CMake option is on —
            // see CMakeLists.txt) or an ABI the shim failed to compile for.
            Log.w(TAG, "libpcsclite.so shim not present in this build — smart-card redirection will report no reader", e)
        }
    }

    /**
     * Kicks off USB permission + reader discovery (see
     * [UsbCcidReader.discoverAndRequestPermission]) and stores the first
     * reader that grants permission and opens successfully as the active
     * reader every later `SCard*` call from the native shim operates on.
     * Call from `RdpSessionActivity.onCreate`/`onNewIntent` when the
     * connecting profile has `enableSmartcardRedirect` on — same "only ask
     * for what this session needs" pattern as the CAMERA permission request
     * for webcam redirect.
     */
    fun discoverReader(context: Context) {
        UsbCcidReader.discoverAndRequestPermission(context.applicationContext) { opened ->
            if (opened != null) {
                Log.i(TAG, "CCID reader ready for smart-card redirection")
                reader?.close()
                reader = opened
                readerIsNfc = false
            }
        }
    }

    /**
     * NFC-READER FEATURE: enables contactless card reading for as long as
     * [activity] stays in the foreground — see [NfcCcidReader.enableReaderMode]
     * for the underlying [android.nfc.NfcAdapter] reader-mode contract. Every
     * tag tapped while this is active that answers ISO/IEC 14443-4 becomes
     * the new active [reader] immediately, the same "last one presented
     * wins" replacement [discoverReader] already does for USB — there is
     * still only ever one active reader/card at a time (see
     * [PcscCardReader]'s doc comment).
     *
     * No-op (logs and returns) on a device with no NFC radio. Call from
     * `RdpSessionActivity.onResume`; pair with [disableNfcReaderMode] in
     * `onPause`.
     */
    fun enableNfcReaderMode(activity: android.app.Activity) {
        NfcCcidReader.enableReaderMode(activity) { opened ->
            if (opened != null) {
                Log.i(TAG, "Contactless card tapped — ready for smart-card redirection")
                reader?.close()
                reader = opened
                readerIsNfc = true
            }
        }
    }

    /** Disables NFC reader mode previously enabled via [enableNfcReaderMode]. Call from `RdpSessionActivity.onPause`. */
    fun disableNfcReaderMode(activity: android.app.Activity) {
        NfcCcidReader.disableReaderMode(activity)
    }

    /** Releases the active reader, if any. Call from `RdpSessionActivity.onDestroy`. */
    fun releaseReader() {
        reader?.close()
        reader = null
        readerIsNfc = false
    }

    // ── Called from pcsc_shim.c via JNI (CallStatic*Method) ─────────────────
    // Every method below is intentionally small and side-effect-scoped to a
    // single UsbCcidReader call, matching the synchronous request/response
    // shape SCard* functions have — the shim blocks its calling thread on
    // each of these exactly the way it would block on a real IPC round-trip
    // to pcscd.

    /**
     * Returns the PC/SC-style reader name list the shim's
     * `SCardListReaders` should hand back to FreeRDP — a single synthetic
     * name if a reader is open and ready, or empty if none is available
     * yet (matches how a real PC/SC stack reports zero readers rather than
     * erroring when nothing is plugged in / permission wasn't granted yet).
     */
    @JvmStatic
    fun nativeListReaders(): Array<String> {
        val r = reader ?: return emptyArray()
        if (!r.isOpen) return emptyArray()
        val name = if (readerIsNfc) "Android Contactless Smart Card 00 00" else "Android Smart Card 00 00"
        return arrayOf(name)
    }

    /**
     * Backs `SCardConnect` — powers the card on (if not already) and
     * reports whether a card responded. `readerName` is accepted but
     * ignored beyond logging: this bridge only ever manages one active
     * reader (see [UsbCcidReader]'s single-reader/single-slot scope), so
     * there's nothing to disambiguate yet.
     */
    @JvmStatic
    fun nativeConnect(readerName: String): Boolean {
        val r = reader ?: run {
            Log.w(TAG, "nativeConnect($readerName): no reader open")
            return false
        }
        val atr = r.powerOn()
        if (atr == null) {
            Log.w(TAG, "nativeConnect($readerName): powerOn failed / no card present")
            return false
        }
        return true
    }

    @JvmStatic
    fun nativeDisconnect() {
        reader?.powerOff()
    }

    /** Backs `SCardStatus`'s ATR-reporting half. Empty array if no card powered on. */
    @JvmStatic
    fun nativeGetAtr(): ByteArray = reader?.lastAtr() ?: ByteArray(0)

    @JvmStatic
    fun nativeIsCardPresent(): Boolean = reader?.isCardPresent() ?: false

    /**
     * Backs the protocol field of `SCardConnect`/`SCardReconnect`/`SCardStatus`.
     * Reflects [UsbCcidReader.activeProtocol], which is parsed from the
     * card's real ATR (ISO/IEC 7816-3 TD-chain) rather than assumed — see
     * [UsbCcidReader.parseAtrProtocol]. Returns [UsbCcidReader.PROTOCOL_T0]
     * if no reader/card is available (matches `powerOn`'s ISO-mandated
     * default when nothing else is known).
     */
    @JvmStatic
    fun nativeGetProtocol(): Int = reader?.activeProtocol() ?: UsbCcidReader.PROTOCOL_T0

    /**
     * Backs `SCardGetStatusChange`'s actual blocking contract: waits up to
     * [timeoutMs] for the reader's interrupt endpoint to report a real
     * presence change, instead of the shim reporting the current snapshot
     * immediately every call. Returns `false` (and the shim's caller then
     * treats it as "no change occurred in time," which callers of
     * `SCardGetStatusChange` are required to handle) if this reader has no
     * interrupt pipe or none was ever detected — see
     * [UsbCcidReader.waitForStatusChange].
     */
    @JvmStatic
    fun nativeWaitForStatusChange(timeoutMs: Int): Boolean =
        reader?.waitForStatusChange(timeoutMs.toLong()) ?: false

    /** Backs `SCardTransmit`. Returns null (mapped to an SCard error by the shim) on any failure. */
    @JvmStatic
    fun nativeTransmit(apdu: ByteArray): ByteArray? = reader?.transmit(apdu)

    /**
     * PCSC-GETATTRIB FEATURE: backs `SCardGetAttrib`. Returns null (the
     * shim maps that to `SCARD_E_UNSUPPORTED_FEATURE`) if no reader is
     * active or the active one has no real data for [attribId] — see
     * [PcscCardReader.getAttrib]'s doc comment for why this never
     * fabricates a value.
     */
    @JvmStatic
    fun nativeGetAttrib(attribId: Int): ByteArray? = reader?.getAttrib(attribId)

    /**
     * PCSC-GETATTRIB FEATURE: backs `SCardSetAttrib`. Always false — see
     * [PcscCardReader.setAttrib]'s doc comment for why this is a
     * deliberate, permanent "unsupported," not a stub awaiting a future
     * implementation.
     */
    @JvmStatic
    fun nativeSetAttrib(attribId: Int, data: ByteArray): Boolean = reader?.setAttrib(attribId, data) ?: false

    /**
     * PCSC-GETATTRIB FEATURE: backs `SCardControl` (CCID escape/vendor
     * IOCTL passthrough). Always null — see [PcscCardReader.control]'s doc
     * comment for why this is a deliberate, permanent "unsupported."
     */
    @JvmStatic
    fun nativeControl(controlCode: Int, data: ByteArray): ByteArray? = reader?.control(controlCode, data)
}
