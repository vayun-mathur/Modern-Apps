@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.vayunmathur.translate.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.translate.platform.TranslateViewModel
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import java.util.concurrent.Executors
import androidx.camera.view.PreviewView
import android.view.Surface

@Composable
internal fun CameraPreviewLayer(
    viewModel: TranslateViewModel,
    previewView: PreviewView,
    onBack: () -> Unit,
    onOpenLanguagePicker: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val sourceLang by viewModel.sourceLang.collectAsState()
    val targetLang by viewModel.targetLang.collectAsState()
    val translationAvailable by viewModel.translationAvailable.collectAsState()

    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var provider by remember { mutableStateOf<androidx.camera.lifecycle.ProcessCameraProvider?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }

    // OCR results, in analysed-bitmap pixel coords, plus the translations for the
    // lines in them. Keeping the two apart lets OCR keep running at full speed while
    // the (much slower) translator fills results in behind it.
    val overlays = remember { mutableStateOf<List<OverlayBox>>(emptyList()) }
    val translations = remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val frameW = remember { mutableStateOf(0) }
    val frameH = remember { mutableStateOf(0) }
    val frozenFrame = remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    // Size of the preview area and the rotation it is being shown at. Both go into the
    // ViewPort that ties the two camera streams to one field of view, so a change to
    // either has to rebind rather than just retarget.
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var boundRotation by remember { mutableStateOf(Surface.ROTATION_0) }

    // Read by the analysis-thread callback; kept fresh via rememberUpdatedState.
    val pausedState = rememberUpdatedState(paused)

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val inFlight = remember { AtomicBoolean(false) }
    val lastMs = remember { AtomicLong(0L) }
    val analysis = rememberCameraAnalysis()

    fun displayRotation(): Int {
        val display = previewView.display ?: runCatching { context.display }.getOrNull()
        return display?.rotation ?: Surface.ROTATION_0
    }

    // Keyed on `camera` too: the first pass runs before binding completes, when
    // there is no CameraControl to talk to yet.
    LaunchedEffect(camera, torchOn) {
        camera?.cameraControl?.enableTorch(torchOn)
    }

    CameraBinding(
        context = context,
        lifecycleOwner = lifecycleOwner,
        scope = scope,
        viewModel = viewModel,
        previewView = previewView,
        analysis = analysis,
        analysisExecutor = analysisExecutor,
        inFlight = inFlight,
        lastMs = lastMs,
        pausedValue = { pausedState.value },
        viewSize = viewSize,
        boundRotation = boundRotation,
        onRotationChange = { boundRotation = it },
        frameW = frameW,
        frameH = frameH,
        overlays = overlays,
        frozenFrame = frozenFrame,
        camera = remember { mutableStateOf(camera) }.also { it.value = camera },
        provider = remember { mutableStateOf(provider) }.also { it.value = provider },
    )

    TranslationLoop(
        viewModel = viewModel,
        sourceLang = sourceLang,
        targetLang = targetLang,
        translationAvailable = translationAvailable,
        overlays = overlays.value,
        onTranslations = { translations.value = it },
    )

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged {
                    viewSize = it
                    boundRotation = displayRotation()
                },
        ) {
            // The preview stays composed even while paused — swapping it out tears the
            // camera surface down and back up, which flashes black on every toggle.
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            val frozen = frozenFrame.value
            if (paused && frozen != null && !frozen.isRecycled) {
                Image(
                    bitmap = frozen.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    // Already cropped to the viewport, so it covers the preview area
                    // exactly — there is no second crop left to get wrong.
                    contentScale = ContentScale.FillBounds,
                )
            }
        }

        CameraOverlay(
            overlays = overlays.value,
            translations = translations.value,
            translationAvailable = translationAvailable,
            frameW = frameW.value,
            frameH = frameH.value,
            viewW = viewSize.width,
            viewH = viewSize.height,
            sourceLang = sourceLang,
            targetLang = targetLang,
            paused = paused,
            onTogglePaused = { paused = !paused },
            torchOn = torchOn,
            onToggleTorch = { torchOn = !torchOn },
            onBack = onBack,
            onOpenLanguagePicker = onOpenLanguagePicker,
        )
    }
}
