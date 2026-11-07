package com.vayunmathur.globalesim.data

import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/** AnonymousIdStore (DataStore UUID) and StripeKeyStore (SharedPreferences cache). */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class IdAndKeyStoresTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun anonymousIdMintsOnceAndPersists() = runTest {
        val store = AnonymousIdStore(ctx)
        // Reset any state a sibling test left in the shared store file.
        store.replace(java.util.UUID.randomUUID().toString())
        val first = store.getOrCreate()
        assertTrue(first.isNotBlank())
        // UUIDv4 shape: 8-4-4-4-12 hex with the version nibble.
        assertTrue(Regex("[0-9a-f-]{36}").matches(first), first)
        assertEquals(first, store.getOrCreate())
        assertEquals(first, store.accountId.first())
    }

    @Test fun anonymousIdReplaceAdoptsClaimedId() = runTest {
        val store = AnonymousIdStore(ctx)
        val minted = store.getOrCreate()
        store.replace("claimed-sim-id")
        assertNotEquals(minted, store.accountId.first())
        assertEquals("claimed-sim-id", store.getOrCreate())
        store.replace("second-id")
        assertEquals("second-id", store.accountId.first())
    }

    @Test fun anonymousIdMintsIntoFreshFilesDir() = runTest {
        // A context over a fresh files dir has an empty DataStore, so
        // getOrCreate must take the mint-and-persist branch.
        val freshDir = java.nio.file.Files.createTempDirectory("esim-fresh").toFile()
        val wrapper = object : android.content.ContextWrapper(ctx) {
            override fun getFilesDir(): java.io.File {
                return java.io.File(freshDir, "files").apply { mkdirs() }
            }
            override fun getApplicationContext(): android.content.Context = this
        }
        val store = AnonymousIdStore(wrapper)
        val minted = store.getOrCreate()
        assertTrue(Regex("[0-9a-f-]{36}").matches(minted), minted)
        assertEquals(minted, store.getOrCreate())
        assertEquals(minted, store.accountId.first())
        freshDir.deleteRecursively()
    }

    @Test fun stripeKeyCacheRoundTrip() {
        val store = StripeKeyStore(ctx)
        store.save("")
        assertNull(store.getCached())
        store.save("pk_test_123")
        assertEquals("pk_test_123", store.getCached())
        // A fresh store instance over the same context sees the cached key.
        assertEquals("pk_test_123", StripeKeyStore(ctx).getCached())
        store.save("pk_live_456")
        assertEquals("pk_live_456", store.getCached())
    }
}
