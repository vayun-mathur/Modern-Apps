@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.vayunmathur.translate.ui

import android.view.Surface
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.translate.platform.TranslateViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Builds the shared ImageAnalysis use case. */
@Composable
internal fun rememberCameraAnalysis(): ImageAnalysis {
    val context = LocalContext.current
    return remember {
        // Built once so the display-rotation listener and the dispose block can reach it.
        // Target rotation is pinned to ROTATION_0 here purely so [ANALYSIS_SIZE] is read in
        // sensor coords; the real rotation is applied after binding (see below).
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(Surface.ROTATION_0)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            ANALYSIS_SIZE,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
            )
            .build()
    }
}

/** One background translator, decoupled from the OCR loop. */
@Composable
internal fun TranslationLoop(
    viewModel: TranslateViewModel,
    sourceLang: String,
    targetLang: String,
    translationAvailable: Boolean,
    overlays: List<OverlayBox>,
    onTranslations: (Map<String, String>) -> Unit,
) {
    // NLLB takes on the order of a second per line, so translating every line of every
    // frame inline (as this screen used to) meant the overlay updated once every several
    // seconds, always against a frame the camera had long since moved off. Instead we
    // memo per source line: repeat lines are free, and OCR keeps redrawing boxes at full
    // rate. Keyed on the source too: switching it must drop the memo, or lines keep
    // showing translations made from the previous source language.
    // The overlay list arrives new on every OCR pass, but this loop must not
    // restart for that (it would drop the memo and re-translate everything).
    // rememberUpdatedState keeps the polling loop reading the latest snapshot.
    val latestOverlays = rememberUpdatedState(overlays)
    LaunchedEffect(sourceLang, targetLang, translationAvailable) {
        onTranslations(emptyMap())
        if (!translationAvailable) return@LaunchedEffect
        var memo = emptyMap<String, String>()
        while (isActive) {
            val next = latestOverlays.value.firstOrNull { it.text !in memo }
            if (next == null) {
                delay(TRANSLATE_IDLE_POLL_MS)
                continue
            }
            // Memo failures as the source text so a line that can't be translated
            // isn't retried on every frame forever.
            val translated = viewModel.translate(next.text) ?: next.text
            memo = (memo + (next.text to translated)).let { updated ->
                if (updated.size <= TRANSLATION_CACHE_MAX) {
                    updated
                } else {
                    updated.entries.drop(updated.size - TRANSLATION_CACHE_MAX)
                        .associate { it.key to it.value }
                }
            }
            onTranslations(memo)
        }
    }
}

