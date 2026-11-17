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
fun BlackWhitePanel(bw: BlackAndWhiteAdjustment, onChange: (BlackAndWhiteAdjustment) -> Unit) = PanelColumn {
    LabeledSlider(stringResource(R.string.reds), bw.reds, 0f..200f) { onChange(bw.copy(reds = it)) }
    LabeledSlider(stringResource(R.string.yellows), bw.yellows, 0f..200f) { onChange(bw.copy(yellows = it)) }
    LabeledSlider(stringResource(R.string.greens), bw.greens, 0f..200f) { onChange(bw.copy(greens = it)) }
    LabeledSlider(stringResource(R.string.cyans), bw.cyans, 0f..200f) { onChange(bw.copy(cyans = it)) }
    LabeledSlider(stringResource(R.string.blues), bw.blues, 0f..200f) { onChange(bw.copy(blues = it)) }
    LabeledSlider(stringResource(R.string.magentas), bw.magentas, 0f..200f) { onChange(bw.copy(magentas = it)) }
}
