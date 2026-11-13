package com.vayunmathur.games.voxels.domain

import kotlin.math.hypot

/** What a finger on the world layer asks the game to do. */
sealed interface WorldTouch {
    /** A quick tap: place a block / interact with what's under the finger. */
    data class Tap(val x: Float, val y: Float) : WorldTouch
    /** Held still past the long-press timeout, then again every repeat interval: mine / attack / use. */
    data class Act(val x: Float, val y: Float) : WorldTouch
    /** A hold ended (stop showing the progress ring). */
    data object ActEnd : WorldTouch
    /** A drag that started on the look side became the look pointer; `x, y` is where it started. */
    data class LookStart(val x: Float, val y: Float) : WorldTouch
    /** The look pointer moved: current position, and the movement since the last report. */
    data class LookMove(val x: Float, val y: Float, val dx: Float, val dy: Float) : WorldTouch
    /** The look pointer lifted. */
    data object LookEnd : WorldTouch
}

/** The finger currently holding an action, for drawing the progress ring. */
data class HoldInfo(val x: Float, val y: Float, val progress: Float)

/**
 * Per-pointer gesture logic for the full-screen world layer, kept free of Compose so the rules can be
 * unit-tested:
 *
 * - A finger that lifts before the long-press timeout without moving more than [slopPx] taps.
 * - A finger held still past the timeout starts acting (mine, attack, eat, throw) and repeats every
 *   [repeatMs] for as long as it stays down; it may slide to aim.
 * - A finger that moves past the slop before the timeout never acts. If it started on the look side
 *   and no other finger is looking, it becomes the look pointer; otherwise it is ignored. So a thumb
 *   that comes to rest on the screen and then turns the camera no longer breaks the block under it.
 * - Every finger is tracked on its own, so one finger can look while another taps or holds.
 */
class WorldTouchTracker(
    private val slopPx: Float,
    private val longPressMs: Long,
    private val repeatMs: Long,
) {
    private sealed interface State {
        val x: Float
        val y: Float
    }
    private data class Pending(
        override val x: Float, override val y: Float, val downAt: Long, val lookSide: Boolean,
    ) : State
    private data class Holding(
        override val x: Float, override val y: Float, val lastFire: Long, val nextFire: Long,
    ) : State
    private data class Looking(override val x: Float, override val y: Float) : State
    private data class Ignored(override val x: Float, override val y: Float) : State

    private val pointers = LinkedHashMap<Long, State>()

    /** Whether some finger is currently the look pointer. */
    val looking: Boolean get() = pointers.values.any { it is Looking }

    /** Whether this pointer's events belong to the world layer (it's looking or acting). */
    fun owns(id: Long): Boolean = pointers[id].let { it is Looking || it is Holding }

    /** A finger touched down; `lookSide` says whether it landed on the half that starts look drags. */
    fun down(id: Long, x: Float, y: Float, time: Long, lookSide: Boolean): List<WorldTouch> {
        pointers[id] = Pending(x, y, time, lookSide)
        return emptyList()
    }

    /** A finger moved to (x, y). */
    fun move(id: Long, x: Float, y: Float): List<WorldTouch> {
        return when (val s = pointers[id]) {
            is Pending -> if (hypot(x - s.x, y - s.y) > slopPx) {
                if (s.lookSide && !looking) {
                    pointers[id] = Looking(x, y)
                    listOf(WorldTouch.LookStart(s.x, s.y), WorldTouch.LookMove(x, y, x - s.x, y - s.y))
                } else {
                    pointers[id] = Ignored(x, y)
                    emptyList()
                }
            } else emptyList()
            is Looking -> {
                pointers[id] = Looking(x, y)
                listOf(WorldTouch.LookMove(x, y, x - s.x, y - s.y))
            }
            is Holding -> { pointers[id] = s.copy(x = x, y = y); emptyList() }
            is Ignored, null -> emptyList()
        }
    }

    /** A finger lifted. */
    fun up(id: Long): List<WorldTouch> = when (val s = pointers.remove(id)) {
        is Pending -> listOf(WorldTouch.Tap(s.x, s.y))
        is Holding -> listOf(WorldTouch.ActEnd)
        is Looking -> listOf(WorldTouch.LookEnd)
        is Ignored, null -> emptyList()
    }

    /** The gesture was cancelled (another layer took the pointers, or the game paused). */
    fun cancel(): List<WorldTouch> {
        val out = buildList {
            if (pointers.values.any { it is Holding }) add(WorldTouch.ActEnd)
            if (looking) add(WorldTouch.LookEnd)
        }
        pointers.clear()
        return out
    }

    /** Advance the clock: promote long presses and fire repeats that are due. */
    fun tick(time: Long): List<WorldTouch> {
        val out = ArrayList<WorldTouch>()
        for ((id, s) in pointers.entries.toList()) {
            when (s) {
                is Pending -> if (time - s.downAt >= longPressMs) {
                    pointers[id] = Holding(s.x, s.y, lastFire = time, nextFire = time + repeatMs)
                    out += WorldTouch.Act(s.x, s.y)
                }
                is Holding -> if (time >= s.nextFire) {
                    pointers[id] = s.copy(lastFire = time, nextFire = maxOf(s.nextFire + repeatMs, time + 1))
                    out += WorldTouch.Act(s.x, s.y)
                }
                else -> Unit
            }
        }
        return out
    }

    /** The first finger holding an action, with progress (0..1) toward its next repeat. */
    fun hold(time: Long): HoldInfo? {
        val h = pointers.values.firstOrNull { it is Holding } as? Holding ?: return null
        val span = (h.nextFire - h.lastFire).coerceAtLeast(1)
        return HoldInfo(h.x, h.y, ((time - h.lastFire).toFloat() / span).coerceIn(0f, 1f))
    }
}
