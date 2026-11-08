package com.vayunmathur.appstore.data.installer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.security.InstallRequirement
import com.vayunmathur.appstore.data.security.VerificationResult
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SessionInstaller refusal paths: nothing to install, and bytes the
 * verifier rejects (which must also be cleaned up, never committed).
 * The happy commit path needs a real PackageInstaller and is covered
 * on-device.
 */
@RunWith(RobolectricTestRunner::class)
class SessionInstallerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `empty file list is refused without starting`() = runTest {
        val outcome = SessionInstaller(context).installSplits(
            "com.example.app",
            emptyList(),
            InstallRequirement(expectedPackage = "com.example.app"),
        )
        assertFalse(outcome.started)
        assertIs<VerificationResult.Rejected>(outcome.verification)
    }

    @Test
    fun `unreadable bytes are refused and deleted`() = runTest {
        val junk = File(context.cacheDir, "session-junk.apk").apply { writeText("not an apk") }
        assertTrue(junk.exists())
        val outcome = SessionInstaller(context).installSplits(
            "com.example.app",
            listOf(junk),
            InstallRequirement(expectedPackage = "com.example.app"),
        )
        assertFalse(outcome.started)
        assertIs<VerificationResult.Rejected>(outcome.verification)
        assertFalse(junk.exists(), "rejected downloads must be cleaned up")
        assertEquals("com.example.app", InstallRequirement(expectedPackage = "com.example.app").expectedPackage)
    }
}
