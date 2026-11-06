package com.vayunmathur.youpipe.util

import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Slice 2/3 (w5): the SponsorBlock/DeArrow fetchers in util/Extractor.kt are
 * best-effort (catch-all returns null/emptyList). With the mirrors pointed at
 * a closed loopback port the connection is refused, pinning the fallback
 * paths. (A loopback HTTP server would pin the parse paths too, but the
 * unit-test sandbox refuses loopback connections — both under Robolectric
 * and on plain JVM — so success bodies are covered on-device only.)
 */
class W5Slice2ExtractorHttpTest {

    private val realSponsor = SPONSORBLOCK_MIRROR
    private val realThumb = DEARROW_THUMB_MIRROR

    @BeforeTest
    fun pointAtClosedPort() {
        // Port 1 is never bound: connect() fails fast with "refused".
        SPONSORBLOCK_MIRROR = "http://127.0.0.1:1"
        DEARROW_THUMB_MIRROR = "http://127.0.0.1:1/api/dearrow/thumbnail"
    }

    @AfterTest
    fun restoreMirrors() {
        SPONSORBLOCK_MIRROR = realSponsor
        DEARROW_THUMB_MIRROR = realThumb
    }

    @Test
    fun getDeArrowBranding_nullWhenUnreachable() = runTest {
        assertNull(getDeArrowBranding(4242L))
    }

    @Test
    fun getSponsorSegments_emptyWhenUnreachable() = runTest {
        assertEquals(emptyList(), getSponsorSegments(4242L))
    }
}
