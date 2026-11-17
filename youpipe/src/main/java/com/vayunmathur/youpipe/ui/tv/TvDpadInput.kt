package com.vayunmathur.youpipe.ui.tv

import android.view.KeyEvent
import com.vayunmathur.youpipe.platform.CastPlayback

/**
 * The TV remote map for the player, modelled on YouTube for TV.
 *
 * Controls hidden (just watching):
 * - CENTER/ENTER/UP/DOWN: bring the controls up (focus lands on play/pause); the press that
 *   wakes them does not also act, so nobody loses their place by accident.
 * - LEFT/RIGHT: ±10s scrub preview while held, committed on release - no need to open anything.
 *
 * Controls visible: the D-pad belongs to the focus system (buttons, related row), except while
 * the seek bar holds focus, where LEFT/RIGHT scrub. Every press re-arms the auto-hide.
 *
 * Media keys (play/pause/next/prev/FF/REW) act whenever a player is attached. BACK dismisses the
 * topmost overlay first via [overlayDismiss]; only then does it navigate. Volume keys are left to
 * the system, which already moves the shared media level.
 */
object TvDpadInput {
    /**
     * Dismiss the topmost overlay if one is showing; true when something was dismissed.
     * Last writer wins - only one player is ever composed at a time.
     */
    var overlayDismiss: (() -> Boolean)? = null

    /** A scrub preview the user is composing, or null when no scrub is in progress. */
    var scrubPreviewMs: Long? = null
        private set

    /** Set by the overlay's seek bar: while it holds focus, LEFT/RIGHT scrub. */
    var seekFocused: Boolean = false

    /** Set by the player while a full-screen option sheet is open: keys go to the sheet. */
    var sheetOpen: Boolean = false

    /** Handle a key before the focus system sees it. Returns true when claimed. */
    fun onKeyEvent(event: KeyEvent): Boolean = when {
        event.keyCode == KeyEvent.KEYCODE_BACK -> handleBack(event)
        player() == null -> false
        event.action == KeyEvent.ACTION_UP && isScrubKey(event.keyCode) -> handleScrubRelease()
        event.action != KeyEvent.ACTION_DOWN -> false
        isMediaKey(event.keyCode) -> handleMediaKey(event.keyCode)
        sheetOpen -> false
        TvTransportState.controlsHidden -> handleWhileHidden(event.keyCode)
        else -> handleWhileVisible(event.keyCode)
    }

    /** Only a scrub this map started is committed; otherwise the release is not ours. */
    private fun handleScrubRelease(): Boolean {
        if (scrubPreviewMs == null) return false
        commitScrub()
        return true
    }

    private fun handleWhileVisible(keyCode: Int): Boolean {
        TvTransportState.reveal()
        // A scrub in progress keeps scrubbing even before focus has landed on the seek bar.
        if ((seekFocused || scrubPreviewMs != null) && isScrubKey(keyCode)) {
            scrubBy(if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) SCRUB_STEP_MS else -SCRUB_STEP_MS)
            return true
        }
        return false
    }

    private fun handleWhileHidden(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT -> { scrubBy(SCRUB_STEP_MS); true }
        KeyEvent.KEYCODE_DPAD_LEFT -> { scrubBy(-SCRUB_STEP_MS); true }
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        -> { TvTransportState.reveal(); true }
        else -> false
    }

    private fun handleBack(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            if (overlayDismiss?.invoke() == true) return true
        }
        return false
    }

    private fun isMediaKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_REWIND,
        -> true
        else -> false
    }

    private fun handleMediaKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> toggle()
            KeyEvent.KEYCODE_MEDIA_PLAY -> player()?.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> player()?.pause()
            KeyEvent.KEYCODE_MEDIA_NEXT -> CastPlayback.onNavigate?.invoke(true)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> CastPlayback.onNavigate?.invoke(false)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> skip(SCRUB_STEP_MS)
            KeyEvent.KEYCODE_MEDIA_REWIND -> skip(-SCRUB_STEP_MS)
        }
        return true
    }

    private fun player() = CastPlayback.attachedPlayer

    private fun toggle() {
        val target = player() ?: return
        if (target.isPlaying) target.pause() else target.play()
        TvTransportState.reveal()
    }

    /**
     * Move the scrub preview, starting from live playback on the first press. Accumulated and
     * committed once on release: one seek per gesture, not one per key-repeat.
     */
    private fun scrubBy(deltaMs: Long) {
        val transport = CastPlayback.transport.value
        if (transport.durationMs <= 0) return
        val from = scrubPreviewMs ?: transport.positionMs
        scrubPreviewMs = (from + deltaMs).coerceIn(0, transport.durationMs)
        TvTransportState.reveal()
    }

    private fun commitScrub() {
        val target = scrubPreviewMs ?: return
        scrubPreviewMs = null
        player()?.seekTo(target)
        CastPlayback.update { it.copy(positionMs = target) }
        TvTransportState.reveal()
    }

    private fun skip(deltaMs: Long) {
        val transport = CastPlayback.transport.value
        val end = transport.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (transport.positionMs + deltaMs).coerceIn(0, end)
        player()?.seekTo(target)
        CastPlayback.update { it.copy(positionMs = target) }
        TvTransportState.reveal()
    }

    private fun isScrubKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

    /** Cancel a scrub without committing (overlay dismissed mid-scrub). */
    fun cancelScrub() {
        scrubPreviewMs = null
    }

    private const val SCRUB_STEP_MS = 10_000L
}
