package com.vayunmathur.tuner.domain

/** What [ChordPresenter.update] did to the displayed reading. */
enum class ChordDisplay {
    /** Nothing to redraw. The frame was absorbed by hysteresis or by the hold. */
    UNCHANGED,

    /** A new reading is on screen. */
    UPDATED,

    /** The hold expired. The tab goes back to listening and the detector should be reset. */
    CLEARED,
}

/**
 * Decides what the Chord tab shows, given the detector's per-frame readings (TUNER_SPEC I.7).
 *
 * Separate from the detector because it answers a different question. The detector answers
 * "what is sounding in this 786 ms window", which is a measurement and is allowed to change
 * every frame. This answers "what should the user be looking at", which is a UI question and
 * must not.
 *
 * Two mechanisms, in opposite directions:
 *
 * - [HYSTERESIS_FRAMES] slows a *new* name down, so a reading passing through on the way to
 *   another one does not appear.
 * - [PresentationHold] slows *clearing* down, which is the one that was missing. Measured on a
 *   real ukulele: a strum produced 35 separate visible episodes, 16 of them one or two frames
 *   long, because the tonality gate drops in and out through the decay and the display followed
 *   it frame for frame. A tuner that strobes is unusable, so the display stops tracking the
 *   gate.
 *
 * The Note tab uses [PresentationHold] directly, without the hysteresis: a new *note* must
 * appear immediately, because a held reading over a genuine change would have the user tuning
 * against a note they are no longer playing.
 */
class ChordPresenter {
    private val hold = PresentationHold<ChordReading>(HOLD_FRAMES)
    private var pendingLabel: ChordLabel? = null
    private var pendingCount = 0

    /** What the tab is currently showing, or null when it is listening. */
    val current: ChordReading? get() = hold.current

    /** True when the reading is being held through a gated stretch rather than freshly detected. */
    val isStale: Boolean get() = hold.isStale

    fun reset() {
        hold.reset()
        pendingLabel = null
        pendingCount = 0
    }

    fun update(fresh: ChordReading): ChordDisplay {
        if (fresh.label is ChordLabel.Silent) {
            return if (hold.idle()) ChordDisplay.CLEARED else ChordDisplay.UNCHANGED
        }
        if (!settled(fresh.label)) return ChordDisplay.UNCHANGED
        hold.present(fresh)
        return ChordDisplay.UPDATED
    }

    /** A name has to win [HYSTERESIS_FRAMES] frames in a row before it replaces what is shown. */
    private fun settled(label: ChordLabel): Boolean {
        if (label == hold.current?.label) {
            pendingLabel = null
            pendingCount = 0
            return true
        }
        if (label != pendingLabel) {
            pendingLabel = label
            pendingCount = 1
            return false
        }
        return ++pendingCount >= HYSTERESIS_FRAMES
    }

    /** Shared constants and helpers for ChordPresenter. */
    companion object {
        /** A new name must win this many consecutive frames before it replaces the shown one. */
        const val HYSTERESIS_FRAMES: Int = 2

        /**
         * Gated frames a reading survives before the tab goes back to listening.
         *
         * Chord frames are ~85 ms apart, so this is ~510 ms: long enough to ride out the
         * dropouts measured through a real decay, short enough that the tab still clears
         * promptly when the player stops.
         */
        const val HOLD_FRAMES: Int = 6
    }
}
