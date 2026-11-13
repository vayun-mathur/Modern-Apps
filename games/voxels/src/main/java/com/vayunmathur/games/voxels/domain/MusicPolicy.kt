package com.vayunmathur.games.voxels.domain

/** The game's standing with Android audio focus, as the music player sees it. */
enum class MusicFocus {
    /** Not requested yet (or abandoned on stop). */
    NONE,
    /** Granted: play normally. */
    GAINED,
    /** Another app wants a short, mixable sound (navigation prompt): keep playing, quietly. */
    DUCKED,
    /** A call or another player took over briefly: pause, resume when it hands focus back. */
    LOST_TRANSIENT,
    /** Another app started its own playback: stay silent until the game is next started. */
    LOST,
}

/** Music only plays while the game is on screen and holds audio focus (ducked counts). */
fun musicShouldPlay(foreground: Boolean, focus: MusicFocus): Boolean =
    foreground && (focus == MusicFocus.GAINED || focus == MusicFocus.DUCKED)

/** Volume multiplier for the current focus state. */
fun musicVolumeScale(focus: MusicFocus): Float = if (focus == MusicFocus.DUCKED) DUCK_SCALE else 1f

private const val DUCK_SCALE = 0.2f
