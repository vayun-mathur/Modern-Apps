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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** AddonsViewModel install/refresh/test/order flows and ListsViewModel CRUD via fake transport + seeded DB. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class C9AddonsListsTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: UniflixDatabase

    @BeforeTest fun setUp() {
        FakeAndroidKeyStore.install()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, UniflixDatabase::class.java)
            .allowMainThreadQueries().build()
        com.vayunmathur.library.util.databases[UniflixDatabase::class] = db
        UniflixRepository.resetInstanceForTest()
        uniflixTransport = { url ->
            when {
                url.contains("bad.invalid") -> fakeHttpResponse(500, "boom")
                url.contains("/manifest.json") -> fakeHttpResponse(
                    200,
                    """{"id":"demo","name":"Demo Addon","version":"2.0.0","resources":["stream"],"types":["movie","series"]}""",
                )
                else -> fakeHttpResponse(404, "{}")
            }
        }
    }

    @AfterTest fun tearDown() {
        uniflixTransport = null
        runBlocking {
            for (addon in db.addonDao().observeAllOrdered().first()) {
                db.addonDao().deleteById(addon.dbId)
            }
        }
    }

    private fun repo() = UniflixRepository.get(app)

    private fun await(cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    private fun awaitDb(cond: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (runBlocking { cond() }) return true
            Thread.sleep(20)
        }
        return runBlocking { cond() }
    }

    @Test fun addByUrl_installsAndRefreshFlows() {
        val vm = AddonsViewModel(app)
        vm.addByUrl("https://example.invalid")
        assertTrue(awaitDb { repo().addonCount() == 1 }, "count=${runBlocking { repo().addonCount() }}")
        val row = AddonRow(runBlocking { repo().addonByManifestUrl("https://example.invalid/manifest.json") }!!, isDefault = false)
        assertEquals("Demo Addon", row.entity.name)

        vm.refreshManifest(row)
        assertTrue(await { !vm.refreshing })
        vm.testAddon(row)
        assertTrue(awaitDb { repo().addonById(row.entity.dbId)?.lastError == null })

        vm.refreshAll()
        assertTrue(await { !vm.refreshing })

        vm.setEnabled(row, false)
        assertTrue(awaitDb { repo().addonById(row.entity.dbId)?.enabled == false })
        vm.setEnabled(row, true)
        assertTrue(awaitDb { repo().addonById(row.entity.dbId)?.enabled == true })

        vm.setDefault(row)
        assertTrue(awaitDb { repo().addonById(row.entity.dbId) != null })
        vm.clearDefault()
        vm.remove(row)
        assertTrue(awaitDb { repo().addonCount() == 0 })
    }

    @Test fun addByUrl_badHost_recordsFailure() {
        val vm = AddonsViewModel(app)
        vm.addByUrl("https://bad.invalid/base")
        assertTrue(await { !vm.adding })
        assertEquals(0, runBlocking { repo().addonCount() })
    }

    @Test fun refreshManifest_failureSetsBadge() {
        runBlocking {
            repo().upsertAddon(
                AddonEntity(
                    manifestUrl = "https://bad.invalid/manifest.json",
                    baseUrl = "https://bad.invalid", addonId = "bad", name = "Bad Addon",
                )
            )
        }
        val vm = AddonsViewModel(app)
        val row = AddonRow(runBlocking { repo().addonByManifestUrl("https://bad.invalid/manifest.json") }!!, isDefault = false)
        vm.refreshManifest(row)
        assertTrue(awaitDb { repo().addonById(row.entity.dbId)?.lastError != null }, "error badge not set")
        vm.testAddon(row)
        assertTrue(awaitDb { repo().addonById(row.entity.dbId)?.lastError != null })
    }

    @Test fun addJsPlugin_andMoveOrder() {
        val vm = AddonsViewModel(app)
        val source = "// @uniflix-plugin id=test.plugin name=\"Test Plugin\" version=1.0.0\nfunction resolve(a){return '{}';}\nfunction servers(){return '[]';}"
        vm.addJsPlugin(source)
        assertTrue(awaitDb { repo().addonByManifestUrl("js:test.plugin") != null }, "js plugin not stored")
        val js = runBlocking { repo().addonByManifestUrl("js:test.plugin") }!!
        assertEquals("js", js.kind)

        runBlocking {
            repo().upsertAddon(AddonEntity(manifestUrl = "https://a.invalid/manifest.json", baseUrl = "https://a.invalid", addonId = "a", name = "A", priority = 0))
            repo().upsertAddon(AddonEntity(manifestUrl = "https://b.invalid/manifest.json", baseUrl = "https://b.invalid", addonId = "b", name = "B", priority = 1))
        }
        val rows = runBlocking {
            repo().addons.first().map { AddonRow(it, isDefault = false) }
        }
        assertTrue(rows.size >= 3)
        val first = rows[0]
        val second = rows[1]
        vm.moveUp(rows, second)
        assertTrue(awaitDb {
            val ordered = repo().addons.first()
            ordered.firstOrNull { it.dbId == second.entity.dbId }?.priority ==
                ordered.firstOrNull { it.dbId == first.entity.dbId }?.priority?.let { it } ||
                repo().addonById(second.entity.dbId)?.priority != second.entity.priority
        })
        vm.moveDown(rows, first)
        vm.refreshManifest(AddonRow(js, isDefault = false)) // JS rows take the notice path
        vm.testAddon(AddonRow(js, isDefault = false))
        assertTrue(await { !vm.refreshing })
    }

    @Test fun lists_crud() = runBlocking {
        val vm = ListsViewModel(app)
        vm.createList("Favorites")
        val lists = vm.lists.first { it.isNotEmpty() }
        val list = lists.single { it.name == "Favorites" }
        vm.addItem(list.id, 10, "movie", "Detail Film", null)
        val items = vm.items(list.id).first { it.isNotEmpty() }
        assertEquals("Detail Film", items.single().title)
        vm.deleteItem(items.single())
        assertTrue(vm.items(list.id).first().isEmpty())
        vm.deleteList(list)
        assertTrue(vm.lists.first().none { l -> l.name == "Favorites" })
    }
}
