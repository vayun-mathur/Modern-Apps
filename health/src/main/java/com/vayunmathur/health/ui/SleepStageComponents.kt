package com.vayunmathur.health.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.R
import com.vayunmathur.health.data.SleepData
import com.vayunmathur.health.ui.components.hypnogramColors
import com.vayunmathur.library.ui.DashboardSection
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text

// Sleep stage breakdown components. Split from SleepDetailsPage.kt to keep that file under
// the 350-line UI limit and one public composable per file.

@Composable
fun SleepStageBreakdown(data: SleepData) {
    val context = LocalContext.current
    val colors = hypnogramColors()
    DashboardSection(title = "Stages", accentColor = HealthColors.Sleep) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StageRow(
                stringResource(R.string.label_awake),
                hoursMinutesString(context, data.awakeDurationMillis / 60000),
                colors.awake
            )
            StageRow(
                stringResource(R.string.label_rem),
                hoursMinutesString(context, data.remDurationMillis / 60000),
                colors.rem
            )
            StageRow(
                stringResource(R.string.label_core),
                hoursMinutesString(context, data.lightDurationMillis / 60000),
                colors.core
            )
            StageRow(
                stringResource(R.string.label_deep),
                hoursMinutesString(context, data.deepDurationMillis / 60000),
                colors.deep
            )
        }
    }
}

