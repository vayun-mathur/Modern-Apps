package com.vayunmathur.cast.tv.platform

import android.os.Bundle
import com.vayunmathur.library.log.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.MaterialTheme
import com.vayunmathur.cast.protocol.PlaybackAction
import com.vayunmathur.cast.protocol.PlaybackCommand
import com.vayunmathur.cast.tv.ui.CastTvTheme
import com.vayunmathur.cast.tv.ui.NowPlayingScreen
import com.vayunmathur.cast.tv.ui.PlaybackClock
import com.vayunmathur.cast.tv.ui.TransportOverlay
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "MirrorActivity"

/**
 * The picture: one full-screen `SurfaceView` the decoder writes straight into.
 *
 * **The surface is deliberately not Compose, and the overlay deliberately is.** The surface is one
 * handle given to `MediaCodec`; routing it through `AndroidView` would put a recomposition path
 * between the decoder and the display for no benefit. The transport controls have the opposite
 * requirement - they are state-driven and change constantly - so they are a *second child* of the same
 * `FrameLayout`, a `ComposeView` above the surface. The decoder's path is untouched by this; only the
 * overlay recomposes.
 *
 * **Letterboxing is done here, and that is the point.** The old sender scaled the phone's portrait
 * screen into a 1920x1080 frame and sent mostly black pixels, because a Cast receiver answers
 * `scaling: "sender"` and will not pad for you. Owning the receiver means the phone can send its own
 * aspect ratio at full resolution and the TV pads it - so none of the encoded frame is wasted.
 *
 * **This is also the app's whole input surface.** [onKeyDown]/[onKeyUp] handle the remote directly rather
 * than moving a focus ring between buttons, because a media overlay's D-pad presses are gestures -
 * left seeks, up is volume - and there is nothing to focus. Keys are only claimed while a phone is
 * actually reporting playback, so screen mirroring keeps the ordinary behaviour of every key.
 *
 * **A press while the controls are hidden wakes them rather than toggling playback.** See
 * [onKeyDown]; an audio-only session never has hidden controls at all, because there is no
 * picture for them to get out of the way of.
 */
class MirrorActivity : ComponentActivity(), SurfaceHolder.Callback {

    private lateinit var container: FrameLayout
    private lateinit var surfaceView: SurfaceView

    /**
     * The frame size the sender said it would send, remembered so the letterbox can be recomputed.
     *
     * **Load-bearing.** The letterbox used to be computed only when `ReceiverController.state` emitted,
     * which is once per session - and on this box that emission lands a few milliseconds after
     * [preferLargestDisplayMode] has asked for 3840x2160 and before the switch has taken effect, so the
     * panel still measures 1920x1080. The state never changes again, so a stale layout was final: a
     * 1920x1080 surface left sitting on a 4K panel. Holding the frame size here is what lets the panel
     * tell us when it has finished resizing.
     */
    private var frameWidth = 0
    private var frameHeight = 0

    /**
     * The panel mode the current session is being displayed in, so a redundant request is not
     * re-issued on every state emission. `-1` when none has been asked for.
     *
     * A mode change blanks the screen for around a second, so asking for the mode that is already
     * set is not merely wasteful - it is a visible fault.
     */
    private var requestedModeId = -1

    /** Whether the controls are on screen. Any key press reveals them; a timer takes them away. */
    private var overlayVisible by mutableStateOf(false)

    /**
     * Whether the controls stay up rather than timing out.
     *
     * **True for an audio-only session, because there is nothing behind them to get out of the way
     * of.** The auto-hide exists so a seek bar does not sit on top of a film; with no picture it only
     * takes the one thing on screen away and leaves the user pressing a key to find out where playback
     * is. Read from the session rather than fixed, so screen mirroring and video are unchanged.
     */
    private var overlayPinned = false

    /**
     * A scrub the user is still composing, in milliseconds, or -1 when none is in progress.
     *
     * **Accumulate here and commit on release.** Sending a seek per keypress would put a storm of them
     * on the control channel and into an ExoPlayer that is simultaneously feeding a hardware encoder;
     * holding a local preview and sending one `SeekTo` at the end is both kinder and what the user
     * means by holding the button down.
     *
     * Held past the commit until the phone's own answer arrives - see [scrubCommittedAtMs].
     */
    private var scrubPreviewMs by mutableLongStateOf(NO_SCRUB)

