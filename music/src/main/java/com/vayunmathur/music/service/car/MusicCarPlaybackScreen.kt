package com.vayunmathur.music.service.car

import androidx.annotation.OptIn
import androidx.car.app.CarContext
import androidx.car.app.annotations.ExperimentalCarApi
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.media3.common.Player

/**
 * Now playing: title/artist/album rows plus transport actions.
 *
 * Transport goes through the shared `MediaController` (same session the
 * phone UI drives), so car and phone stay in lockstep. Actions are
 * title-only: music ships no playback drawables (launcher icons are
 * generated), and title actions need none. Refreshes on player events via
 * [invalidate].
 */
class MusicCarPlaybackScreen(
    carContext: CarContext,
    private val state: MusicCarState,
) : Screen(carContext) {

    private companion object {
        const val MIN_API_MEDIA_TEMPLATE = 8
        const val MIN_API_MEDIA_STYLE = 9
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            invalidate()
        }
    }

    init {
        state.controller?.addListener(listener)
        state.observe { invalidate() }
    }

    @OptIn(ExperimentalCarApi::class)
    @Suppress("DEPRECATION")
    override fun onGetTemplate(): Template {
        // API 8+: the dedicated media playback template. The host renders
        // transport from the registered MediaSession token (see
        // MusicCarSession token note); our Pane content below is the API 1–7
        // fallback so old hosts still show now-playing + actions.
        if (carContext.getCarAppApiLevel() >= MIN_API_MEDIA_TEMPLATE) {
            val builder = androidx.car.app.media.model.MediaPlaybackTemplate.Builder()
                .setHeader(
                    Header.Builder()
                        .setStartHeaderAction(Action.BACK)
                        .setTitle(nowPlayingTitle())
                        .build()
                )
            if (carContext.getCarAppApiLevel() >= MIN_API_MEDIA_STYLE) {
                runCatching {
                    builder.setStyle(
                        androidx.car.app.media.model.MediaPlaybackStyle.Builder()
                            .build()
                    )
                }
            }
            return builder.build()
        }
        return legacyPaneTemplate()
    }

    private fun nowPlayingTitle(): String {
        val meta = state.controller?.currentMediaItem?.mediaMetadata
        return meta?.title?.toString() ?: "Now playing"
    }

    @Suppress("DEPRECATION")
    private fun legacyPaneTemplate(): Template {
        val controller = state.controller
        val meta = controller?.currentMediaItem?.mediaMetadata
        val pane = Pane.Builder()
        val row = Row.Builder()
            .setTitle(meta?.title?.toString() ?: "Nothing playing")
        meta?.artist?.let { row.addText(it.toString()) }
        meta?.albumTitle?.let { row.addText(it.toString()) }
        pane.addRow(row.build())
        if (controller != null) {
            if (controller.hasPreviousMediaItem()) {
                pane.addAction(
                    Action.Builder()
                        .setTitle("Prev")
                        .setOnClickListener { controller.seekToPrevious() }
                        .build(),
                )
            }
            pane.addAction(
                Action.Builder()
                    .setTitle(if (controller.isPlaying) "Pause" else "Play")
                    .setOnClickListener {
                        if (controller.isPlaying) controller.pause() else controller.play()
                    }
                    .build(),
            )
            if (controller.hasNextMediaItem()) {
                pane.addAction(
                    Action.Builder()
                        .setTitle("Next")
                        .setOnClickListener { controller.seekToNext() }
                        .build(),
                )
            }
        }
        return PaneTemplate.Builder(pane.build())
            .setTitle("Now playing")
            .setHeaderAction(Action.BACK)
            .build()
    }
}
