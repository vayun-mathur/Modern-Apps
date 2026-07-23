package com.vayunmathur.library.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.sdk.games.GameHubClient
import com.vayunmathur.sdk.games.GameMetadata

@Composable
fun rememberGameHubClient(gameId: String): GameHubClient {
    val context = LocalContext.current
    return remember(gameId) { GameHubClient(context, gameId) }
}

private fun resolveGameMetadata(
    context: Context,
    gameId: String,
    explicit: GameMetadata?
): GameMetadata {
    if (explicit != null) return explicit
    return GameMetadata(
        gameId = gameId,
        displayName = when (gameId) {
            "chess" -> "Chess"
            "solitaire" -> "Solitaire"
            "alchemist" -> "Alchemist"
            "pipes" -> "Pipes"
            "unblockjam" -> "Unblock Jam"
            "wordmaker" -> "Wordmaker"
            "logicgate" -> "Logic Gates"
            else -> gameId.replaceFirstChar { it.uppercase() }
        },
        description = null,
        versionName = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (_: Exception) { null },
        versionCode = try { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode } catch (_: Exception) { null }
    )
}

/**
 * One-liner hook: registers game + achievement defs into hub, tracks sessions via lifecycle.
 * Registration happens immediately on open (by gameId), not gated on achievementsManager.
 * Session tracking is unconditional; achievement binding is gated.
 */
@Composable
fun GameHubComposeHook(
    gameId: String,
    achievementsManager: AchievementsManager?,
    metadata: GameMetadata? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val client = rememberGameHubClient(gameId)
    val sessionReporter = remember(gameId) { GameHubSessionReporter(client) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> sessionReporter.start()
                Lifecycle.Event.ON_PAUSE -> sessionReporter.end()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sessionReporter.end()
        }
    }

    // Immediate registration on open — not gated on achievementsManager
    LaunchedEffect(gameId) {
        try {
            client.registerGame(resolveGameMetadata(context, gameId, metadata))
        } catch (_: Exception) { }
    }

    // Achievement binding only when manager available; cancels previous bind on change
    var reporterHandle by remember { androidx.compose.runtime.mutableStateOf<GameHubReporter.ReporterHandle?>(null) }
    DisposableEffect(achievementsManager) {
        onDispose {
            reporterHandle?.cancel()
            reporterHandle = null
        }
    }
    LaunchedEffect(achievementsManager) {
        reporterHandle?.cancel()
        reporterHandle = null
        if (achievementsManager != null) {
            val gm = resolveGameMetadata(context, gameId, metadata)
            reporterHandle = GameHubReporter.bind(context, gameId, achievementsManager, client, gm)
        }
    }
}

@Composable
fun GameHubSessionHook(gameId: String, displayName: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val client = remember(gameId) { GameHubClient(context, gameId) }
    val reporter = remember(gameId) { GameHubSessionReporter(client) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> reporter.start()
                Lifecycle.Event.ON_PAUSE -> reporter.end()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            reporter.end()
        }
    }

    LaunchedEffect(gameId) {
        try {
            val gm = resolveGameMetadata(
                context,
                gameId,
                GameMetadata(gameId = gameId, displayName = displayName)
            )
            client.registerGame(gm)
        } catch (_: Exception) { }
    }
}
