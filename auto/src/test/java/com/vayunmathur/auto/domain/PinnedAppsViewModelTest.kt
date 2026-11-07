package com.vayunmathur.auto.domain

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.data.PinnedAppsPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PinnedAppsViewModelTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var store = ViewModelStore()

    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }

    // runBlocking (not runTest) so DataStore edits on IO actually land before
    // the next test class in this JVM reads the synchronous mirror.
    @AfterTest fun tearDown() = runBlocking {
        store.clear()
        PinnedAppsPrefs.setPinned(ctx, emptyList())
        Dispatchers.resetMain()
    }

    private fun vm(): PinnedAppsViewModel {
        store = ViewModelStore()
        return ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                PinnedAppsViewModel(app) as T
        })[PinnedAppsViewModel::class.java]
    }

    @Test fun `defaults are empty with no discovered apps`() = runBlocking {
        PinnedAppsPrefs.setPinned(ctx, emptyList())
        val vm = vm()
        assertEquals(emptyList(), vm.allApps.value)
        assertNull(vm.rejected.value)
    }

    @Test fun `pinned ids flow through while subscribed and rejection clears`() = runBlocking {
        PinnedAppsPrefs.setPinned(ctx, listOf("com.a/.Car"))
        val vm = vm()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { vm.pinnedIds.collect() }
        try {
            assertEquals(listOf("com.a/.Car"), vm.pinnedIds.value)
        } finally { job.cancel() }
        vm.consumeRejected()
        assertNull(vm.rejected.value)
        assertTrue(vm.rows.value.isEmpty())
    }

    @Test fun `move on absent or first ids writes nothing`() = runBlocking {
        PinnedAppsPrefs.setPinned(ctx, listOf("a", "b"))
        val vm = vm()
        vm.moveUp("zzz"); vm.moveDown("zzz"); vm.moveUp("a")
        assertEquals(listOf("a", "b"), PinnedAppsPrefs.pinnedNow(ctx))
    }

    @Test fun `refresh keeps empty discovery on this device`() = runBlocking {
        val vm = vm()
        vm.refresh()
        assertEquals(emptyList(), vm.allApps.value)
    }
}
