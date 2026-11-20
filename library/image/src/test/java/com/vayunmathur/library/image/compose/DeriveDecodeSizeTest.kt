package com.vayunmathur.library.image.compose

import com.vayunmathur.library.image.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeriveDecodeSizeTest {

    @Test
    fun `explicit size always wins over layout`() {
        assertEquals(
            Size(256, 256),
            deriveDecodeSize(Size(256, 256), constraintWidth = 1080, constraintHeight = 800, measuredWidth = 400, measuredHeight = 300),
        )
    }

    @Test
    fun `explicit original size is preserved so callers can still request the full image`() {
        assertEquals(
            Size.Original,
            deriveDecodeSize(Size.Original, constraintWidth = 1080, constraintHeight = 800, measuredWidth = 400, measuredHeight = 300),
        )
    }

    @Test
    fun `plain models derive their decode size from bounded constraints`() {
        assertEquals(
            Size(320, 240),
            deriveDecodeSize(null, constraintWidth = 320, constraintHeight = 240, measuredWidth = 0, measuredHeight = 0),
        )
    }

    @Test
    fun `unbounded constraints fall back to the measured layout size`() {
        assertEquals(
            Size(180, 180),
            deriveDecodeSize(null, constraintWidth = Int.MAX_VALUE, constraintHeight = 240, measuredWidth = 180, measuredHeight = 180),
        )
        assertEquals(
            Size(180, 180),
            deriveDecodeSize(null, constraintWidth = 320, constraintHeight = Int.MAX_VALUE, measuredWidth = 180, measuredHeight = 180),
        )
    }

    @Test
    fun `no usable layout size leaves the request unsized`() {
        assertNull(deriveDecodeSize(null, constraintWidth = Int.MAX_VALUE, constraintHeight = Int.MAX_VALUE, measuredWidth = 0, measuredHeight = 0))
        assertNull(deriveDecodeSize(null, constraintWidth = 0, constraintHeight = 0, measuredWidth = 0, measuredHeight = 0))
        assertNull(deriveDecodeSize(null, constraintWidth = 320, constraintHeight = Int.MAX_VALUE, measuredWidth = 0, measuredHeight = 0))
    }
}
