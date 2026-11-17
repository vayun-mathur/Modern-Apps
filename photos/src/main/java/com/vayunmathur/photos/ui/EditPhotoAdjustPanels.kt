package com.vayunmathur.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.get
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.ink.brush.Brush
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconRotateRight
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.BasicAdjustment
import com.vayunmathur.photos.data.BlackAndWhiteAdj
import com.vayunmathur.photos.data.BlurAdj
import com.vayunmathur.photos.data.BlurParams
import com.vayunmathur.photos.data.ChannelMixerAdj
import com.vayunmathur.photos.data.ColorBalanceAdj
import com.vayunmathur.photos.data.CurveChannel
import com.vayunmathur.photos.data.CurvesAdj
import com.vayunmathur.photos.data.CurvesAdjustment
import com.vayunmathur.photos.data.DodgeBurnMode
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.GradientMapAdj
import com.vayunmathur.photos.data.HslAdj
import com.vayunmathur.photos.data.HslAdjustments
import com.vayunmathur.photos.data.HslColorRange
import com.vayunmathur.photos.data.ImageAdjustments
import com.vayunmathur.photos.data.LevelsAdj
import com.vayunmathur.photos.data.PhotoFilter
import com.vayunmathur.photos.data.PhotoFilterAdj
import com.vayunmathur.photos.data.PhotoFilters
import com.vayunmathur.photos.data.PosterizeAdj
import com.vayunmathur.photos.data.Selection
import com.vayunmathur.photos.data.SelectionCombine
import com.vayunmathur.photos.data.SelectiveAdj
import com.vayunmathur.photos.data.SelectiveColorAdj
import com.vayunmathur.photos.data.SelectiveEdits
import com.vayunmathur.photos.data.SelectiveMask
import com.vayunmathur.photos.data.ThresholdAdj
import com.vayunmathur.photos.data.VibranceAdj
import com.vayunmathur.photos.data.toColorMatrix
import com.vayunmathur.photos.platform.PhotoEditViewModel
import kotlin.math.roundToInt

@Composable
internal fun CropRotatePanel(
    onRotate90: () -> Unit,
    selectedAspect: Float?,
    onAspect: (Float?) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    val aspects: List<Pair<String, Float?>> = listOf(
        "Free" to null, "1:1" to 1f, "4:3" to 4f / 3f, "3:4" to 3f / 4f, "16:9" to 16f / 9f, "9:16" to 9f / 16f,
    )
    PanelContainer(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.aspect_ratio), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoHint(stringResource(R.string.locks_the_crop_to_a_fixed_width_height_s))
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            aspects.forEach { (label, ar) ->
                SelectableChip(label, selectedAspect == ar, { onAspect(ar) })
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.rotate), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoHint(stringResource(R.string.drag_the_round_handle_above_the_crop_box))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.clickable { onRotate90() },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) { IconRotateRight(); Text(stringResource(R.string.rotate_90), fontSize = 13.sp) }
            }
            Surface(
                modifier = Modifier.clickable { onReset() },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
            ) { Text(stringResource(UiR.string.reset), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Surface(
                modifier = Modifier.clickable { onCancel() },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) { IconClose(); Text(stringResource(UiR.string.cancel), fontSize = 13.sp) }
            }
            Surface(
                modifier = Modifier.clickable { onApply() },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) { IconCheck(); Text(stringResource(UiR.string.apply), fontSize = 13.sp) }
            }
        }
    }
}


@Composable
internal fun AdjustmentPanel(
    adjustments: ImageAdjustments,
    selectedAdjustment: AdjustmentType,
    onSelectAdjustment: (AdjustmentType) -> Unit,
    onUpdateAdjustment: ((ImageAdjustments) -> ImageAdjustments) -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AdjustmentType.entries.forEach { type ->
                val value = type.get(adjustments)
                Surface(
                    modifier = Modifier.clickable { onSelectAdjustment(type) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (selectedAdjustment == type) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                ) {
                    val label = stringResource(type.label)
                    Text(
                        if (value != 0f) "$label ${value.roundToInt()}" else label,
                        fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        val currentValue = selectedAdjustment.get(adjustments)
        LabeledSlider(stringResource(selectedAdjustment.label), currentValue, selectedAdjustment.min..selectedAdjustment.max) {
            onUpdateAdjustment { adj -> selectedAdjustment.set(adj, it) }
        }
        if (adjustments != ImageAdjustments()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Surface(
                    modifier = Modifier.clickable { onReset() }.padding(4.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(stringResource(R.string.reset_all), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

@Composable
internal fun FilterPresetPanel(
    bitmap: android.graphics.Bitmap?,
    adjustments: ImageAdjustments,
    onSelectFilter: (PhotoFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(8.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhotoFilters.all.forEach { filter ->
            val isSelected = if (filter.adjustments == ImageAdjustments()) adjustments == ImageAdjustments() else adjustments == filter.adjustments
            Column(
                modifier = Modifier.width(72.dp).clickable { onSelectFilter(filter) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                bitmap?.let { bmp ->
                    val filterMatrix = remember(filter) { filter.adjustments.toColorMatrix() }
                    val hasFilter = filter.adjustments != ImageAdjustments()
                    Box(
                        modifier = Modifier.size(64.dp).then(
                            if (isSelected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier
                        ),
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = filter.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            colorFilter = if (hasFilter) androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(filterMatrix.array)) else null,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(filter.name, fontSize = 10.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}