    /**
     * When the last scrub was committed, so its preview can be held until the phone confirms.
     *
     * Dropping the preview the instant the seek went out made the bar jump backwards: snapshots arrive
     * twice a second, so for up to half a second the newest one still described the *pre-seek*
     * position, and the bar snapped back there before the next one pulled it forward. Keeping the
     * preview until a snapshot that postdates the commit arrives makes the bar move once, to where the
     * user put it.
     */
    private var scrubCommittedAtMs = 0L

    private var hideJob: Job? = null

    /**
     * Back dismisses the controls before it leaves the session (see
     * [onKeyDown]). Registered as a callback rather than intercepted
     * as a raw KEYCODE_BACK in the key dispatch, so predictive-back handling
     * stays with the framework; enabled only while dismissible controls are
     * on screen, otherwise back finishes the Activity as usual.
     */
    private val backPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            hideOverlay()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
        updateBackCallback()
        surfaceView = SurfaceView(this).apply { holder.addCallback(this@MirrorActivity) }
        // An audio-only session has nothing to draw. The surface is not merely hidden but never
        // added: an unattached SurfaceView creates no surface, so `surfaceCreated` never fires and
        // the receiver is never handed somewhere to draw. A full-screen black rectangle over silence
        // would read as a fault rather than as music.
        val hasVideo = (ReceiverController.state.value.phase as? ReceiverPhase.Mirroring)?.hasVideo != false
        overlayPinned = !hasVideo
        overlayVisible = overlayPinned
        container = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            if (hasVideo) {
                addView(
                    surfaceView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.Gravity.CENTER,
                    ),
                )
            }
            addView(overlayView(), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(container)
        matchDisplayModeTo(frameWidth, frameHeight, 0f)
        // The display-mode change above is asynchronous, and so is the first layout pass. Either one
        // finishing is a reason to redo the letterbox against the size the panel actually ended up.
        container.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                fitToFrame(frameWidth, frameHeight)
            }
        }
        // A TV has no navigation bar to keep clear of, but some launchers still overlay a status bar,
        // and a mirrored screen should be the whole panel.
        WindowCompat.getInsetsController(window, container).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        // Finishing when the session ends is what returns the user to the idle screen rather than
        // leaving the last frame frozen on a dead surface.
        lifecycleScope.launch {
            ReceiverController.state.collectLatest { state ->
                if (state.phase is ReceiverPhase.Mirroring) {
                    // Settled here as well as in onCreate: the phase may not have arrived yet when the
                    // Activity is built, and this is the first moment the session is known for certain.
                    overlayPinned = !state.phase.hasVideo
                    if (overlayPinned) overlayVisible = true
                    updateBackCallback()
                    // Before the letterbox, because switching the panel changes the size the
                    // letterbox is computed against - and the layout listener installed above will
                    // recompute it once the switch lands.
                    matchDisplayModeTo(
                        state.phase.width,
                        state.phase.height,
                        state.phase.frameRate,
                    )
                    fitToFrame(state.phase.width, state.phase.height)
                } else {
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ReceiverController.detachSurface()
    }

    /**
     * The now-playing screen and the controls, as layers above the picture.
     *
     * Composes to nothing at all until the phone reports playback, which is what keeps both off a
     * mirrored phone screen: screen mirroring has no transport, so it never sends a snapshot.
     *
     * **The now-playing screen is behind the controls and only for an audio session**, where there is
     * no `SurfaceView` in the window at all and this `ComposeView` is the whole of it. Over video it
     * would be an opaque rectangle across the film. It renders only the metadata that names what the
     * player is actually playing - see `ReceiverUiState.nowPlayingForCurrentItem` - so nothing appears
     * until the phone has described the track, and the existing headline in the overlay stays the
     * fallback for an old phone, a non-music app, and the moment before the first snapshot lands.
     */
    private fun overlayView(): ComposeView = ComposeView(this).apply {
        setContent {
            CastTvTheme {
                val state by ReceiverController.state.collectAsState()
                val playback = state.playback
                // Released in an effect rather than inline: writing state during composition is how a
                // recomposition loop starts.
                LaunchedEffect(playback?.receivedAtMs) {
                    val arrivedAt = playback?.receivedAtMs ?: return@LaunchedEffect
                    if (scrubCommittedAtMs != 0L && arrivedAt > scrubCommittedAtMs) {
                        scrubCommittedAtMs = 0L
                        scrubPreviewMs = NO_SCRUB
                    }
                }
                if (playback == null) return@CastTvTheme
                val mirroring = state.phase as? ReceiverPhase.Mirroring
                val nowPlaying = state.nowPlayingForCurrentItem
                // Read from the state rather than from `overlayPinned`, which is a plain field that
                // composition does not observe: the same condition, but one that recomposes when the
                // session's own answer to it changes.
                val audioOnly = mirroring?.hasVideo == false
                // One clock for both layers, passed down as a lambda so neither the artwork nor the
                // lyrics column ends up in a scope that invalidates per frame. See `PlaybackClock`.
                PlaybackClock(
                    snapshot = playback,
                    scrubPreviewMs = scrubPreviewMs.takeIf { it != NO_SCRUB },
                ) { positionMs ->
                    val transport: @Composable (Modifier, Boolean) -> Unit = { m, scrim ->
                        TransportOverlay(
                            snapshot = playback,
                            sourceName = mirroring?.sourceName.orEmpty(),
                            nowPlaying = nowPlaying,
                            positionMs = positionMs,
                            scrubbing = scrubPreviewMs != NO_SCRUB,
                            scrim = scrim,
                            modifier = m,
                        )
                    }
                    if (nowPlaying != null && audioOnly) {
                        // **Stacked, not overlaid.** There is no picture for the bar to get out of the
                        // way of, so it takes the height it needs and the lyrics take the rest -
                        // which is the only way they can fill the panel without a reserved constant
                        // that is wrong on every screen size but the one it was measured on.
                        //
                        // **And the background is painted here, once, for the whole window** - so the
                        // transport sits on the same sheet of colour as the cover and the lyrics rather
                        // than on a black band under them. Which is also why the bar is asked for no
                        // scrim: with nothing behind it to see through, a gradient would draw exactly
                        // the seam this is avoiding.
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface),
                        ) {
                            NowPlayingScreen(
                                nowPlaying = nowPlaying,
                                snapshot = playback,
                                sourceName = mirroring?.sourceName.orEmpty(),
                                positionMs = positionMs,
                                artwork = state.artwork,
                                modifier = Modifier.weight(1f),
                            )
                            if (overlayVisible) transport(Modifier, false)
                        }
                    } else if (overlayVisible) {
                        // Over the picture, where a scrim is the whole point.
                        Box(modifier = Modifier.fillMaxSize()) {
                            transport(Modifier.align(Alignment.BottomCenter), true)
                        }
                    }
                }
            }
        }
    }

    /**
     * The remote, in full.
     *
     * Only claims a key while the phone is reporting playback - otherwise every one of these falls
     * through to the system, which is what a mirrored phone screen needs. `BACK` is not handled
     * in the key handlers at all: [backPressedCallback] dismisses the controls before back will leave the
     * session, because a user who has just brought up a seek bar and presses back means
     * "put that away", not "stop watching".
     *
     * Left and right accumulate into [scrubPreviewMs] and commit on release; everything else is one
     * message per press. `ACTION_MULTIPLE` is not handled: a held D-pad repeats as `ACTION_DOWN`.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        ReceiverController.state.value.playback ?: return super.onKeyUp(keyCode, event)
        // Committing the scrub is the *release*: the key-up half of a scrub press.
        if (isScrubKey(keyCode)) {
            commitScrub()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val playback = ReceiverController.state.value.playback
            ?: return super.onKeyDown(keyCode, event)

        // Any press at all brings the controls up, and re-arms the timer that takes them away.
        val waking = !overlayVisible
        revealOverlay()
        // **A press that wakes the controls does not also toggle playback.** With the overlay hidden
        // there is nothing on screen to say what a centre press is aimed at, so it reads as "show me
        // the controls" - and pausing the film instead is the kind of surprise that costs the user the
        // place they were watching. Only the toggle is held back: a dedicated skip or volume key is
        // unambiguous whether anything is on screen or not, and a scrub reveals the very bar it moves.
        // An audio-only session never reaches this, because its controls are pinned and never hidden.
        if (waking && isToggleKey(event.keyCode)) return true

        return handleKeyDown(event, playback)
    }

    /** One press while the controls are up: toggles, seeks, skips, tracks or volume. */
    private fun handleKeyDown(event: KeyEvent, playback: PlaybackSnapshot): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            -> ReceiverController.send(PlaybackCommand(PlaybackAction.Toggle))

            KeyEvent.KEYCODE_MEDIA_PLAY -> ReceiverController.send(PlaybackCommand(PlaybackAction.Play))
            KeyEvent.KEYCODE_MEDIA_PAUSE -> ReceiverController.send(PlaybackCommand(PlaybackAction.Pause))

            KeyEvent.KEYCODE_DPAD_RIGHT -> scrubBy(SCRUB_STEP_MS, playback)
            KeyEvent.KEYCODE_DPAD_LEFT -> scrubBy(-SCRUB_STEP_MS, playback)

            // The remote's dedicated keys, where it has them. Distinct from a D-pad scrub: these are
            // a discrete "skip", so the phone's own interval applies rather than an accumulated one.
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> ReceiverController.skip(forward = true)
            KeyEvent.KEYCODE_MEDIA_REWIND -> ReceiverController.skip(forward = false)

            KeyEvent.KEYCODE_MEDIA_NEXT ->
                ReceiverController.send(PlaybackCommand(PlaybackAction.Next))
            KeyEvent.KEYCODE_MEDIA_PREVIOUS ->
                ReceiverController.send(PlaybackCommand(PlaybackAction.Previous))

            // The shared level, not this box's own volume: the phone owns it, so the press goes there
            // and the gain follows on the next snapshot.
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_VOLUME_UP,
            -> return pressVolume(event, up = true)

            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            -> return pressVolume(event, up = false)

            else -> return super.onKeyDown(event.keyCode, event)
        }
        return true
    }

    /**
     * Nudge the shared volume level, falling through to the box's own volume control when there
     * is no session to own it.
     */
    private fun pressVolume(event: KeyEvent, up: Boolean): Boolean {
        if (ReceiverController.nudgeVolume(up = up)) return true
        return super.onKeyDown(event.keyCode, event)
    }

    private fun isScrubKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

    /** The presses that mean "the other one" - and so mean nothing until the controls are visible. */
    private fun isToggleKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE

    /**
     * Move the preview position, starting it from where playback actually is on the first press.
     *
     * Interpolated rather than taken from the last report, so a scrub begun mid-playback starts from
     * the frame on screen rather than from up to half a second behind it.
     */
    private fun scrubBy(deltaMs: Long, playback: PlaybackSnapshot) {
        val duration = playback.state.durationMs
        if (duration <= 0) return
        val from = scrubPreviewMs.takeIf { it != NO_SCRUB }
            ?: playback.positionAt(System.currentTimeMillis())
        scrubPreviewMs = (from + deltaMs).coerceIn(0, duration)
    }

    private fun commitScrub() {
        val target = scrubPreviewMs
        if (target == NO_SCRUB) return
        // The preview is *kept*, and released by the composition above once a snapshot postdating this
        // moment arrives. A seek the phone refuses - which it does while its own slider is under a
        // thumb - is therefore corrected by the next heartbeat rather than left showing for ever.
        scrubCommittedAtMs = System.currentTimeMillis()
        ReceiverController.send(
            PlaybackCommand(PlaybackAction.SeekTo, value = target.toDouble()),
        )
        revealOverlay()
    }

    private fun revealOverlay() {
        overlayVisible = true
        updateBackCallback()
        hideJob?.cancel()
        hideJob = null
        if (overlayPinned) return
        hideJob = lifecycleScope.launch {
            delay(OVERLAY_AUTO_HIDE_MS)
            // A scrub in progress is an interaction, however long the user has been holding the key.
            if (scrubPreviewMs == NO_SCRUB) {
                overlayVisible = false
                updateBackCallback()
            }
        }
    }

    private fun hideOverlay() {
        hideJob?.cancel()
        hideJob = null
        scrubPreviewMs = NO_SCRUB
        scrubCommittedAtMs = 0L
        overlayVisible = false
        updateBackCallback()
    }

    /** Back only dismisses controls that are showing and can be dismissed. */
    private fun updateBackCallback() {
        backPressedCallback.isEnabled = overlayVisible && !overlayPinned
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.status(TAG, "surface ready")
        ReceiverController.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // A rotation or a resolution change hands back a *different* surface, so the controller has
        // to be told again - re-attaching the same one is harmless.
        ReceiverController.attachSurface(holder.surface)
        // And the panel may be a different size than when the letterbox was last worked out.
        fitToFrame(frameWidth, frameHeight)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.status(TAG, "surface gone")
        ReceiverController.detachSurface()
    }

    /**
     * Size the surface to the sender's aspect ratio, centred, with black either side.
     *
     * Two different sizes are set here and the distinction is the whole point:
     *
     *  - The **layout** size is the letterbox. A portrait phone appears as a tall strip in the middle
     *    of the panel, which is what mirroring a phone should look like.
     *  - The **buffer** size, via [SurfaceHolder.setFixedSize], stays at the sender's own
     *    resolution. Without it the surface buffer is the view's size, so a 1344x2992 frame was
     *    squeezed into roughly 486x1080 by `MediaCodec` before it ever reached the panel - the
     *    phone's extra resolution was encoded, transmitted, and then thrown away on arrival. With it
     *    the composer scales a full-resolution buffer to the output instead, and can put it on a
     *    hardware overlay rather than through the 1080p UI framebuffer.
     *
     * Whether that last step actually happens is the composer's decision, not ours, so it has to be
     * checked on the device with `dumpsys SurfaceFlinger` rather than assumed.
     */
    private fun fitToFrame(sourceWidth: Int, sourceHeight: Int) {
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        frameWidth = sourceWidth
        frameHeight = sourceHeight
        container.post {
            val availableWidth = container.width
            val availableHeight = container.height
            if (availableWidth == 0 || availableHeight == 0) return@post
            val frameAspect = sourceWidth.toDouble() / sourceHeight
            val screenAspect = availableWidth.toDouble() / availableHeight
            val (width, height) = if (frameAspect > screenAspect) {
                // Wider than the panel: full width, bars top and bottom.
                availableWidth to (availableWidth / frameAspect).toInt()
            } else {
                (availableHeight * frameAspect).toInt() to availableHeight
            }
            // Nothing to do, and worth checking: this is now called from a layout listener, so
            // re-applying the same layoutParams would schedule another layout pass and loop.
            val current = surfaceView.layoutParams
            if (current != null && current.width == width && current.height == height) return@post
            surfaceView.holder.setFixedSize(sourceWidth, sourceHeight)
            surfaceView.layoutParams = FrameLayout.LayoutParams(
                width,
                height,
                android.view.Gravity.CENTER,
            )
            Log.status(
                TAG,
                "buffer ${sourceWidth}x$sourceHeight shown as ${width}x$height " +
                    "on a ${availableWidth}x$availableHeight panel",
            )
        }
    }

    /**
     * Put the panel into the mode the sender is actually streaming, so nothing is rescaled.
     *
     * **This used to ask for the largest mode and deliberately ignore refresh rate**, on the
     * reasoning that a mirror wants the whole panel and that every rate a TV offers is close
     * enough to a multiple of 30. Both halves stopped being true once the sender began composing a
     * desktop for a mode the *user* picked: the largest mode is then the wrong one whenever they
     * chose something smaller, and the rate is the point rather than a detail - this panel lists
     * 3840x2160 at eight rates, and the sender has already refused the ones it cannot encode.
     *
     * Matching both means the decoded frame maps one-to-one onto the screen: no scaler in the
     * path, and no cadence mismatch between a 30 fps stream and a 60 Hz panel.
     *
     * Falls back to the largest mode when the session's geometry is not known yet (this runs once
     * from `onCreate`, before the first state emission) or when nothing matches - an older sender
     * whose rate is 0, or a size the panel does not list.
     */
    private fun matchDisplayModeTo(width: Int, height: Int, frameRate: Float) {
        val modes = display?.supportedModes ?: return
        if (modes.size <= 1) return
        val exact = modes.firstOrNull { mode ->
            mode.physicalWidth == width &&
                mode.physicalHeight == height &&
                frameRate > 0f &&
                abs(mode.refreshRate - frameRate) <= RATE_TOLERANCE
        }
        val chosen = exact
            ?: modes.maxByOrNull { it.physicalWidth.toLong() * it.physicalHeight }
            ?: return
        if (chosen.modeId == requestedModeId) return
        requestedModeId = chosen.modeId
        window.attributes = window.attributes.apply { preferredDisplayModeId = chosen.modeId }
        Log.status(
            TAG,
            "asked for ${chosen.physicalWidth}x${chosen.physicalHeight} @ " +
                "${chosen.refreshRate}Hz of ${modes.size} modes" +
                if (exact == null) " (no mode matches ${width}x$height@$frameRate)" else "",
        )
    }

    private companion object {
        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT

        /**
         * How far a panel mode's rate may sit from the stream's and still be that mode.
         *
         * The rate crosses the wire as a float and is compared against a value the panel reports
         * as 59.94006, so exact equality is not something to rely on. Small enough that 59.94 and
         * 60 - which are genuinely different modes on this screen - never match each other.
         */
        const val RATE_TOLERANCE = 0.2f

        /** No scrub in progress. A sentinel rather than a nullable, so the Compose state is primitive. */
        const val NO_SCRUB = -1L

        /**
         * How far one D-pad press moves the preview.
         *
         * Smaller than a skip on purpose: this is a *scrub*, and the user is aiming. Holding the key
         * repeats it, so a long press still crosses a long video quickly.
         */
        const val SCRUB_STEP_MS = 5_000L

        /** Matches YouPipe's own `CONTROLS_AUTO_HIDE_DELAY_MS`, doubled for a remote's slower aim. */
        const val OVERLAY_AUTO_HIDE_MS = 4_000L
    }
}
