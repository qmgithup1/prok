package com.systemsgo.hex.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import com.systemsgo.hex.R
import com.systemsgo.hex.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * QR-SCANNER-REDESIGN
 *
 * Full replacement for the old zxing-android-embedded flow. That library
 * launched its own opaque, self-styled CaptureActivity — there was no way to
 * add a camera-flip control or a "scan from gallery" button to it without
 * forking the library, and its viewfinder was a plain rectangle baked into
 * its own layout XML.
 *
 * This screen is a small, self-contained CameraX + ML Kit implementation
 * instead, giving full control over:
 *  - a rounded, "framed" square viewfinder (see [QrViewfinderOverlay]) rather
 *    than a plain full-bleed rectangle, with an animated scan line so it's
 *    obvious the camera is live;
 *  - a front/back camera flip button that also transparently recovers if the
 *    currently-selected camera fails to open (e.g. a broken/blocked back
 *    camera) by falling back to whichever camera *does* work, instead of
 *    just showing a dead preview;
 *  - importing a QR code from an existing photo instead of only the live
 *    camera, restricted at the picker level to actual image types only.
 *
 * The decoded text is handed back to the caller (HomeScreen) exactly the way
 * zxing's ScanContract used to — as a plain string extra — so
 * MainViewModel.parseQrContent() and everything downstream of it needed no
 * changes at all.
 */
class QrScannerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QR_CONTENT = "qr_content"
        fun intent(context: Context): Intent = Intent(context, QrScannerActivity::class.java)
    }

    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Defense in depth: HomeScreen only ever launches this Activity after
        // CAMERA permission has already been granted (see its
        // cameraPermissionLauncher), but a scanner screen with no camera
        // permission would just show a dead black preview forever, so bail
        // out immediately rather than trap the user on a broken screen.
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        setContent {
            SystemsGoTheme {
                QrScannerScreen(
                    cameraExecutor = cameraExecutor,
                    onResult = { content ->
                        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_QR_CONTENT, content))
                        finish()
                    },
                    onClose = {
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

/** Awaits [ProcessCameraProvider.getInstance] without blocking the main thread. */
private suspend fun Context.getCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            { continuation.resume(future.get()) },
            ContextCompat.getMainExecutor(this)
        )
    }

/** Decodes a picked image Uri into a [Bitmap], working on both old and new APIs. */
private fun decodeBitmapFromUri(context: Context, uri: Uri): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.isMutableRequired = false
        }
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    }
} catch (_: Exception) {
    null
}

@Composable
private fun QrScannerScreen(
    cameraExecutor: ExecutorService,
    onResult: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(onBack = onClose)

    // Guards against the camera analyzer AND the gallery import both firing
    // onResult (e.g. a code recognized right as the user opens the gallery).
    val resultHandled = remember { AtomicBoolean(false) }
    fun deliverResult(content: String) {
        if (resultHandled.compareAndSet(false, true)) onResult(content)
    }

    var hasBackCamera by remember { mutableStateOf(true) }
    var hasFrontCamera by remember { mutableStateOf(true) }
    // Which lens is active right now. Starts on the back camera and is
    // corrected below (once we actually know what's on the device) if the
    // back camera turns out to be unavailable.
    var useFrontCamera by remember { mutableStateOf(false) }
    var bindError by remember { mutableStateOf(false) }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val barcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    DisposableEffect(Unit) {
        onDispose { barcodeScanner.close() }
    }

    // Discover which lenses actually exist exactly once, then pick the best
    // starting camera: back if present, otherwise front — so a device whose
    // back camera is disabled/missing still opens straight into the selfie
    // camera instead of a dead preview the user would have to manually flip
    // out of.
    LaunchedEffect(Unit) {
        val provider = context.getCameraProvider()
        hasBackCamera = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
        hasFrontCamera = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
        useFrontCamera = !hasBackCamera && hasFrontCamera
    }

    // (Re)binds Preview + ImageAnalysis every time the selected lens changes.
    // If binding the requested lens fails (e.g. a back camera reported as
    // present by hasCamera() but that actually errors out on open — some
    // devices, damaged hardware, or an app-op restriction), it automatically
    // retries on the other lens instead of leaving the user stuck on a black
    // screen with no obvious way out other than backing out entirely.
    LaunchedEffect(useFrontCamera, hasBackCamera, hasFrontCamera) {
        val provider = context.getCameraProvider()
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysisUseCase ->
                analysisUseCase.setAnalyzer(cameraExecutor) { imageProxy ->
                    analyzeFrame(imageProxy, barcodeScanner) { content ->
                        scope.launch { deliverResult(content) }
                    }
                }
            }

        fun trySelector(selector: CameraSelector): Boolean = try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            true
        } catch (_: Exception) {
            false
        }

        val primary = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val fallback = if (useFrontCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        val fallbackAvailable = if (useFrontCamera) hasBackCamera else hasFrontCamera

        bindError = when {
            trySelector(primary) -> false
            fallbackAvailable && trySelector(fallback) -> {
                // The lens we wanted couldn't actually open — silently swap
                // our own state to match the lens that's really active now,
                // so the flip button and its icon stay truthful.
                useFrontCamera = !useFrontCamera
                false
            }
            else -> true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }

    // ── Import a QR code from an existing photo ─────────────────────────────
    // IMPORT-FIX (same reasoning as the .rdp file picker): only "image/png"
    // and "image/jpeg" are offered, with no "*/*" fallback, so the system
    // picker actually hides videos, documents, and every other unrelated
    // file type instead of the user being able to pick anything.
    val noQrInImageText = stringResource(R.string.error_qr_image_no_code)
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bitmap = decodeBitmapFromUri(context, uri)
            if (bitmap == null) {
                snackbarHostState.showSnackbar(noQrInImageText)
                return@launch
            }
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            barcodeScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    val value = barcodes.firstOrNull()?.rawValue
                    if (value != null) {
                        deliverResult(value)
                    } else {
                        scope.launch { snackbarHostState.showSnackbar(noQrInImageText) }
                    }
                }
                .addOnFailureListener {
                    scope.launch { snackbarHostState.showSnackbar(noQrInImageText) }
                }
        }
    }

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            QrViewfinderOverlay(modifier = Modifier.fillMaxSize())

            if (bindError) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.error_camera_unavailable),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp)
                    )
                }
            }

            // ── Top bar: close button + prompt ──────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPaddingCompat()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ScannerCircleButton(
                    icon = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.qr_scanner_close),
                    onClick = onClose
                )
                Text(
                    text = stringResource(R.string.scan_qr_prompt),
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.width(48.dp))
            }

            // ── Bottom bar: flip camera + import from gallery ───────────────
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPaddingCompat()
                    .padding(bottom = 36.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ScannerActionButton(
                    icon = Icons.Filled.PhotoLibrary,
                    label = stringResource(R.string.qr_scanner_import_gallery),
                    onClick = { galleryLauncher.launch(arrayOf("image/png", "image/jpeg")) }
                )
                if (hasBackCamera && hasFrontCamera) {
                    Spacer(modifier = Modifier.width(40.dp))
                    ScannerActionButton(
                        icon = Icons.Filled.Cameraswitch,
                        label = stringResource(R.string.qr_scanner_flip_camera),
                        onClick = { useFrontCamera = !useFrontCamera }
                    )
                }
            }
        }
    }
}

