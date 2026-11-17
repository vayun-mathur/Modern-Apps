package com.vayunmathur.launcher.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class TvRowOrderTest {
    private val installed = listOf(
        "com.google.android.youtube.tv",
        "com.netflix.ninja",
        "com.example.game",
        "com.example.other",
    )

    @Test
    fun savedOrderWinsVerbatimMinusMissing() {
        val saved = listOf("com.netflix.ninja", "com.gone.app", "com.example.game")
        assertEquals(
            listOf("com.netflix.ninja", "com.example.game"),
            TvRowOrder.mergeOrder(saved, installed),
        )
    }

    @Test
    fun noSavedOrderTopsUpDefaultsWithInstalled() {
        assertEquals(
            listOf(
                "com.google.android.youtube.tv",
                "com.netflix.ninja",
                "com.example.game",
                "com.example.other",
            ),
            TvRowOrder.mergeOrder(
                saved = null,
                installed = installed,
                defaults = listOf("com.google.android.youtube.tv", "com.netflix.ninja"),
            ),
        )
    }

    @Test
    fun topUpStopsAtOneFullRow() {
        val many = (1..10).map { "com.example.app$it" } + installed
        val row = TvRowOrder.mergeOrder(null, many, defaults = emptyList())
        assertEquals(TvRowOrder.PER_ROW, row.size)
    }

    @Test
    fun moveSlidesEntry() {
        val order = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), TvRowOrder.move(order, 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), TvRowOrder.move(order, 3, 0))
        assertEquals(order, TvRowOrder.move(order, 1, 1))
        assertEquals(order, TvRowOrder.move(order, 9, 0))
    }

    @Test
    fun chainedMovesTrackHeldTile() {
        // Pick up "b" at 1, move right twice, back once: the held tile is found by value
        // each step (the ViewModel moveKey contract), ending where it belongs.
        var order = listOf("a", "b", "c", "d", "e", "f", "g")
        var held = "b"
        fun step(delta: Int) {
            val from = order.indexOf(held)
            val to = (from + delta).coerceIn(0, order.size - 1)
            order = TvRowOrder.move(order, from, to)
        }
        step(+1)
        assertEquals(listOf("a", "c", "b", "d", "e", "f", "g"), order)
        step(+1)
        assertEquals(listOf("a", "c", "d", "b", "e", "f", "g"), order)
        step(-1)
        assertEquals(listOf("a", "c", "b", "d", "e", "f", "g"), order)
    }

    @Test
    fun crossRowStepsMoveByFullRow() {
        val order = listOf("a", "b", "c", "d", "e", "f", "g")
        // Down from index 1 lands on index 1 + PER_ROW, clamped into range.
        val from = 1
        val to = (from + TvRowOrder.PER_ROW).coerceIn(0, order.size - 1)
        assertEquals(listOf("a", "c", "d", "e", "f", "g", "b"), TvRowOrder.move(order, from, to))
        // Up past the start clamps to 0.
        assertEquals(listOf("b", "a", "c"), TvRowOrder.move(listOf("a", "b", "c"), 1, -5))
    }

    @Test
    fun clampBeyondRangeIsNoOp() {
        val order = listOf("a", "b", "c")
        // Clamped to the end: a real move, landing last.
        assertEquals(listOf("b", "c", "a"), TvRowOrder.move(order, 0, 99))
        // Out-of-range source: untouched.
        assertEquals(order, TvRowOrder.move(order, -1, 2))
    }

    @Test
    fun replaceSwapsWithoutDuplicating() {
        val order = listOf("a", "b", "c")
        assertEquals(listOf("a", "x", "c"), TvRowOrder.replaceAt(order, 1, "x"))
        // Replacing with a package already on the row moves it rather than duplicating.
        assertEquals(listOf("b", "a"), TvRowOrder.replaceAt(order.take(2), 5, "a"))
        assertEquals(order, TvRowOrder.replaceAt(order, 1, ""))
    }

    @Test
    fun hdmiSlotsLeaveRoomForStoreSettingsAdd() {
        assertEquals(3, TvRowOrder.maxHdmiSlots(hasStore = true))
        assertEquals(4, TvRowOrder.maxHdmiSlots(hasStore = false))
    }
}

