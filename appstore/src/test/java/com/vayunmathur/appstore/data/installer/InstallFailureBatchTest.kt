package com.vayunmathur.appstore.data.installer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Update-all failure batching: sequential installs fail minutes apart with
 * per-package messages, so identical reasons must be collected for one summary
 * instead of one snackbar per app. Outside a batch everything passes through.
 */
class InstallFailureBatchTest {

    @Test
    fun `outside a batch nothing is collected`() {
        assertFalse(InstallFailureBatch.collect("boom"))
    }

    @Test
    fun `inside a batch reasons are collected and handed back once`() {
        InstallFailureBatch.begin()
        try {
            assertTrue(InstallFailureBatch.collect("first"))
            assertTrue(InstallFailureBatch.collect("second"))

            assertEquals(listOf("first", "second"), InstallFailureBatch.end())
        } finally {
            InstallFailureBatch.end()
        }
    }

    @Test
    fun `beginning a batch drops leftovers`() {
        InstallFailureBatch.begin()
        InstallFailureBatch.collect("stale")
        InstallFailureBatch.begin()
        try {
            assertEquals(emptyList(), InstallFailureBatch.end())
        } finally {
            InstallFailureBatch.end()
        }
    }

    @Test
    fun `ending twice yields nothing the second time`() {
        InstallFailureBatch.begin()
        InstallFailureBatch.collect("once")

        assertEquals(listOf("once"), InstallFailureBatch.end())
        assertEquals(emptyList(), InstallFailureBatch.end())
    }

    @Test
    fun `install results publish to collectors`() = runTest {
        InstallEvents.publish(InstallResult("com.example.app", success = true))

        assertEquals(
            InstallResult("com.example.app", success = true),
            InstallEvents.results.first(),
        )
    }
}
