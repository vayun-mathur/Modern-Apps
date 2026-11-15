package com.vayunmathur.passwords.platform

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.domain.ImportSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasswordsViewModelExtraTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private fun vm() = PasswordsViewModel(app, PasswordRepository.get(app))

    @Test fun resolveColumnsMapsEverySourceHeaderFamily() {
        val viewModel = vm()

        val bitwarden = viewModel.resolveColumns(
            listOf("name", "login_username", "login_password", "login_uri", "login_totp"),
            ImportSource.BITWARDEN,
        )
        assertEquals(0, bitwarden.name)
        assertEquals(1, bitwarden.username)
        assertEquals(2, bitwarden.password)
        assertEquals(3, bitwarden.url)
        assertEquals(4, bitwarden.totp)

        // Fallback header spellings are honoured in order.
        val fallback = viewModel.resolveColumns(
            listOf("name", "username", "password", "uri", "totp"),
            ImportSource.BITWARDEN,
        )
        assertEquals(3, fallback.url)

        val proton = viewModel.resolveColumns(
            listOf("name", "username", "password", "url", "totp", "email", "note", "type"),
            ImportSource.PROTON_PASS,
        )
        assertEquals(5, proton.email)
        assertEquals(6, proton.note)
        assertEquals(7, proton.type)

        val onePassword = viewModel.resolveColumns(
            listOf("title", "username", "password", "url", "otpauth"),
            ImportSource.ONE_PASSWORD,
        )
        assertEquals(0, onePassword.name)
        assertEquals(4, onePassword.totp)

        // Dashlane's second username column is used when the first is absent.
        val dashlane = viewModel.resolveColumns(
            listOf("title", "username2", "password", "url", "otpsecret"),
            ImportSource.DASHLANE,
        )
        assertEquals(1, dashlane.username)
    }

    @Test fun resolveColumnsMissingColumnsAreMinusOne() {
        val viewModel = vm()
        val columns = viewModel.resolveColumns(listOf("unrelated", "columns"), ImportSource.CHROME)
        assertEquals(-1, columns.name)
        assertEquals(-1, columns.username)
        assertEquals(-1, columns.password)
        assertEquals(-1, columns.url)
        // Chrome maps its note column onto TOTP.
        val chrome = viewModel.resolveColumns(
            listOf("name", "username", "password", "url", "note"),
            ImportSource.CHROME,
        )
        assertEquals(4, chrome.totp)
        // Firefox has no TOTP header family at all.
        val firefox = viewModel.resolveColumns(
            listOf("url", "username", "password"),
            ImportSource.FIREFOX,
        )
        assertEquals(0, firefox.name) // url doubles as the entry name
        assertEquals(-1, firefox.totp)
        assertEquals(-1, firefox.email)
        assertEquals(-1, firefox.note)
        assertEquals(-1, firefox.type)
    }

    @Test fun copyWithFeedbackEmitsEventAndMarksClipSensitive() = runBlocking {
        val viewModel = vm()
        // Subscribe before copying: the shared flow has no replay, so a late
        // collector would miss the one-shot event.
        var value: String? = null
        val job = launch(Dispatchers.Default) { value = viewModel.copyEvents.first() }
        // Give the collector thread time to actually subscribe (the flow has no
        // replay, so an emission before subscription would be lost by design).
        Thread.sleep(500)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        viewModel.copyToClipboard("totp", "123456", feedback = "TOTP copied")
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("123456", clipboard.primaryClip?.getItemAt(0)?.text)
        assertEquals(
            true,
            clipboard.primaryClip?.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE),
        )
        // viewModelScope posts on the main looper under Robolectric; idle it while awaiting.
        val deadline = System.currentTimeMillis() + 4_000
        while (value == null && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        job.cancel()
        assertEquals("TOTP copied", value)
    }

    @Test fun copyWithoutFeedbackEmitsNoEvent() {
        val viewModel = vm()
        viewModel.copyToClipboard("password", "pw")
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("pw", clipboard.primaryClip?.getItemAt(0)?.text)
        // No subscriber can receive an event that was never emitted: the shared flow has no replay.
        assertEquals(0, viewModel.copyEvents.replayCache.size)
    }

    @Test fun draftTransformsChainAndReseedingRules() {
        val viewModel = vm()
        viewModel.initDraft(Password(id = 9, name = "Base", username = "u", websites = listOf("a.com")))
        viewModel.updateDraft { it.copy(name = it.name + "!", totpSecret = "SEED") }
        viewModel.updateDraft { it.copy(websites = it.websites + "b.com") }
        val draft = viewModel.draft.value
        assertEquals("Base!", draft?.name)
        assertEquals("SEED", draft?.totpSecret)
        assertEquals(listOf("a.com", "b.com"), draft?.websites)

        // Reseeding with the same id preserves edits; clearing allows a fresh seed.
        viewModel.initDraft(Password(id = 9, name = "Replacement"))
        assertEquals("Base!", viewModel.draft.value?.name)
        viewModel.clearDraft()
        assertNull(viewModel.draft.value)
        viewModel.initDraft(Password(id = 9, name = "Replacement"))
        assertEquals("Replacement", viewModel.draft.value?.name)
    }

    @Test fun tickerEmitsRepeatedlyWhileSubscribed() = runBlocking {
        val viewModel = vm()
        val values = mutableListOf<Long>()
        val job = launch(Dispatchers.Default) {
            viewModel.tickerFlow.collect { value ->
                val count = synchronized(values) { values += value; values.size }
                if (count >= 2) throw kotlinx.coroutines.CancellationException("enough ticks")
            }
        }
        // The ticker runs on viewModelScope (main looper): idle it while waiting
        // for the one-second tick to deliver a second value.
        val deadline = System.currentTimeMillis() + 6_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val count = synchronized(values) { values.size }
            if (count >= 2) break
            Thread.sleep(10)
        }
        job.cancel()
        val snapshot = synchronized(values) { values.toList() }
        assertTrue(snapshot.size >= 2, "expected at least 2 ticks, got $snapshot")
        assertTrue(snapshot[1] >= snapshot[0])
    }

    @Test fun tickerStartsNearNowAndUiContractsHaveSaneDefaults() {
        val viewModel = vm()
        val before = System.currentTimeMillis()
        val tick = viewModel.tickerFlow.value
        assertTrue(tick in before - 10_000..System.currentTimeMillis() + 10_000)

        val menu = MenuUiState()
        assertTrue(menu.passwords.isEmpty())
        assertTrue(menu.passkeys.isEmpty())
        assertFalse(menu.loading)
        assertEquals(0L, menu.now())
        val edit = PasswordEditUiState()
        assertEquals("", edit.saved.name)
        assertEquals(0L, edit.saved.id)
        assertNull(edit.draft)
        // The Noop actions object accepts every callback without effect.
        PasswordsActions.Noop.copyToClipboard("x", "y")
        PasswordsActions.Noop.delete(Password())
        PasswordsActions.Noop.updateDraft { it }
        PasswordsActions.Noop.saveDraft()
    }
}
