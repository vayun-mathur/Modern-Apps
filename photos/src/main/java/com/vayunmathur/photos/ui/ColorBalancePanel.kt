package com.vayunmathur.photos.ui
import androidx.compose.runtime.mutableIntStateOf

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
fun ColorBalancePanel(balance: ColorBalanceAdjustment, onChange: (ColorBalanceAdjustment) -> Unit) = PanelColumn {
    var range by remember { mutableIntStateOf(0) } // 0 shadows, 1 mid, 2 high
    ButtonGroup(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        listOf("Shadows", "Midtones", "Highlights").forEachIndexed { i, label ->
            toggleableItem(checked = range == i, label = label, onCheckedChange = { range = i })
        }
    }
    val rc = when (range) { 0 -> balance.shadowsRedCyan; 1 -> balance.midRedCyan; else -> balance.highRedCyan }
    val gm = when (range) { 0 -> balance.shadowsGreenMagenta; 1 -> balance.midGreenMagenta; else -> balance.highGreenMagenta }
    val by = when (range) { 0 -> balance.shadowsBlueYellow; 1 -> balance.midBlueYellow; else -> balance.highBlueYellow }
    LabeledSlider(stringResource(R.string.red_cyan), rc, -100f..100f) {
        onChange(when (range) {
            0 -> balance.copy(shadowsRedCyan = it); 1 -> balance.copy(midRedCyan = it); else -> balance.copy(highRedCyan = it)
        })
    }
    LabeledSlider(stringResource(R.string.green_mag), gm, -100f..100f) {
        onChange(when (range) {
            0 -> balance.copy(shadowsGreenMagenta = it); 1 -> balance.copy(midGreenMagenta = it); else -> balance.copy(highGreenMagenta = it)
        })
    }
    LabeledSlider(stringResource(R.string.blue_yellow), by, -100f..100f) {
        onChange(when (range) {
            0 -> balance.copy(shadowsBlueYellow = it); 1 -> balance.copy(midBlueYellow = it); else -> balance.copy(highBlueYellow = it)
        })
    }
    SwitchRow("Preserve Luminosity", balance.preserveLuminosity) { onChange(balance.copy(preserveLuminosity = it)) }
}