/**
 * Runs one camera frame through ML Kit. [imageProxy] is always closed exactly
 * once, on every exit path, since CameraX stalls the whole analysis pipeline
 * until it is.
 */
@OptIn(ExperimentalGetImage::class)
private fun analyzeFrame(
    imageProxy: androidx.camera.core.ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onFound: (String) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(inputImage)
        .addOnSuccessListener { barcodes ->
            barcodes.firstOrNull()?.rawValue?.let(onFound)
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}

/**
 * The scanner's viewfinder: a dark scrim over the whole preview with a
 * rounded square cut out of the middle (so the framed area reads clearly as
 * "put the QR code here"), bright corner brackets, and a slow animated scan
 * line for a sense of the camera actively working — instead of the old
 * library's plain, unstyled rectangle.
 */
@Composable
private fun QrViewfinderOverlay(modifier: Modifier = Modifier) {
    val accent = PulsarCyan
    val infiniteTransition = rememberInfiniteTransition(label = "qr_scan_line")
    val scanLineProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "qr_scan_line_progress"
    )

    Canvas(modifier = modifier) {
        val squareSize = size.minDimension * 0.62f
        val left = (size.width - squareSize) / 2f
        val top = (size.height - squareSize) / 2f - size.height * 0.05f
        val cornerRadiusPx = 28.dp.toPx()

        val outerPath = Path().apply { addRect(Rect(Offset.Zero, size)) }
        val holePath = Path().apply {
            addRoundRect(
                RoundRect(
                    left = left,
                    top = top,
                    right = left + squareSize,
                    bottom = top + squareSize,
                    cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                )
            )
        }
        val scrimPath = Path.combine(PathOperation.Difference, outerPath, holePath)
        drawPath(scrimPath, color = Color.Black.copy(alpha = 0.6f))

        // Corner brackets — drawn as short "L" shapes just inside each corner
        // of the rounded square, matching the app's cyan accent.
        val bracketLength = squareSize * 0.16f
        val strokeWidth = 5.dp.toPx()
        val stroke = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val inset = cornerRadiusPx * 0.55f

        fun corner(x: Float, y: Float, dx: Int, dy: Int) {
            drawLine(accent, Offset(x, y), Offset(x + dx * bracketLength, y), stroke.width, stroke.cap)
            drawLine(accent, Offset(x, y), Offset(x, y + dy * bracketLength), stroke.width, stroke.cap)
        }
        corner(left + inset, top + inset, 1, 1)
        corner(left + squareSize - inset, top + inset, -1, 1)
        corner(left + inset, top + squareSize - inset, 1, -1)
        corner(left + squareSize - inset, top + squareSize - inset, -1, -1)

        // Animated horizontal scan line sweeping down inside the frame.
        val lineY = top + squareSize * scanLineProgress
        val lineAlpha = 1f - kotlin.math.abs(scanLineProgress - 0.5f) * 1.2f
        drawLine(
            color = accent.copy(alpha = lineAlpha.coerceIn(0.15f, 0.9f)),
            start = Offset(left + 8.dp.toPx(), lineY),
            end = Offset(left + squareSize - 8.dp.toPx(), lineY),
            strokeWidth = 3.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}

@Composable
private fun ScannerCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = contentDescription, tint = Color.White)
        }
    }
}

@Composable
private fun ScannerActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = onClick) {
                Icon(icon, contentDescription = label, tint = Color.White)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 84.dp)
        )
    }
}

// Small local shims so this file doesn't need to pull in the
// androidx.compose.foundation.layout.WindowInsets machinery just for two
// simple system-bar paddings.
@Composable
private fun Modifier.statusBarsPaddingCompat(): Modifier =
    this.then(Modifier.windowInsetsPadding(WindowInsets.statusBars))

@Composable
private fun Modifier.navigationBarsPaddingCompat(): Modifier =
    this.then(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
