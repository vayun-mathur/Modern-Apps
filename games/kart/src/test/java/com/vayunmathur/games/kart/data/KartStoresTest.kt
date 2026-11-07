package com.vayunmathur.games.kart.data

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.kart.platform.AppBackupAgent
import com.vayunmathur.games.kart.platform.KartPairingStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class KartStoresTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun dataStoreDefaultsThenSaveCupRoundTrips() = runBlocking {
        val store = KartDataStore(ctx)
        assertEquals(0, store.currentCup.first())
        assertEquals(0, store.racesWon.first())
        assertEquals(0L, store.bestLapMs.first())
        store.saveCup(2)
        assertEquals(2, store.currentCup.first())
        // Saving the cup leaves lifetime counters untouched.
        assertEquals(0, store.racesWon.first())
        assertEquals(0L, store.bestLapMs.first())
        store.saveCup(0)
        assertEquals(0, store.currentCup.first())
    }

    @Test fun pairingStoreBytesKeysAndDeviceId() = runBlocking {
        val store = KartPairingStore(ctx)
        assertNull(store.getBytes("kem"))
        store.setBytes("kem", byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), store.getBytes("kem"))
        // onlyIfAbsent must not overwrite an existing identity.
        store.setBytes("kem", byteArrayOf(9), onlyIfAbsent = true)
        assertContentEquals(byteArrayOf(1, 2, 3), store.getBytes("kem"))
        store.setBytes("kem", byteArrayOf(9), onlyIfAbsent = false)
        assertContentEquals(byteArrayOf(9), store.getBytes("kem"))

        assertNull(store.deviceKey("phone-1"))
        store.remember("phone-1", byteArrayOf(7, 8))
        assertContentEquals(byteArrayOf(7, 8), store.deviceKey("phone-1"))
        assertNull(store.deviceKey("phone-2"))

        val id1 = store.deviceId()
        val id2 = store.deviceId()
        assertTrue(id1.isNotBlank())
        assertEquals(id1, id2)
    }

    @Test fun modelsAndBackupAgent() {
        assertEquals(3, CUPS.size)
        assertEquals(listOf("Mushroom Cup", "Flower Cup", "Star Cup"), CUPS.map { it.name })
        assertTrue(CUPS.all { it.trackCount == 4 })
        assertEquals(listOf(0, 1, 2), CUPS.map { it.id })
        val progress = KartProgress()
        assertEquals(0, progress.currentCup)
        assertEquals(0, progress.racesWon)
        assertEquals(0L, progress.bestLapMs)
        assertEquals(KartProgress(1, 2, 3L), KartProgress(currentCup = 1, racesWon = 2, bestLapMs = 3L))
        assertEquals(listOf("settings"), AppBackupAgent().datastoreNames)
    }
}
