package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Transformed-text offset accounting: prefix starts and the offset mapping.
 * Slice-1 coverage for library/ui (odf package).
 */
class OdfDocTransformSlice1Test {

    @Test
    fun `transformed starts accumulate prefixes plus separators`() {
        val starts = runTransStarts(listOf(3, 2), listOf("•  ", ""))
        assertEquals(0, starts[0])
        // 3 chars + 3 prefix + 1 separator.
        assertEquals(7, starts[1])
    }

    @Test
    fun `empty inputs yield empty starts`() {
        assertEquals(0, runTransStarts(emptyList(), emptyList()).size)
    }

    @Test
    fun `the offset mapping round-trips through prefixes`() {
        val lens = listOf(3, 3)
        val prefixes = listOf("•  ", "")
        val transStarts = runTransStarts(lens, prefixes)
        val origStarts = intArrayOf(0, 4)
        val mapping = PrefixOffsetMapping(
            origStarts = origStarts,
            transStarts = transStarts,
            prefixLens = intArrayOf(3, 0),
            lens = lens.toIntArray(),
            origLen = 7,
            transLen = 10,
        )
        // The transformed slot starts with the injected prefix: original
        // 0 (paragraph start) reads at the prefix start, which the mapping
        // reports as the paragraph's transformed start.
        assertEquals(0, transStarts[0])
        assertEquals(7, transStarts[1])
        // Text after the prefix shifts right by the prefix length.
        assertEquals(4, mapping.originalToTransformed(1))
        // Back the other way: inside-prefix offsets clamp to the paragraph start.
        assertEquals(0, mapping.transformedToOriginal(1))
        assertEquals(1, mapping.transformedToOriginal(4))
        // Start of the second paragraph maps to its transformed start.
        assertEquals(7, mapping.originalToTransformed(4))
    }
}
