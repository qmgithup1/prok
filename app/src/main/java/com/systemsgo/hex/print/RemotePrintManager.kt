package com.systemsgo.hex.print

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.util.Log
import com.systemsgo.hex.rdp.native.AFreeRdpBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * PRINTER-REDIRECT FEATURE.
 *
 * Owns the Android-side half of remote printing for one RDP session: takes
 * print data arriving from the remote session's printer device on the
 * "rdpdr" channel (see [com.systemsgo.hex.rdp.native.AFreeRdpBridge.printJobData]
 * / [com.systemsgo.hex.rdp.native.AFreeRdpBridge.printerChannelState]) and hands
 * it to Android's own Print Framework ([PrintManager] +
 * [PrintDocumentAdapter]), exactly the way any other Android app would print
 * a document. This is deliberately *not* a custom printer pipeline: Android's
 * Print Framework already knows how to discover and print to Wi-Fi/network
 * printers and USB printers (via whatever print services — Mopria, vendor
 * plugins, etc. — the device has installed) and always offers "Save as PDF",
 * so this class's only job is to package the received bytes as a
 * [PrintDocumentAdapter] and let the system print dialog take it from there.
 * Following Android's own Print Framework guidance (developer.android.com →
 * "Printing") also means print-job progress (preparing / printing /
 * completed / cancelled) is surfaced automatically through the system's
 * print notification — this class doesn't reimplement that UI, only tracks
 * the same states in [jobStatus] for callers that want it (tests, a future
 * in-session indicator) without reaching into the system notification shade.
 *
 * One instance per RDP session — created and torn down alongside the
 * session's [com.systemsgo.hex.rdp.protocol.RdpRemoteAdapter], mirroring
 * [com.systemsgo.hex.audio.RemoteAudioManager]'s lifetime.
 *
 * IMPORTANT SCOPE NOTE (see systemsgo_jni.c's printer-redirection doc comment
 * and app/src/main/cpp/SETUP.md): this build's FreeRDP prebuilt has
 * WITH_CUPS=OFF, so no printer device is actually registered on "rdpdr" yet
 * (mirrors the smartcard/PCSC gap already documented there) —
 * [backendAvailable] reports that honestly via
 * [com.systemsgo.hex.rdp.native.AFreeRdpBridge.isPrinterBackendAvailable]. This
 * class is written to work the moment print data starts flowing (nothing
 * here needs to change): until then, [printJobData] simply never emits, and
 * [start] is a no-op when [backendAvailable] or [redirectRequested] is false.
 */
