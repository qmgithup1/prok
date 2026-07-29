package com.systemsgo.hex.print

/**
 * PRINTER-REDIRECT FEATURE.
 *
 * Mirrors the audio package's split between small shared model types
 * ([com.systemsgo.hex.audio.RemoteAudioModels]) and the manager that actually
 * does something with them ([RemotePrintManager]).
 */

/**
 * Overall availability of remote printing for the current session, combining
 * every gate that has to pass before a print job can actually reach Android's
 * Print Framework:
 *  - the native FreeRDP build must have a working printer backend
 *    ([com.systemsgo.hex.rdp.native.AFreeRdpBridge.isPrinterBackendAvailable] —
 *    see that property's doc and app/src/main/cpp/SETUP.md for the current
 *    native-build gap, the same kind this project already documents for
 *    audio/smartcard);
 *  - the profile must have opted in (enablePrinterRedirect);
 *  - the server must have agreed to open the "rdpdr" channel's printer device.
 * The UI (connection form's toggle, any future session-level printer status
 * indicator) reads this single value instead of re-deriving it from three
 * separate booleans at every call site — exactly the reasoning
 * RemoteAudioAvailability already documents for sound.
 */
enum class RemotePrintAvailability {
    /** Backend not compiled in this build — see AFreeRdpBridge.isPrinterBackendAvailable. */
    UNSUPPORTED_BUILD,
    /** Profile has printer redirection turned off. */
    DISABLED_BY_PROFILE,
    /** Enabled and backend present, but the server hasn't opened the printer device (yet, or ever). */
    CHANNEL_NOT_CONNECTED,
    /** Device connected — print jobs can flow. */
    AVAILABLE,
}

/**
 * Status of a single redirected print job, surfaced to the user per the
 * "Display print job status" requirement. Android's own Print Framework
 * already shows most of this natively (the system print-job notification
 * covers PREPARING/PRINTING/COMPLETED/CANCELLED once
 * [android.print.PrintManager.print] is called with a well-behaved
 * [android.print.PrintDocumentAdapter] — see [RemotePrintManager] for how
 * that's wired up); this enum is what [RemotePrintManager] tracks internally
 * so callers (tests, a future in-session status indicator) don't have to
 * reach into the Android print-job notification to know the current state.
 */
enum class PrintJobStatus {
    /** Receiving/spooling print data from the remote session over "rdpdr". */
    PREPARING,
    /** Handed off to Android's Print Framework and actively printing. */
    PRINTING,
    /** Print Framework reported the job finished successfully. */
    COMPLETED,
    /** Print Framework (or this manager) reported a failure. */
    FAILED,
    /** The user cancelled the job from Android's system print UI. */
    CANCELLED,
}

/** One redirected print job's current state, keyed by the native rdpdr job id. */
data class PrintJobInfo(
    val jobId: Int,
    val status: PrintJobStatus,
    /** Human-readable detail, e.g. an error message. Null unless status is FAILED. */
    val detail: String? = null,
)
