package com.vayunmathur.astronomy.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Live back-camera preview meant to sit behind the (transparent) sky canvas as an
 * AR overlay. Requests CAMERA permission on first show; if the user denies it,
 * [onUnavailable] is invoked so the caller can flip its camera toggle back off.
 * The camera is bound only while composed, so removing this composable releases it.
 */
@Composable
fun CameraBackground(modifier: Modifier = Modifier, onUnavailable: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok
        if (!ok) onUnavailable()
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    if (!granted) return

    val surfaceRequest = remember { MutableStateFlow<SurfaceRequest?>(null) }
    val surfaceReqState by surfaceRequest.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var provider: ProcessCameraProvider? = null
            var manualOwner: ManualLifecycleOwner? = null
            try {
                provider = ProcessCameraProvider.awaitInstance(context)
                provider.unbindAll()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider { req -> surfaceRequest.value = req }
                val owner = ManualLifecycleOwner(); owner.start(); manualOwner = owner
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                awaitCancellation()
            } finally {
                manualOwner?.destroy()
                provider?.unbindAll()
                surfaceRequest.value = null
            }
        }
    }

    surfaceReqState?.let { req ->
        CameraXViewfinder(surfaceRequest = req, modifier = modifier.fillMaxSize())
    }
}

private class ManualLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    fun start() { registry.currentState = Lifecycle.State.RESUMED }
    fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
}
