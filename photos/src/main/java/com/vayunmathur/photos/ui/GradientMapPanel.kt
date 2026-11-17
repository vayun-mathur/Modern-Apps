package com.vayunmathur.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.ButtonGroup
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.photos.data.BlackAndWhiteAdjustment
import com.vayunmathur.photos.data.ChannelMixerAdjustment
import com.vayunmathur.photos.data.ColorBalanceAdjustment
import com.vayunmathur.photos.data.FilterBlur
import com.vayunmathur.photos.data.FilterBlurMode
import com.vayunmathur.photos.data.LevelsAdjustment
import com.vayunmathur.photos.data.NoiseParams
import com.vayunmathur.photos.data.PhotoFilterAdj
import com.vayunmathur.photos.data.SelectiveColorAdj
import com.vayunmathur.photos.data.SelectiveColorRange
import com.vayunmathur.photos.data.StylizeMode
import com.vayunmathur.photos.data.StylizeParams
import com.vayunmathur.photos.data.UnsharpMask
import com.vayunmathur.photos.data.applyToBitmap
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.photos.R

@Composable
fun GradientMapPanel(onSelect: (List<com.vayunmathur.photos.data.GradientStop>) -> Unit) = PanelColumn {
    val presets = listOf(
        "Grayscale" to listOf(
            com.vayunmathur.photos.data.GradientStop(0f, 0xFF000000.toInt()),
            com.vayunmathur.photos.data.GradientStop(1f, 0xFFFFFFFF.toInt()),
        ),
        "Sepia" to listOf(
            com.vayunmathur.photos.data.GradientStop(0f, 0xFF1B0E00.toInt()),
            com.vayunmathur.photos.data.GradientStop(0.5f, 0xFF8A5A2B.toInt()),
            com.vayunmathur.photos.data.GradientStop(1f, 0xFFFFE9C9.toInt()),
        ),
        "Cool" to listOf(
            com.vayunmathur.photos.data.GradientStop(0f, 0xFF001133.toInt()),
            com.vayunmathur.photos.data.GradientStop(0.5f, 0xFF00AACC.toInt()),
            com.vayunmathur.photos.data.GradientStop(1f, 0xFFFFFFFF.toInt()),
        ),
        "Warm" to listOf(
            com.vayunmathur.photos.data.GradientStop(0f, 0xFF330000.toInt()),
            com.vayunmathur.photos.data.GradientStop(0.5f, 0xFFCC5500.toInt()),
            com.vayunmathur.photos.data.GradientStop(1f, 0xFFFFFF66.toInt()),
        ),
    )
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { (label, stops) ->
            Surface(
                modifier = Modifier.clickable { onSelect(stops) },
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) { Text(label, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
        }
    }
}
