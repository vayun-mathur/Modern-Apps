package com.vayunmathur.games.logicgate.ui

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals

class TrayDragTest {
    @Test
    fun theChipIsPlacedWhereItsPreviewIsNotUnderTheFinger() {
        val drag = TrayDrag("nand", root = Offset(300f, 900f))
        // 48 dp at 2.75x density.
        assertEquals(Offset(300f, 900f - 132f), drag.previewCenter(132f))
        assertEquals(drag.root, drag.previewCenter(0f))
    }
}
