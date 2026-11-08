package com.vayunmathur.appstore.data.security

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SourceStamp fails closed to null: a file that is not a signed APK,
 * and a path that does not exist at all, both read as "no stamp" so
 * the verifier can decide whether absence is acceptable. The Stamp
 * value itself keeps its fingerprint and lineage as given.
 */
@RunWith(RobolectricTestRunner::class)
class SourceStampTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `a plain file carries no verifiable stamp`() {
        val file = temp.newFile("not-an-apk.bin")
        file.writeText("plain bytes, not an apk")
        assertNull(SourceStamp.of(file))
    }

    @Test
    fun `a missing file carries no verifiable stamp`() {
        assertNull(SourceStamp.of(File(temp.root, "does-not-exist.apk")))
    }

    @Test
    fun `stamp value keeps its fingerprint and lineage`() {
        val stamp = SourceStamp.Stamp(
            current = "ab".repeat(32),
            lineage = setOf("ab".repeat(32), "cd".repeat(32)),
        )
        assertEquals("ab".repeat(32), stamp.current)
        assertEquals(2, stamp.lineage.size)
        assertEquals(stamp, stamp.copy())
    }
}
