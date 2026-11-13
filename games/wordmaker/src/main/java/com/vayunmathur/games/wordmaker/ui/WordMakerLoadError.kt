package com.vayunmathur.games.wordmaker.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.wordmaker.data.GameMode
import com.vayunmathur.games.wordmaker.ui.components.WordMakerModeChooser
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ErrorState
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.game.GameTopBarActions

/**
 * A level that could not be loaded or generated. Keeps the game's own top bar, so the player
 * can switch mode or open settings, and offers Retry instead of a dead end.
 */
@Composable
fun WordMakerLoadError(
    message: String,
    gameMode: GameMode,
    levelNumber: Int,
    onSelectMode: (GameMode) -> Unit,
    onRetry: () -> Unit,
    onOpenGameCenter: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    AppScaffold(
        modifier = Modifier.fillMaxSize(),
        title = {
            WordMakerModeChooser(
                selected = gameMode,
                onSelected = onSelectMode,
                levelNumber = levelNumber,
            )
        },
        actions = {
            GameTopBarActions(
                onOpenGameCenter = onOpenGameCenter,
                onOpenSettings = onOpenSettings,
            )
        },
        alignment = AppBarAlignment.Center,
        scrollBehavior = appBarScrollBehavior(),
    ) { innerPadding ->
        ErrorState(
            title = message,
            modifier = Modifier.padding(innerPadding),
            retryLabel = stringResource(UiR.string.retry),
            onRetry = onRetry,
        )
    }
}
