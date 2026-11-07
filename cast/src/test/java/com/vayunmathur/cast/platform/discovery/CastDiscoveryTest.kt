package com.vayunmathur.cast.platform.discovery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CastDiscoveryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `manager starts empty and clear is safe`() {
        val manager = CastDiscoveryManager(context)
        assertTrue(manager.devices.value.isEmpty())
        assertEquals(false, manager.localNetworkBlocked.value)
        manager.clear()
        assertTrue(manager.devices.value.isEmpty())
    }

    @Test
    fun `discover can be collected and cancelled without devices`() = runBlocking {
        val manager = CastDiscoveryManager(context)
        val job = async {
            manager.discover().collect { }
        }
        delay(300)
        job.cancel()
        delay(100)
        assertTrue(manager.devices.value.isEmpty())
        manager.clear()
    }
}
