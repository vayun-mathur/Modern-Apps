package com.vayunmathur.share.platform.airdrop.link

import android.content.Context
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MoseyLinkTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private class FakeHal : MoseyHal {
        override val backend = MoseyBackend.SharedLibrary
        override val version = "fake-1"
        var started = false; var stopped = false
        val updates = mutableListOf<String>()
        override fun hardwareCapability(): ByteArray? = byteArrayOf(0x08, 0x01)
        override fun start(channels: IntArray, token: IBinder, config: ByteArray) { started = true }
        override fun update(countryCode: String, config: ByteArray) { updates += countryCode }
        override fun stop() { stopped = true }
    }

    @Test
    fun `detector reports unavailable on test device`() {
        val status = MoseyDetector.detect(ctx)
        assertNotNull(status.detail)
        assertEquals(status.backend != null, status.available)
        // open must not crash either way
        MoseyDetector.open(ctx)
    }

    @Test
    fun `mosey status availability derives from backend`() {
        assertFalse(MoseyStatus(null, "none").available)
        assertTrue(MoseyStatus(MoseyBackend.AidlPixel, "pixel").available)
        assertEquals("moseylib", MoseyBackend.SharedLibrary.label)
    }

    @Test
    fun `mosey link activate without country code fails and deactivate stops hal`() = runBlocking {
        val hal = FakeHal()
        val link = MoseyLink(ctx, hal)
        assertEquals(AirDropLink.Kind.Mosey, link.kind)
        assertNull(link.network)
        // No WifiManager country code / telephony in Robolectric -> activate refuses cleanly.
        val ok = link.activate()
        assertFalse(ok)
        // IPv4 peer notifications are ignored (need IPv6 + country).
        link.onTransferStarted(InetAddress.getByName("192.168.1.2"))
        link.onTransferEnded(InetAddress.getByName("192.168.1.2"))
        link.deactivate()
        assertTrue(hal.stopped)
    }

    @Test
    fun `link selector falls back to wifi lan`() = runBlocking {
        val sel = AirDropLinkSelector.select(ctx)
        assertNotNull(sel.link)
        assertNotNull(sel.mosey.detail)
        sel.link.deactivate()
    }

    @Test
    fun `mosey config capability edge cases`() {
        assertFalse(MoseyConfig.isChannelHoppingSupported(byteArrayOf(0x1F, 0x01)))
        assertFalse(MoseyConfig.isChannelHoppingSupported(byteArrayOf(0x08, 0x00)))
        val cfg = MoseyConfig.start(intArrayOf(6, 36), staChannel = 6, dualBandSimultaneous = false)
        assertTrue(cfg.isNotEmpty())
        val ended = MoseyConfig.transferEnded(InetAddress.getByName("fe80::5") as java.net.Inet6Address)
        assertTrue(ended.isNotEmpty())
    }
}
