package com.vayunmathur.passwords.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.PasswordRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pass-3: VM copy-events, ticker, and draft seeding edges not covered before. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasswordsViewModelPass3Test {

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun vm() = PasswordsViewModel(app, PasswordRepository.get(app))

    @Test
    fun copyWithFeedbackEmitsCopyEvent() = runBlocking {
        val viewModel = vm()
        val events = mutableListOf<String>()
        val job = launch { viewModel.copyEvents.collect { events += it } }
        // SharedFlow has no replay; give the collector a moment to subscribe
        withTimeoutOrNull(2000) {
            while (job.children.count() == 0 && events.isEmpty()) kotlinx.coroutines.delay(10)
            kotlinx.coroutines.delay(50)
        }
        viewModel.copyToClipboard("password", "s3cret", feedback = "Password copied")
        withTimeoutOrNull(3000) {
            while (events.isEmpty()) kotlinx.coroutines.delay(10)
        }
        job.cancel()
        assertEquals(listOf("Password copied"), events.toList())
        Unit
    }

    @Test
    fun copyWithoutFeedbackEmitsNothing() = runBlocking {
        val viewModel = vm()
        val events = mutableListOf<String>()
        val job = launch { viewModel.copyEvents.collect { events += it } }
        kotlinx.coroutines.delay(100)
        viewModel.copyToClipboard("password", "s3cret")
        kotlinx.coroutines.delay(200)
        job.cancel()
        assertTrue(events.isEmpty())
        Unit
    }

    @Test
    fun tickerEmitsCurrentTime() = runBlocking {
        val viewModel = vm()
        val before = System.currentTimeMillis()
        val value = withTimeoutOrNull(5000) { viewModel.tickerFlow.first { it >= before } }
        assertNotNull(value)
        Unit
    }

    @Test
    fun draftSeedWithSameIdAfterClearReseeds() {
        val viewModel = vm()
        viewModel.initDraft(Password(id = 9, name = "First"))
        viewModel.updateDraft { it.copy(name = "Edited") }
        assertEquals("Edited", viewModel.draft.value?.name)
        viewModel.clearDraft()
        assertNull(viewModel.draft.value)
        viewModel.initDraft(Password(id = 9, name = "Second"))
        assertEquals("Second", viewModel.draft.value?.name)
        // updateDraft transforms only the open draft
        viewModel.updateDraft { it.copy(username = "octocat") }
        assertEquals("octocat", viewModel.draft.value?.username)
    }
}
