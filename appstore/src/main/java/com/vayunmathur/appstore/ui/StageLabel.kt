package com.vayunmathur.appstore.ui
import com.vayunmathur.appstore.R

import com.vayunmathur.appstore.platform.reason
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.vayunmathur.appstore.data.installer.InstallStage

@Composable
fun stageLabel(stage: InstallStage): String = when (stage) {
    InstallStage.Preparing -> stringResource(R.string.stage_preparing)
    is InstallStage.Downloading ->
        stringResource(R.string.stage_downloading, (stage.fraction * 100).toInt())
    InstallStage.Verifying -> stringResource(R.string.stage_verifying)
    InstallStage.Installing -> stringResource(R.string.stage_installing)
    is InstallStage.Failed -> stringResource(R.string.stage_failed, stage.reason)
}
