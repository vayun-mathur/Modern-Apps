package com.vayunmathur.uniflix.platform

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.uniflix.data.AddonEntity
import com.vayunmathur.uniflix.data.UniflixDatabase
import com.vayunmathur.uniflix.data.UniflixRepository
import com.vayunmathur.uniflix.data.fakeHttpResponse
import com.vayunmathur.uniflix.data.uniflixTransport
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddonsRefreshCoverageTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = UniflixRepository.get(app)

    @BeforeTest fun setUp() {
        FakeAndroidKeyStore.install()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = Room.inMemoryDatabaseBuilder(app, UniflixDatabase::class.java)
            .allowMainThreadQueries().build()
        com.vayunmathur.library.util.databases[UniflixDatabase::class] = db
        UniflixRepository.resetInstanceForTest()
    }

    @AfterTest fun tearDown() { uniflixTransport = null }

    private fun await(timeoutMs: Long = 8_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (cond()) return
            Thread.sleep(20)
        }
        assertTrue(cond(), "condition not met")
    }

    @Test fun refreshAllUpdatesUrlAddonAndBadgesFailure() = runBlocking {
        val goodId = repo.upsertAddon(
            AddonEntity(
                manifestUrl = "https://addons.example.com/good/manifest.json",
                baseUrl = "https://addons.example.com/good",
                addonId = "old.good", name = "Old Good", version = "0.1",
            ),
        )
        val badId = repo.upsertAddon(
            AddonEntity(
                manifestUrl = "https://addons.example.com/bad/manifest.json",
                baseUrl = "https://addons.example.com/bad",
                addonId = "old.bad", name = "Old Bad",
            ),
        )
        val jsId = repo.upsertAddon(
            AddonEntity(
                manifestUrl = "js://plugin", baseUrl = "", addonId = "js.one",
                name = "JS One", kind = "js", jsSource = "// plugin",
            ),
        )
        uniflixTransport = { url ->
            when {
                url.contains("good") -> fakeHttpResponse(
                    200,
                    """{"id":"fresh.good","name":"Fresh Good","version":"2.0","resources":["stream"],"types":["movie","series"]}""",
                )
                else -> fakeHttpResponse(404, "nope")
            }
        }
        val vm = AddonsViewModel(app)
        // The addons flow must be collected for refreshAll to see the rows.
        val collectJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            vm.addons.collect {}
        }
        try {
            await { runBlocking { vm.addons.first() }.size == 3 }
            vm.refreshAll()
            await { !vm.refreshing }
            val good = repo.addonById(goodId)
            assertNotNull(good)
            assertEquals("fresh.good", good.addonId)
            assertEquals("Fresh Good", good.name)
            assertEquals("2.0", good.version)
            assertNull(good.lastError)
            // The failing addon keeps its identity and gains an error badge.
            val bad = repo.addonById(badId)
            assertNotNull(bad)
            assertEquals("old.bad", bad.addonId)
            assertNotNull(bad.lastError)
            // JS plugins are skipped by the HTTP refresh, untouched.
            assertEquals("js.one", repo.addonById(jsId)?.addonId)
            assertNull(repo.addonById(jsId)?.lastError)
        } finally {
            collectJob.cancel()
        }
    }

    @Test fun refreshAllWithNoAddonsCompletes() {
        val vm = AddonsViewModel(app)
        val collectJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            vm.addons.collect {}
        }
        try {
            vm.refreshAll()
            await { !vm.refreshing }
        } finally {
            collectJob.cancel()
        }
    }
}
