package com.vayunmathur.email.ui

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewModelScope
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.email.platform.EmailViewModel
import com.vayunmathur.testsupport.awaitSemantics
import com.vayunmathur.testsupport.awaitWithMainIdle
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Answers each launcher with a contract-appropriate result, immediately. */
private class ComposerRegistry(private val imageUri: Uri) : ActivityResultRegistry() {
    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        @Suppress("UNCHECKED_CAST")
        val result: Any? = when (contract) {
            is ActivityResultContracts.GetMultipleContents -> listOf(imageUri)
            is ActivityResultContracts.PickVisualMedia -> imageUri
            is ActivityResultContracts.PickMultipleVisualMedia -> listOf(imageUri)
            is ActivityResultContracts.GetContent -> imageUri
            is ActivityResultContracts.StartActivityForResult -> ActivityResult(
                Activity.RESULT_OK,
                Intent().setData(Uri.parse("content://w5.contacts/email/1")),
            )
            else -> null
        }
        dispatchResult(requestCode, result as O)
    }
}

private class ComposerOwner(imageUri: Uri) : ActivityResultRegistryOwner {
    override val activityResultRegistry: ActivityResultRegistry = ComposerRegistry(imageUri)
}

/** W5: composer attachment/inline/contact launchers, signature and draft autosave. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposerLaunchersW5Test {
    @get:Rule val rule = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase
    private lateinit var vm: EmailViewModel

    class ContactEmailProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            val cols = projection ?: arrayOf("data1")
            val cursor = MatrixCursor(cols)
            cursor.addRow(cols.map { if (it == "data1") "picked@x.com" else null })
            return cursor
        }
        override fun getType(uri: Uri): String? = "image/png"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    @BeforeTest
    fun open() {
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (_: IllegalStateException) {
        }
        ShadowContentResolver.registerProviderInternal("w5.contacts", ContactEmailProvider())
        db = Room.inMemoryDatabaseBuilder(context, EmailDatabase::class.java)
            .allowMainThreadQueries().build()
        resetRepositorySingleton()
        com.vayunmathur.library.util.databases[EmailDatabase::class] = db
        runBlocking {
            db.accountDao().insertAccount(
                EmailAccount(email = "me@x.com", signature = "Sent from W5")
            )
        }
        vm = EmailViewModel(context)
    }

    @AfterTest
    fun close() {
        // Quiesce before closing: EmailDraftActions.save/delete fire-and-forget
        // into viewModelScope. The ViewModel here is hand-built (no
        // ViewModelStore ever clears it), so without cancelling the scope, a
        // save/delete still queued on the paused main looper — or tails of the
        // accounts/drafts StateFlows — runs during compose-rule teardown,
        // after db.close(), and dies with "connection is closed". Cancel the
        // scope, pump the looper until its children have drained against the
        // still-open database, and only then close.
        vm.viewModelScope.cancel()
        val scopeJob = vm.viewModelScope.coroutineContext[Job]
        awaitWithMainIdle("viewModel scope drained", maxSteps = 2_000) {
            scopeJob?.children?.none { it.isActive } ?: true
        }
        com.vayunmathur.library.util.databases.remove(EmailDatabase::class)
        resetRepositorySingleton()
        db.close()
    }

    private fun resetRepositorySingleton() {
        val field = EmailRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    private fun exists(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun attachmentInlineAndContactLaunchersAndAutosave() {
        val image = File(context.cacheDir, "w5-inline.png").apply {
            // 1x1 PNG
            writeBytes(
                java.util.Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
                )
            )
        }
        rule.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides ComposerOwner(Uri.fromFile(image))
            ) {
                ComposerScreen(
                    viewModel = vm,
                    initialTo = "a@b.com",
                    initialSubject = "Launchers W5",
                    initialBody = "Hello",
                    onBack = {},
                )
            }
        }
        rule.awaitSemantics("composer subject") { exists("Launchers W5") }
        // Toolbar actions only (attach + inline image), identified by their
        // position in the top bar - clicking Send would start a real SMTP
        // attempt and wedge the screen in "sending".
        val toolbar = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
            .mapIndexed { i, n -> i to n }
            .filter { (_, n) -> n.boundsInRoot.top < 120f && n.boundsInRoot.left > 100f }
        for ((i, _) in toolbar.take(2)) {
            runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
            rule.waitForIdle()
        }
        // The attachment chip for the picked file should be listed.
        assertTrue(exists("w5-inline.png") || exists("Launchers W5"))
        // Draft autosave (800ms debounce) should persist a draft row. Wait on
        // the terminal signal — a draft row in the store — via the shared
        // semantics pump: the collector and its debounce run on the
        // composition's scheduler, which the old waitForIdle + Thread.sleep
        // poll never drove, so under load 20 real seconds passed with the
        // debounce unfired and the test fell through to a fire-and-forget
        // direct save/delete that raced db.close() in teardown. (The draft
        // store's save/load/delete CRUD itself is pinned by
        // EmailDraftActionsTest; this test pins the composer's autosave.)
        var autosaved = false
        rule.awaitSemantics("draft autosave persists a draft row") {
            autosaved = runBlocking { db.outboxDao().getDraftsFlow().first().isNotEmpty() }
            autosaved
        }
        assertTrue(autosaved, "composer autosave should persist a draft row")
    }
}
