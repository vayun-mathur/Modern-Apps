package com.vayunmathur.office.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import com.vayunmathur.office.ui.ColorPickerDialog
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.setSlideElementColor
import com.vayunmathur.office.platform.setSlideElementFill
import com.vayunmathur.office.platform.setSlideElementStroke

/** Slide-element color overlays (split from OfficeCellOverlays.kt). */

@Composable
internal fun SlideElementOverlays(
    state: DocumentOverlayState,
    viewModel: OfficeViewModel,
    activeSlide: MutableState<Int>,
    activeSlideEl: MutableState<Int>,
) {
if (state.showSlideTextColor && activeSlideEl.value >= 0) {
ColorPickerDialog(
    "Text Color",
    onColorSelected = { viewModel.setSlideElementColor(activeSlide.value, activeSlideEl.value, it) },
    onDismiss = { state.showSlideTextColor = false })
}
if (state.showSlideFillColor && activeSlideEl.value >= 0) {
ColorPickerDialog(
    "Fill Color",
    onColorSelected = { viewModel.setSlideElementFill(activeSlide.value, activeSlideEl.value, it) },
    onDismiss = { state.showSlideFillColor = false })
}
if (state.showSlideStrokeColor && activeSlideEl.value >= 0) {
ColorPickerDialog(
    "Border Color",
    onColorSelected = { viewModel.setSlideElementStroke(activeSlide.value, activeSlideEl.value, it) },
    onDismiss = { state.showSlideStrokeColor = false })
}
}
