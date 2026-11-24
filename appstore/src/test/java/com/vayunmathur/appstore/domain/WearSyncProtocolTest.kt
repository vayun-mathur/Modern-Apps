package com.vayunmathur.appstore.domain

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString

class WearSyncProtocolTest {

    @Test
    fun `golden wire json is stable across both apps`() {
        // The wear module pins these exact strings in its own copy of this test:
        // a one-sided protocol edit breaks one side or the other, never silently both.
        val catalog = WearCatalog(listOf(WearCatalogApp("com.example.a", "A", "1.0", 7L, 123L)))
        assertEquals(
            """{"apps":[{"packageName":"com.example.a","name":"A","versionName":"1.0","versionCode":7,"sizeBytes":123}]}""",
            WearSyncProtocol.json.encodeToString(catalog),
        )
        val offer = ApkOffer("com.example.a", "A", 7L, 123L, "ab12")
        assertEquals(
            """{"packageName":"com.example.a","name":"A","versionCode":7,"sizeBytes":123,"sha256":"ab12"}""",
            WearSyncProtocol.json.encodeToString(offer),
        )
        assertEquals("/appstore/apk/com.example.a", WearSyncProtocol.apkChannelPath("com.example.a"))
        assertEquals("com.example.a", WearSyncProtocol.packageFromChannelPath("/appstore/apk/com.example.a"))
        assertNull(WearSyncProtocol.packageFromChannelPath("/appstore/catalog"))
    }

    @Test
    fun `phone machine walks send to installed and never fakes a step`() {
        var state = PhoneWearState.NOT_DOWNLOADED
        state = PhoneWearMachine.reduce(state, PhoneWearEvent.SendStarted)
        assertEquals(PhoneWearState.SENDING, state)
        // Install cannot be requested before the bytes are on the watch.
        assertEquals(
            PhoneWearState.SENDING,
            PhoneWearMachine.reduce(state, PhoneWearEvent.InstallRequested),
        )
        state = PhoneWearMachine.reduce(state, PhoneWearEvent.SendFinished(true))
        assertEquals(PhoneWearState.ON_WATCH, state)
        state = PhoneWearMachine.reduce(state, PhoneWearEvent.InstallRequested)
        assertEquals(PhoneWearState.INSTALL_REQUESTED, state)
        state = PhoneWearMachine.reduce(
            state,
            PhoneWearEvent.ResultReceived(InstallResult("com.example.a", 7L, installed = false, "user cancelled")),
        )
        assertEquals(PhoneWearState.FAILED, state)
    }

    @Test
    fun `reconcile trusts the watch snapshot except during a live send`() {
        val entry = WatchAppEntry("com.example.a", downloadedVersionCode = 7L, installedVersionCode = 7L)
        assertEquals(
            PhoneWearState.INSTALLED,
            PhoneWearMachine.reduce(
                PhoneWearState.ON_WATCH,
                PhoneWearEvent.Reconciled(entry, catalogVersionCode = 7L),
            ),
        )
        assertEquals(
            PhoneWearState.SENDING,
            PhoneWearMachine.reduce(
                PhoneWearState.SENDING,
                PhoneWearEvent.Reconciled(entry, catalogVersionCode = 7L),
            ),
        )
        assertEquals(
            PhoneWearState.FAILED,
            PhoneWearMachine.reduce(PhoneWearState.SENDING, PhoneWearEvent.WatchGone),
        )
        // No capability / no watch: nothing about the app breaks or changes.
        assertEquals(
            PhoneWearState.ON_WATCH,
            PhoneWearMachine.reduce(PhoneWearState.ON_WATCH, PhoneWearEvent.WatchGone),
        )
    }

    @Test
    fun `watch machine requires verified bytes and an explicit install tap`() {
        var phase = WatchAppPhase.LISTED
        phase = WatchAppMachine.reduce(phase, WatchAppEvent.ReceiveStarted)
        assertEquals(WatchAppPhase.RECEIVING, phase)
        phase = WatchAppMachine.reduce(phase, WatchAppEvent.ReceiveFinished(verified = false))
        assertEquals(WatchAppPhase.REJECTED, phase)
        phase = WatchAppMachine.reduce(phase, WatchAppEvent.ReceiveStarted)
        phase = WatchAppMachine.reduce(phase, WatchAppEvent.ReceiveFinished(verified = true))
        assertEquals(WatchAppPhase.READY_TO_INSTALL, phase)
        // No tap, no install: RECEIVING/READY states never advance on their own.
        phase = WatchAppMachine.reduce(phase, WatchAppEvent.InstallTapped)
        assertEquals(WatchAppPhase.INSTALLING, phase)
        phase = WatchAppMachine.reduce(phase, WatchAppEvent.InstallFinished(installed = true))
        assertEquals(WatchAppPhase.INSTALLED, phase)
    }

    @Test
    fun `hash mismatch and truncation are rejected`() {
        val dir = java.nio.file.Files.createTempDirectory("wear-sync").toFile()
        try {
            val file = File(dir, "a.apk").apply { writeBytes("hello watch".toByteArray()) }
            val good = ApkOffer(
                "com.example.a", "A", 1L, file.length(),
                ApkHash.sha256Hex(file),
            )
            assertTrue(ApkHash.verify(file, good))
            assertFalse(ApkHash.verify(file, good.copy(sha256 = "00".repeat(32))))
            assertFalse(ApkHash.verify(file, good.copy(sizeBytes = file.length() + 1)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `eligibility is a technical gate only`() {
        assertNull(watchSendEligibility("https://x/a.apk", splitCount = 0, sizeBytes = 10L))
        assertTrue(watchSendEligibility(null, 0, 10L) != null)
        assertTrue(watchSendEligibility("https://x/a.apk", splitCount = 2, sizeBytes = 10L) != null)
        assertTrue(
            watchSendEligibility(
                "https://x/a.apk", 0, WearSyncProtocol.MAX_APK_BYTES + 1,
            ) != null,
        )
    }
}
