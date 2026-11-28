package com.vayunmathur.pdf.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #942: opening a document must not eagerly build a whole-book
 * search index. The gate lives on [SafePdfDocument] so both the viewer
 * prewarm and any future caller get the same decision.
 */
class SafePdfPrewarmGateTest {

    @Test fun smallDocumentsStillPrewarm() {
        assertTrue(SafePdfDocument.shouldPrewarmSearch(0))
        assertTrue(SafePdfDocument.shouldPrewarmSearch(1))
        assertTrue(SafePdfDocument.shouldPrewarmSearch(42))
    }

    @Test fun theBoundaryIsInclusiveAndBookScaleIsExcluded() {
        assertTrue(SafePdfDocument.shouldPrewarmSearch(SafePdfDocument.MAX_PREWARM_PAGES))
        assertFalse(SafePdfDocument.shouldPrewarmSearch(SafePdfDocument.MAX_PREWARM_PAGES + 1))
        // The reported #942 document: 940 pages.
        assertFalse(SafePdfDocument.shouldPrewarmSearch(940))
    }

    @Test fun negativeCountsNeverPrewarm() {
        assertFalse(SafePdfDocument.shouldPrewarmSearch(-1))
    }
}
