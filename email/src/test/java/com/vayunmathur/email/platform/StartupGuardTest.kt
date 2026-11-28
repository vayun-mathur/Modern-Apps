package com.vayunmathur.email.platform

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.email.data.DateMillisBackfill
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.email.data.PeekContentBackfill
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for #939 ("crashes on startup", Android 15 device):
 * the Add Account gate composes from the accounts flow's initial empty
 * value, then every background startup step that touches the database or
 * WorkManager completes asynchronously. Before the fix those steps ran in
 * unguarded `launch` blocks, so a single failure — a RuntimeException or,
 * critically, an [Error] such as UnsatisfiedLinkError from a database that
 * will not open on that device — escaped as an uncaught coroutine
 * exception and exited the app about a second after the first frame.
 *
 * These tests pin the contract that now prevents that: startup steps are
 * isolated, `Throwable` (not just `Exception`) is contained, later steps
 * still run, and the one-time backfills survive a broken database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupGuardTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase

    @BeforeTest
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, EmailDatabase::class.java)
            .allowMainThreadQueries().build()
        resetRepositorySingleton()
        com.vayunmathur.library.util.databases[EmailDatabase::class] = db
    }

    @AfterTest
    fun close() {
        com.vayunmathur.library.util.databases.remove(EmailDatabase::class)
        resetRepositorySingleton()
        runCatching { db.close() }
    }

    private fun resetRepositorySingleton() {
        val field = EmailRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    @Test
    fun `a failing startup step is contained and later steps still run`() = runBlocking {
        var laterStepRan = false
        val failed = StartupGuard.runStep("broken database") {
            throw IllegalStateException("database failed to open")
        }
        val succeeded = StartupGuard.runStep("later step") { laterStepRan = true }
        assertFalse(failed)
        assertTrue(succeeded)
        assertTrue(laterStepRan)
    }

    @Test
    fun `an Error from a startup step does not escape`() = runBlocking {
        // The pre-fix failure mode: native/database failures surface as
        // Error, which every catch (Exception) in the old code missed.
        val result = StartupGuard.runStep("native load") {
            throw UnsatisfiedLinkError("libsqlcipher.so not loaded")
        }
        assertFalse(result)
    }

    @Test
    fun `a successful startup step reports true`() = runBlocking {
        assertTrue(StartupGuard.runStep("ok") { })
    }

    @Test
    fun `cancellation is not swallowed by the guard`() {
        assertFailsWith<CancellationException> {
            runBlocking { StartupGuard.runStep("cancelled") { throw CancellationException() } }
        }
    }

    @Test
    fun `backfills survive a database that will not open`() {
        // Point the repository at a closed database so every DAO call
        // throws, then run both backfills on a scope that records any
        // uncaught exception instead of crashing the process.
        db.close()
        val uncaught = AtomicReference<Throwable?>(null)
        val scope = CoroutineScope(
            Dispatchers.IO + CoroutineExceptionHandler { _, failure -> uncaught.set(failure) },
        )
        DateMillisBackfill.runIfNeeded(scope, context)
        PeekContentBackfill.runIfNeeded(scope, context)
        runBlocking { delay(2_000) }
        assertNull(uncaught.get(), "backfill failure escaped as uncaught: ${uncaught.get()}")
        assertEquals(
            null, uncaught.get(),
            "backfills must contain database failures, not crash startup (#939)",
        )
    }
}
