package com.vayunmathur.library.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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

/**
 * One-liner hook: registers game + achievement defs into hub, tracks sessions via lifecycle.
 * No leaderboards.
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
    val sessionReporter = remember { GameHubSessionReporter(client) }

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

    LaunchedEffect(achievementsManager) {
        if (achievementsManager != null) {
            val gm = metadata ?: GameMetadata(
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
            GameHubReporter.bind(context, gameId, achievementsManager, client, gm)
        }
    }
}

@Composable
fun GameHubSessionHook(gameId: String, displayName: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val client = remember { GameHubClient(context, gameId) }
    val reporter = remember { GameHubSessionReporter(client) }

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

    LaunchedEffect(Unit) {
        try {
            client.registerGame(
                GameMetadata(
                    gameId = gameId,
                    displayName = displayName,
                    versionName = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (_: Exception) { null }
                )
            )
        } catch (_: Exception) { }
    }
}
