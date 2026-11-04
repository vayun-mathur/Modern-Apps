package com.vayunmathur.games.logicgate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.logicgate.domain.model.ChipInfo
import com.vayunmathur.games.logicgate.domain.model.Chips

/** The localized name of [chipId], falling back to the id. */
@Composable
fun chipLabel(chipId: String): String = ChipInfo.label(chipId)?.let { stringResource(it) } ?: chipId

/** Every chip's localized name by id, for measuring and drawing outside composition. */
@Composable
fun rememberChipLabels(): Map<String, String> {
    val resources = LocalResources.current
    return remember(resources) {
        Chips.all.associate { chip -> chip.id to (ChipInfo.label(chip.id)?.let(resources::getString) ?: chip.id) }
    }
}
