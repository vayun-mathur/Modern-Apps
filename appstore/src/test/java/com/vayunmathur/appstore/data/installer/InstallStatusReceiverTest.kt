package com.vayunmathur.appstore.data.installer

import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The PackageInstaller callback bridge: terminal statuses become
 * [InstallEvents] results, user-action forwards with no intent are
 * dropped, and failures are collected while a batch is active.
 */
@RunWith(RobolectricTestRunner::class)
class InstallStatusReceiverTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun intent(status: Int, pkg: String? = "com.example.app", message: String? = null) =
        Intent(InstallStatusReceiver.ACTION_INSTALL_STATUS).apply {
            putExtra(PackageInstaller.EXTRA_STATUS, status)
            putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, pkg)
            if (message != null) putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, message)
        }

    private fun receive(intent: Intent): InstallResult? = runBlocking {
        val deferred = async { withTimeoutOrNull(2_000) { InstallEvents.results.first() } }
        // Publish from a delayed coroutine so the collector has subscribed
        // before the (unreplayed) event fires.
        launch {
            kotlinx.coroutines.delay(200)
            InstallStatusReceiver().onReceive(context, intent)
        }
        deferred.await()
    }

    @Test
    fun `success publishes a successful result`() {
        assertEquals(
            InstallResult("com.example.app", success = true),
            receive(intent(PackageInstaller.STATUS_SUCCESS)),
        )
    }

    @Test
    fun `aborted publishes an unsuccessful result without being a failure`() {
        assertEquals(
            InstallResult("com.example.app", success = false),
            receive(intent(PackageInstaller.STATUS_FAILURE_ABORTED)),
        )
    }

    @Test
    fun `a failure status publishes an unsuccessful result`() {
        assertEquals(
            InstallResult("com.example.app", success = false),
            receive(intent(PackageInstaller.STATUS_FAILURE_STORAGE, message = "no space")),
        )
    }

    @Test
    fun `a result with no package publishes nothing`() {
        assertEquals(null, receive(intent(PackageInstaller.STATUS_SUCCESS, pkg = null)))
    }

    @Test
    fun `pending user action without a forwarded intent is a no-op`() {
        // Must simply return: no event, no crash.
        InstallStatusReceiver().onReceive(
            context,
            intent(PackageInstaller.STATUS_PENDING_USER_ACTION),
        )
    }

    @Test
    fun `failure batch collects only while active`() {
        assertFalse(InstallFailureBatch.collect("outside"))
        InstallFailureBatch.begin()
        try {
            assertTrue(InstallFailureBatch.collect("first failure"))
            assertTrue(InstallFailureBatch.collect("second failure"))
            assertEquals(listOf("first failure", "second failure"), InstallFailureBatch.end())
        } finally {
            InstallFailureBatch.end()
        }
        assertTrue(InstallFailureBatch.end().isEmpty())
    }

    @Test
    fun `install events can be published directly`() = runBlocking {
        val deferred = async { withTimeoutOrNull(2_000) { InstallEvents.results.first() } }
        launch {
            kotlinx.coroutines.delay(200)
            InstallEvents.publish(InstallResult("com.example.direct", success = true))
        }
        assertEquals(InstallResult("com.example.direct", success = true), deferred.await())
    }
}
