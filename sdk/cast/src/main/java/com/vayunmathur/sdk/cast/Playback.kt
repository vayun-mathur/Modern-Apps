package com.vayunmathur.sdk.cast

import android.os.Bundle

/**
 * Where playback is, for the end that cannot see the player to draw a seek bar from.
 *
 * **A deliberate second definition of `:cast:protocol`'s message of the same name, not a reuse of
 * it.** This module is the public client contract: every app that casts compiles against it, and
 * giving it a dependency on `:cast:protocol` would hand each of them the wire format, the crypto and
 * the RTP packetiser - which is the entire thing brokering exists to avoid. `:cast` depends on both
 * sides and owns the translation, so the duplication is one file wide and two functions deep, and it
 * buys a public API that cannot leak the protocol.
 *
 * Used in both directions: an app that draws into a `Surface` reports its own player with
 * [CastClient.reportPlaybackState], and an app whose media the TV is *serving* is told about the
 * television's player through [CastClient.onPlaybackState]. Which end owns the truth is whichever end
 * owns the player.
 *
 * A snapshot rather than a delta: absolute in every field, so a lost one repairs itself on the next
 * heartbeat and there is no sequence number to get wrong.
 */
data class PlaybackState(
    val positionMs: Long,
    /** Zero or less for something with no known end, which the TV draws without a bar. */
    val durationMs: Long,
    /**
     * Whether media is actually advancing.
     *
     * Report the player's own "is playing" rather than its play-when-ready flag: the receiver
     * extrapolates position between snapshots, and a stall reported as playing runs its seek bar ahead
     * of the picture and then snaps it back.
     */
    val playing: Boolean,
    /** For a spinner. Distinct from [playing], because a stall is not a pause. */
    val buffering: Boolean = false,
    /** Tempo multiplier, 1.0 being normal. */
    val speed: Float = 1f,
    /** Media volume as 0..1, the same level on both ends. */
    val volume: Float = 1f,
    /**
     * Whether there is anything to skip to.
     *
     * Carried because the TV cannot know - what comes next is this app's own idea of a queue - and a
     * remote offering a button that does nothing is worse than one offering none. Always the app's,
     * and meaningless coming *from* the television.
     */
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    /**
     * The item finished on its own, so a queue can advance.
     *
     * Only meaningful coming from the television. Its own field rather than a position-against-duration
     * reading, which cannot be told apart from a pause at the end of a track - and has no duration to
     * compare against at all while a resource is still being written.
     */
    val ended: Boolean = false,
) {
    internal fun toBundle(): Bundle = Bundle().apply {
        putLong(CastContract.KEY_POSITION_MS, positionMs)
        putLong(CastContract.KEY_DURATION_MS, durationMs)
        putBoolean(CastContract.KEY_PLAYING, playing)
        putBoolean(CastContract.KEY_BUFFERING, buffering)
        putFloat(CastContract.KEY_SPEED, speed)
        putFloat(CastContract.KEY_VOLUME, volume)
        putBoolean(CastContract.KEY_HAS_NEXT, hasNext)
        putBoolean(CastContract.KEY_HAS_PREVIOUS, hasPrevious)
        putBoolean(CastContract.KEY_ENDED, ended)
    }

    internal companion object {
        /**
         * Read one back, for the television's own playback.
         *
         * Defaults rather than refusals for the optional fields, exactly as `:cast` does going the
         * other way: an absent speed means 1x, and a snapshot rejected for lacking one would leave
         * every surface on the phone frozen.
         */
        internal fun from(data: Bundle): PlaybackState = PlaybackState(
            positionMs = data.getLong(CastContract.KEY_POSITION_MS),
            durationMs = data.getLong(CastContract.KEY_DURATION_MS),
            playing = data.getBoolean(CastContract.KEY_PLAYING),
            buffering = data.getBoolean(CastContract.KEY_BUFFERING),
            speed = data.getFloat(CastContract.KEY_SPEED, 1f),
            volume = data.getFloat(CastContract.KEY_VOLUME, 1f),
            hasNext = data.getBoolean(CastContract.KEY_HAS_NEXT),
            hasPrevious = data.getBoolean(CastContract.KEY_HAS_PREVIOUS),
            ended = data.getBoolean(CastContract.KEY_ENDED),
        )
    }
}

/**
 * A press on whichever transport the user reached for.
 *
 * Arrives from the television's remote through [CastClient.onCommand] when the app is drawing into a
 * `Surface`, and goes *to* the television through [CastClient.sendCommand] when the television is the
 * one with the player.
 *
 * [value] is the action's argument for the actions that take one - milliseconds for
 * [PlaybackAction.SeekTo], a multiplier for [PlaybackAction.SetSpeed], 0..1 for
 * [PlaybackAction.SetVolume] - and null for the rest. One nullable field rather than three
 * mostly-absent ones, which cannot then disagree.
 */
data class PlaybackCommand(
    val action: PlaybackAction,
    val value: Double? = null,
) {
    internal fun toBundle(): Bundle = Bundle().apply {
        putInt(CastContract.KEY_ACTION, action.wire)
        value?.let { putDouble(CastContract.KEY_ACTION_VALUE, it) }
    }

    /** Bundle parsing helpers for [PlaybackCommand]. */
    companion object {
        /**
         * Read one back, or null for an action this build does not know.
         *
         * Null rather than an exception on purpose: a newer Cast gaining a button must not crash an
         * app built against an older SDK, and ignoring the press is the correct behaviour anyway.
         */
        internal fun from(data: Bundle?): PlaybackCommand? {
            if (data == null) return null
            val action = PlaybackAction.of(data.getInt(CastContract.KEY_ACTION, -1)) ?: return null
            val value = if (data.containsKey(CastContract.KEY_ACTION_VALUE)) {
                data.getDouble(CastContract.KEY_ACTION_VALUE)
            } else {
                null
            }
            return PlaybackCommand(action, value)
        }
    }
}

/**
 * What the transport asked for.
 *
 * The [wire] value is what crosses the `Bundle`; the Kotlin names are free to change. An enum is not
 * put in the `Bundle` directly because a `Serializable` enum there is a class-loading problem waiting
 * for a client compiled against a different SDK version.
 */
enum class PlaybackAction(internal val wire: Int) {
    Play(CastContract.ACTION_PLAY),
    Pause(CastContract.ACTION_PAUSE),

    /**
     * Whichever of the two the end holding the player is not currently doing.
     *
     * Its own action rather than the sender resolving it from the last snapshot: that snapshot can be
     * half a second old, and two quick presses resolved against it would both send the same thing.
     */
    Toggle(CastContract.ACTION_TOGGLE),

    /** Absolute position, in milliseconds. */
    SeekTo(CastContract.ACTION_SEEK_TO),

    /** A discrete skip, by whatever interval the end holding the player uses for one. */
    SkipForward(CastContract.ACTION_SKIP_FORWARD),
    SkipBack(CastContract.ACTION_SKIP_BACK),

    /** Always the app's to answer, whichever way it arrived: the queue is the app's. */
    Next(CastContract.ACTION_NEXT),
    Previous(CastContract.ACTION_PREVIOUS),

    /** A tempo multiplier. */
    SetSpeed(CastContract.ACTION_SET_SPEED),

    /**
     * A 0..1 level for *both* ends.
     *
     * The app is expected to move its own media volume to match, so the level survives the session
     * ending and local playback resumes where the television left it.
     */
    SetVolume(CastContract.ACTION_SET_VOLUME),
    ;

    internal companion object {
        fun of(wire: Int): PlaybackAction? = entries.firstOrNull { it.wire == wire }
    }
}