class RemotePrintManager(
    private val appContext: Context,
    /** Raw print-data chunks arriving from the native printer device on "rdpdr". */
    private val printJobData: SharedFlow<AFreeRdpBridge.NativePrintJobData>,
    /** Printer device connect/disconnect events for the redirected device. */
    private val channelState: SharedFlow<Boolean>,
    /** True if this profile requested printer redirection (enablePrinterRedirect). */
    private val redirectRequested: Boolean,
    /** Whether the native build has a working printer backend at all. */
    private val backendAvailable: Boolean,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectJob: Job? = null
    private var channelJob: Job? = null

    private val _availability = MutableStateFlow(
        when {
            !backendAvailable   -> RemotePrintAvailability.UNSUPPORTED_BUILD
            !redirectRequested  -> RemotePrintAvailability.DISABLED_BY_PROFILE
            else                -> RemotePrintAvailability.CHANNEL_NOT_CONNECTED
        }
    )
    val availability: StateFlow<RemotePrintAvailability> = _availability.asStateFlow()

    private val _jobStatus = MutableStateFlow<PrintJobInfo?>(null)
    /** Most recently updated redirected print job's status, or null before any job has arrived. */
    val jobStatus: StateFlow<PrintJobInfo?> = _jobStatus.asStateFlow()

    // Per-job spool buffer, keyed by native job id — accumulates chunks until
    // the native side marks one as final, then hands the whole document to
    // the Print Framework in one go (PrintDocumentAdapter.onWrite needs the
    // complete, already-rendered document; it isn't a streaming API).
    private val spoolBuffers = mutableMapOf<Int, ByteArrayOutputStream>()

    /** Call once the session is CONNECTED — starts collecting print data (if enabled/available). */
    fun start() {
        if (!backendAvailable || !redirectRequested) return

        channelJob = scope.launch {
            channelState.collect { connected ->
                _availability.value = if (connected) {
                    RemotePrintAvailability.AVAILABLE
                } else {
                    RemotePrintAvailability.CHANNEL_NOT_CONNECTED
                }
            }
        }
        collectJob = scope.launch {
            printJobData.collect { chunk -> handleChunk(chunk) }
        }
    }

    /** Call on session disconnect — cancels collection and discards any partial spool buffers. */
    fun stop() {
        collectJob?.cancel()
        collectJob = null
        channelJob?.cancel()
        channelJob = null
        spoolBuffers.clear()
    }

    private fun handleChunk(chunk: AFreeRdpBridge.NativePrintJobData) {
        _jobStatus.value = PrintJobInfo(chunk.jobId, PrintJobStatus.PREPARING)
        try {
            val buffer = spoolBuffers.getOrPut(chunk.jobId) { ByteArrayOutputStream() }
            buffer.write(chunk.data)
        } catch (e: IOException) {
            Log.w(TAG, "Failed to spool print job ${chunk.jobId}", e)
            spoolBuffers.remove(chunk.jobId)
            _jobStatus.value = PrintJobInfo(chunk.jobId, PrintJobStatus.FAILED, e.message)
            return
        }

        if (chunk.isFinalChunk) {
            val bytes = spoolBuffers.remove(chunk.jobId)?.toByteArray() ?: ByteArray(0)
            submitToPrintFramework(chunk.jobId, bytes)
        }
    }

    /**
     * Hands a fully-spooled print job to Android's Print Framework, following
     * the standard PrintManager/PrintDocumentAdapter pattern
     * (developer.android.com → Printing → "Create a Custom Document"): a
     * minimal adapter that reports layout in [PrintDocumentAdapter.onLayout]
     * and writes the already-prepared bytes straight through in
     * [PrintDocumentAdapter.onWrite]. The system print dialog that opens from
     * [PrintManager.print] is what actually lists Wi-Fi/network/USB printers
     * (via whichever print services are installed) alongside the built-in
     * "Save as PDF" option — this class has no printer-discovery code of its
     * own, by design, since the Print Framework already owns that.
     */
    private fun submitToPrintFramework(jobId: Int, data: ByteArray) {
        _jobStatus.value = PrintJobInfo(jobId, PrintJobStatus.PRINTING)

        val printManager = appContext.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        if (printManager == null) {
            Log.w(TAG, "PRINT_SERVICE unavailable — cannot print job $jobId")
            _jobStatus.value = PrintJobInfo(jobId, PrintJobStatus.FAILED, "Print service unavailable")
            return
        }

        val jobLabel = "Systems Go print job $jobId"
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?,
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    _jobStatus.value = PrintJobInfo(jobId, PrintJobStatus.CANCELLED)
                    return
                }
                val info = PrintDocumentInfo.Builder("$jobLabel.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                    .build()
                // changesLayout=true unconditionally: this is always a brand-new
                // remote document, never a re-layout of previous content.
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback,
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onWriteCancelled()
                    _jobStatus.value = PrintJobInfo(jobId, PrintJobStatus.CANCELLED)
                    return
                }
                try {
                    FileOutputStream(destination.fileDescriptor).use { out -> out.write(data) }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                    _jobStatus.value = PrintJobInfo(jobId, PrintJobStatus.COMPLETED)
                } catch (e: IOException) {
                    Log.w(TAG, "Failed to write print job $jobId", e)
                    callback.onWriteFailed(e.message)
                    _jobStatus.value = PrintJobInfo(jobId, PrintJobStatus.FAILED, e.message)
                }
            }
        }

        try {
            printManager.print(jobLabel, adapter, null)
        } catch (e: Exception) {
            Log.w(TAG, "PrintManager.print() failed for job $jobId", e)
            _jobStatus.value = PrintJobInfo(jobId, PrintJobStatus.FAILED, e.message)
        }
    }

    private companion object {
        const val TAG = "RemotePrintManager"
    }
}
