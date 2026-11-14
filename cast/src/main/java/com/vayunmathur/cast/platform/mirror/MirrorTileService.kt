package com.vayunmathur.cast.platform.mirror

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.vayunmathur.cast.MainActivity
import com.vayunmathur.cast.R
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.platform.CastController
import com.vayunmathur.cast.platform.MirrorPhase
import com.vayunmathur.cast.platform.connect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Start and stop mirroring from the shade.
 *
 * A close transcription of `ShareReceiveTileService` with **one deliberate divergence**: `:share`
 * treats its persisted flag as authoritative and reconciles it headlessly after a reboot. Mirroring
 * cannot do that, because screen-capture consent is single-use and unreplayable. So tile state is
 * read from the in-process [CastController] instead: the foreground service keeps the process alive
 * for as long as mirroring is on, which means that if the process died, mirroring is dead and
 * `STATE_INACTIVE` is the truth. Only the *target* is persisted.
 *
 * A tile can also never start mirroring by itself - consent needs an Activity - so turning it on
 * connects first and then hands off to [MirrorConsentActivity] via `startActivityAndCollapse`.
 * The connect cannot be skipped: consent without a session dies silently in `startSession`, which
 * is exactly the dead tile tap - it only ever worked when the app process happened to already hold
 * one. Turning it off needs no Activity and goes straight to the controller.
 *
 * No `requestListeningState()`: nothing in this repo uses it and the house idiom is to reconcile on
 * the next listen.
 */
class MirrorTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val phase = CastController.mirrorPhase.value
        // Failed is drawn as inactive, so a tap on it has to *start* rather than stop - otherwise
        // the first press after a failure looks like it did nothing.
        if (phase == MirrorPhase.Mirroring || phase == MirrorPhase.Negotiating) {
            CastController.stopMirroring(this)
            // stopMirroring is asynchronous, so waiting for Idle rather than reading the phase
            // straight back: refreshing now would re-publish the state we are leaving.
            scope.launch {
                CastController.mirrorPhase.first { it != MirrorPhase.Mirroring }
                refreshTile()
            }
            return
        }
        scope.launch {
            // Nothing to mirror to until a target has been chosen once in the app, and there is no
            // device list in the shade to choose one from.
            val stored = MirrorPreferences.target(this@MirrorTileService)
            if (stored == null) {
                openApp()
                return@launch
            }
            // The app already holds a live session to this TV: consent is all that is missing.
            if (isLiveTo(stored)) {
                startConsent()
                return@launch
            }
            // Otherwise connect first. Re-resolved rather than reused, because the TV binds an
            // ephemeral control port - the stored one is stale after any TV restart.
            val fresh = resolveTarget(stored) ?: run {
                openApp()
                return@launch
            }
            CastController.connect(this@MirrorTileService, fresh, thenMirror = false)
            // Consent goes through `collapseTo`, not the connect's own handoff: that one is a plain
            // background start, which the platform drops, while collapsing the shade is the tile's
            // sanctioned path to an Activity. Consent must be collected afresh every time.
            if (awaitPaired(fresh) && CastController.device.value?.id == fresh.id) {
                startConsent()
            } else {
                // AwaitingCode needs the app's code card; anything else wants the app's status
                // card rather than a silent shade.
                openApp()
            }
        }
    }

    /** True when the controller already holds a live session to [stored]. */
    private fun isLiveTo(stored: CastDevice): Boolean {
        if (CastController.device.value?.id != stored.id) return false
        val phase = CastController.sessionState.value.phase
        return phase == ClientPhase.Paired || phase == ClientPhase.Streaming
    }

    /**
     * The TV's current address for [stored], matched by stable id.
     *
     * Null when the TV is not announcing itself - off, rebooting, or off the network - in which
     * case opening the app, with its live list, is the honest answer.
     */
    private suspend fun resolveTarget(stored: CastDevice): CastDevice? =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            CastController.discovery(this@MirrorTileService).discover()
                .firstOrNull { it.id == stored.id }
        }

    /**
     * Whether the session `connect` just opened reached a mirrorable phase.
     *
     * Waits for the connecting flag first, so a still-live pre-teardown state cannot be mistaken
     * for the new session's answer; false on timeout, treated like a failure.
     */
    private suspend fun awaitPaired(fresh: CastDevice): Boolean {
        val paired = withTimeoutOrNull(PAIR_WAIT_MS) {
            // A concurrent in-app connect may have already finished - or `connect` may have
            // early-returned on its live session - in which case no flag will ever rise.
            val now = CastController.sessionState.value
            val alreadyPaired = !CastController.isConnecting.value &&
                CastController.device.value?.id == fresh.id &&
                (now.phase == ClientPhase.Paired || now.phase == ClientPhase.Streaming)
            if (alreadyPaired) {
                return@withTimeoutOrNull true
            }
            CastController.isConnecting.first { it }
            val terminal = CastController.sessionState.first {
                it.phase != ClientPhase.Idle && it.phase != ClientPhase.Connecting
            }
            terminal.phase == ClientPhase.Paired || terminal.phase == ClientPhase.Streaming
        }
        return paired == true
    }

    private fun startConsent() {
        collapseTo(MirrorConsentActivity.intent(this))
    }

    /**
     * Sets only `state` and `subtitle`; the label and icon come from the manifest, as they do in
     * `:share`.
     */
    private fun refreshTile() {
        scope.launch {
            val tile = qsTile ?: return@launch
            val phase = CastController.mirrorPhase.value
            val device = CastController.device.value
            val target = MirrorPreferences.target(this@MirrorTileService)
            tile.state = when (phase) {
                MirrorPhase.Mirroring, MirrorPhase.Negotiating -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            tile.subtitle = when {
                phase == MirrorPhase.Mirroring -> device?.friendlyName
                    ?: getString(R.string.cast_tile_mirroring)
                phase == MirrorPhase.Negotiating -> getString(R.string.cast_tile_starting)
                target == null -> getString(R.string.cast_tile_no_target)
                else -> target.friendlyName
            }
            tile.updateTile()
        }
    }

    private fun openApp() {
        collapseTo(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Launch [intent] and close the shade.
     *
     * One call site for the version split, because the `PendingIntent` overload only exists from
     * API 34 and `minSdk` is 31 - so the deprecated one is genuinely the only option below that,
     * and suppressing it twice would be twice as easy to lose track of.
     */
    @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
    private fun collapseTo(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private companion object {
        /**
         * How long the tile waits for the TV to answer mDNS.
         *
         * Bounded because the shade is not a screen: a browse the app ties to a visible list can
         * run indefinitely, but a tile tap that hangs is a dead tap with extra steps.
         */
        const val RESOLVE_TIMEOUT_MS = 8_000L

        /**
         * How long the tile waits for the handshake it started.
         *
         * Covers a full code pairing round trip with time to spare; a remembered-key pair is
         * seconds. Past this, the app - with its status card - is where the user belongs.
         */
        const val PAIR_WAIT_MS = 90_000L
    }
}
