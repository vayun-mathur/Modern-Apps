package com.vayunmathur.games.logicgate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.logicgate.domain.model.ChipInfo
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.PinChips

/** The player's component names by chip id, provided once at the root. */
val LocalChipNames = compositionLocalOf<Map<String, String>> { emptyMap() }

/** The localized name of [chipId] (or the player's name for a component), falling back to the id. */
@Composable
fun chipLabel(chipId: String): String =
    LocalChipNames.current[chipId] ?: ChipInfo.label(chipId)?.let { stringResource(it) } ?: chipId

/** Every chip's displayed name by id, for measuring and drawing outside composition. */
@Composable
fun rememberChipLabels(): Map<String, String> {
    val resources = LocalResources.current
    val custom = LocalChipNames.current
    return remember(resources, custom) {
        (Chips.all + PinChips.all).associate { chip -> chip.id to (ChipInfo.label(chip.id)?.let(resources::getString) ?: chip.id) } + custom
    }
}
