package com.vayunmathur.games.voxels.platform

import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import com.vayunmathur.games.voxels.domain.MusicFocus
import com.vayunmathur.games.voxels.domain.musicShouldPlay
import com.vayunmathur.games.voxels.domain.musicVolumeScale

// Background music manager. Cycles the track playlist ambiently at a low volume; a jukebox disc
// temporarily takes over (pausing ambient) until stopped. Music only plays while the game is in the
// foreground and holds audio focus: it pauses on Home, the lock screen and calls, and resumes when
// the game comes back. Best-effort; never throws into the UI.
// MediaPlayer/AudioManager signal failure with assorted runtime exceptions; all are swallowed here.
@Suppress("TooGenericExceptionCaught", "TooManyFunctions")
object MusicFx {
    private const val AMBIENT_VOLUME = 0.45f
    private const val DISC_VOLUME = 0.8f

    private var ambient: MediaPlayer? = null
    private var disc: MediaPlayer? = null
    private var currentDisc: String? = null
    private val prepared = HashSet<MediaPlayer>()
    private var assets: AssetManager? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var foreground = false
    private var focus = MusicFocus.NONE
    // User volume from the settings sheet, 0..1.
    private var userVolume = 1f
    private var idx = 0
    private val playlist = listOf(
        "mcl_forest.ogg", "mcl_piano.ogg", "mcl_winter.ogg", "mcl_gift.ogg",
        "golden.ogg", "mcl_mining.ogg", "lullaby.ogg"
    )
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        focus = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> MusicFocus.GAINED
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> MusicFocus.DUCKED
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> MusicFocus.LOST_TRANSIENT
            else -> MusicFocus.LOST
        }
        apply()
    }

    private fun make(assets: AssetManager, asset: String, loop: Boolean, onDone: (() -> Unit)?): MediaPlayer? {
        return try {
            val afd = assets.openFd("music/$asset")
            val p = MediaPlayer()
            p.setAudioAttributes(attributes)
            p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            p.isLooping = loop
            p.setOnPreparedListener {
                prepared += it
                apply()
            }
            p.setOnErrorListener { _, _, _ -> true }
            if (onDone != null) p.setOnCompletionListener { onDone() }
            p.prepareAsync()
            p
        } catch (_: Throwable) { null }
    }

    /** The game came on screen: take audio focus and start (or resume) the music. */
    fun onForeground(c: Context) {
        assets = c.applicationContext.assets
        foreground = true
        requestFocus(c.applicationContext)
        if (ambient == null && disc == null) playNextAmbient() else apply()
    }

    /** The game left the screen (Home, lock, another app): pause and give focus back. */
    fun onBackground() {
        foreground = false
        apply()
        abandonFocus()
    }

    /** Settings: music volume, 0..1. */
    fun setVolume(v: Float) {
        userVolume = v.coerceIn(0f, 1f)
        apply()
    }

    private fun requestFocus(c: Context) {
        val am = audioManager
            ?: (c.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.also { audioManager = it }
        if (am == null) { focus = MusicFocus.GAINED; return }
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener)
            .setWillPauseWhenDucked(false)
            .build().also { focusRequest = it }
        val result = try { am.requestAudioFocus(req) } catch (_: Throwable) { AudioManager.AUDIOFOCUS_REQUEST_FAILED }
        focus = when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> MusicFocus.GAINED
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> MusicFocus.LOST_TRANSIENT
            else -> MusicFocus.LOST
        }
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        focusRequest?.let { try { am.abandonAudioFocusRequest(it) } catch (_: Throwable) {} }
        focus = MusicFocus.NONE
    }

    // Brings every player in line with the policy: the active one plays (at the right volume) only
    // when allowed; everything else is paused.
    private fun apply() {
        val play = musicShouldPlay(foreground, focus)
        val scale = musicVolumeScale(focus) * userVolume
        val active = disc ?: ambient
        for (p in listOfNotNull(ambient, disc)) {
            try {
                val vol = (if (p === disc) DISC_VOLUME else AMBIENT_VOLUME) * scale
                p.setVolume(vol, vol)
                if (p === active && play && p in prepared) { if (!p.isPlaying) p.start() }
                else if (p.isPlaying) p.pause()
            } catch (_: Throwable) {}
        }
    }

    private fun release(p: MediaPlayer?) {
        if (p == null) return
        prepared -= p
        try { p.release() } catch (_: Throwable) {}
    }

    private fun playNextAmbient() {
        val a = assets ?: return
        val asset = playlist[idx % playlist.size]
        idx++
        release(ambient)
        ambient = make(a, asset, loop = false, onDone = { playNextAmbient() })
    }

    // Jukebox: same disc again -> stop and resume ambient; a new disc -> take over; null -> resume.
    fun toggle(c: Context, asset: String?) {
        if (asset == null || asset == currentDisc) { stopDisc(); resumeAmbient(); return }
        stopDisc()
        disc = make(c.applicationContext.assets, asset, loop = true, onDone = null)
        currentDisc = asset
        apply()
    }

    private fun stopDisc() {
        release(disc)
        disc = null; currentDisc = null
    }

    private fun resumeAmbient() {
        if (ambient != null) apply() else playNextAmbient()
    }

    fun stop() {
        stopDisc()
        release(ambient)
        ambient = null
        foreground = false
        abandonFocus()
    }
}
