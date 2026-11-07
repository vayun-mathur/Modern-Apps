package com.vayunmathur.share.platform

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareViewModelHelpersTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `resolveDisplayName returns file name for file uri`() {
        val uri = Uri.fromFile(File("/tmp/photos/cat.jpg"))
        assertEquals("cat.jpg", resolveDisplayName(app, uri))
    }

    @Test
    fun `uriToTempFile copies file uri content`() {
        val src = File(app.cacheDir, "src-${System.nanoTime()}.txt").apply { writeText("payload-data") }
        val out = uriToTempFile(app, Uri.fromFile(src))
        assertNotNull(out)
        assertEquals("payload-data", out!!.readText())
    }

    @Test
    fun `uriToTempFile returns null for missing content uri`() {
        val out = uriToTempFile(app, Uri.parse("content://com.example.missing/123"))
        assertNull(out)
    }

    @Test
    fun `viewmodel outgoing uris and clear`() {
        val vm = ShareViewModel(app)
        val f = File(app.cacheDir, "vm-${System.nanoTime()}.txt").apply { writeText("hi") }
        vm.setOutgoingUris(listOf(Uri.fromFile(f)))
        assertEquals(1, vm.outgoingUris.value.size)
        vm.clearOutgoing()
        assertTrue(vm.outgoingUris.value.isEmpty())
        assertTrue(vm.sendUiState.value.outgoingUris.isEmpty())
    }

    @Test
    fun `viewmodel handleShareIntent with text stages a file`() {
        val vm = ShareViewModel(app)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "shared hello text")
        }
        vm.handleShareIntent(intent)
        // staging is async on IO; poll briefly while pumping the main looper
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline && vm.outgoingUris.value.isEmpty()) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        assertEquals(1, vm.outgoingUris.value.size)
    }

    @Test
    fun `viewmodel handleShareIntent ignores empty intent`() {
        val vm = ShareViewModel(app)
        vm.handleShareIntent(Intent(Intent.ACTION_VIEW))
        assertTrue(vm.outgoingUris.value.isEmpty())
    }

    @Test
    fun `factory creates viewmodel and rejects unknown class`() {
        val factory = ShareViewModelFactory(app)
        val vm = factory.create(ShareViewModel::class.java)
        assertNotNull(vm)
        class OtherVm : androidx.lifecycle.ViewModel()
        try {
            factory.create(OtherVm::class.java)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Unknown ViewModel"))
        }
    }

    @Test
    fun `connect to ble-only device without host is a no-op`() {
        val vm = ShareViewModel(app)
        // No host/port: must return without crashing or connecting.
        vm.connectToDevice(com.vayunmathur.share.platform.discovery.NearbyDevice(endpointId = "BLE1", endpointName = "Ble", source = com.vayunmathur.share.platform.discovery.DiscoverySource.Ble))
        vm.stopScan()
    }
}
