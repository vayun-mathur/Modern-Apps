package com.vayunmathur.games.voxels.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.network.SimpleResponse
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * VoxelsSync failure paths: the relay seam answers 404 / throws, so [VoxelsSync.init]
 * cannot register and relay calls degrade to null/false/empty. Own test class
 * (own JVM) because `initialized` is a process-wide latch.
 */
@RunWith(RobolectricTestRunner::class)
class VoxelsSyncInitFailTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @AfterTest fun clearSeam() {
        VoxelsSync.relayTransport = null
    }

    @Test fun initFailsWhenRegisterIsRejectedAndCallsDegrade() {
        VoxelsSync.relayTransport = { _, _ -> SimpleResponse(404, "", "", emptyMap(), "fake://relay") }
        assertFalse(runBlocking { VoxelsSync.init(ctx) })
        // device id is still provisioned locally even though registration failed
        assertTrue(VoxelsSync.deviceId.isNotBlank())
        runBlocking {
            assertNull(VoxelsSync.getKey("anyone"))
            assertNull(VoxelsSync.appendWorldOps("w", VoxelsSync.newWorldKey(), listOf("x")))
            assertEquals(emptyList(), VoxelsSync.pullWorldOps("w", VoxelsSync.newWorldKey(), 0).items)
            assertTrue(VoxelsSync.pullMembers("w", VoxelsSync.newWorldKey()).isEmpty())
            assertFalse(VoxelsSync.recordMembers("w", VoxelsSync.newWorldKey(), listOf(VoxelsSync.Member("d"))))
        }
    }

    @Test fun transportExceptionsDegradeToNoResponse() {
        VoxelsSync.relayTransport = { _, _ -> throw RuntimeException("relay unreachable") }
        runBlocking {
            assertNull(VoxelsSync.appendRaw("chan", listOf("x")))
            assertEquals(emptyList(), VoxelsSync.pullRaw("chan", 0))
            assertFalse(VoxelsSync.init(ctx))
        }
    }
}
