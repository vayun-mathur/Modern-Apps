package com.vayunmathur.madeveloper.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.madeveloper.data.GitHostRepository
import com.vayunmathur.madeveloper.domain.DevMessage
import com.vayunmathur.madeveloper.domain.InstallPhase
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The push socket and install state machine through the ViewModel's
 * transport/APK seams with an in-memory socket: ping/pong, app listing,
 * pairing, and the download -> verify -> ready pipeline including its
 * failure branches. The PackageInstaller session itself is the device
 * seam: Robolectric's installer shadow accepts the session, so the
 * INSTALLING transition is asserted, but no real install is performed.
 */
@RunWith(RobolectricTestRunner::class)
class MaDevPushInstallTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun setUp() {
        PairingStore(app).clear()
    }

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue(cond(), "timed out: $desc")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private class Harness {
        val transport = FakeTransport()
        var apkBytes: ByteArray = "fake-apk-bytes".toByteArray()
        var apkStatus: Int = 200
        val scope = CoroutineScope(Dispatchers.Main + Job())

        fun viewModel(app: Application): MaDevViewModel {
            val viewModel = MaDevViewModel(app)
            viewModel.repo = GitHostRepository(FakeGitHost().fetcher)
            viewModel.transportFactory = { _, _ -> transport }
            viewModel.apkFetcher = { apkStatus to apkBytes }
            return viewModel
        }
    }

    @Test fun socketLifecyclePingListAppsAndPairing() {
        val harness = Harness()
        val viewModel = harness.viewModel(app)
        viewModel.connectPush()
        await("connected") { viewModel.state.connected }

        harness.transport.incoming.tryEmit(DevMessage.Ping)
        await("pong sent") { synchronized(harness.transport.sent) { harness.transport.sent.contains("{\"type\":\"pong\"}") } }

        harness.transport.incoming.tryEmit(DevMessage.ListApps)
        await("app list sent") {
            synchronized(harness.transport.sent) {
                harness.transport.sent.any { it.contains("\"type\":\"apps\"") }
            }
        }

        harness.transport.incoming.tryEmit(DevMessage.Paired("tok-42", "dev-7"))
        await("paired state") { viewModel.state.paired }
        assertEquals("tok-42", PairingStore(app).token())
        assertEquals("dev-7", PairingStore(app).deviceId())

        harness.transport.incoming.tryEmit(DevMessage.Unknown("{\"type\":\"future\"}"))
        Thread.sleep(300)
        assertTrue(viewModel.state.connected, "unknown server messages must not drop the socket")
        harness.scope.cancel()
    }

    @Test fun pushedInstallDownloadsVerifiesAndBecomesReady() {
        val harness = Harness()
        val viewModel = harness.viewModel(app)
        viewModel.connectPush()
        await("connected") { viewModel.state.connected }

        val bytes = harness.apkBytes
        harness.transport.incoming.tryEmit(
            DevMessage.Install("com.example.app", 42L, "https://example.com/a.apk", sha256(bytes), bytes.size.toLong()),
        )
        await("install ready") {
            viewModel.state.pending.singleOrNull()?.phase == InstallPhase.READY
        }
        val pending = viewModel.state.pending.single()
        assertEquals("com.example.app", pending.pkg)
        assertEquals(42L, pending.versionCode)
        val file = File(app.cacheDir, "push-com.example.app.apk")
        assertTrue(file.exists(), "the verified APK is written to the cache for the installer")
        assertTrue(file.readBytes().contentEquals(bytes))

        // Tapping install moves it to INSTALLING (the session itself is the
        // device seam); an unready repeat tap is refused while installing.
        viewModel.installPending("com.example.app")
        await("installing") {
            viewModel.state.pending.singleOrNull()?.phase == InstallPhase.INSTALLING
        }
        // Installing a package that was never pushed is a no-op.
        viewModel.installPending("com.example.ghost")
        assertEquals(1, viewModel.state.pending.size)
        harness.scope.cancel()
    }

    @Test fun pushedInstallWithWrongHashFailsAndCachesNothingUsable() {
        val harness = Harness()
        val viewModel = harness.viewModel(app)
        viewModel.connectPush()
        await("connected") { viewModel.state.connected }
        harness.transport.incoming.tryEmit(
            DevMessage.Install(
                "com.example.bad", 1L, "https://example.com/b.apk",
                sha256("different bytes".toByteArray()), harness.apkBytes.size.toLong(),
            ),
        )
        await("install failed") {
            viewModel.state.pending.singleOrNull()?.phase == InstallPhase.FAILED
        }
        val pending = viewModel.state.pending.single()
        assertTrue(pending.error!!.contains("hash"), pending.error!!)
        // A failed download never becomes installable.
        viewModel.installPending("com.example.bad")
        assertEquals(InstallPhase.FAILED, viewModel.state.pending.single().phase)
        harness.scope.cancel()
    }

    @Test fun pushedInstallHttpFailureIsReported() {
        val harness = Harness()
        harness.apkStatus = 404
        val viewModel = harness.viewModel(app)
        viewModel.connectPush()
        await("connected") { viewModel.state.connected }
        harness.transport.incoming.tryEmit(
            DevMessage.Install(
                "com.example.gone", 3L, "https://example.com/gone.apk",
                sha256(harness.apkBytes), harness.apkBytes.size.toLong(),
            ),
        )
        await("install failed") {
            viewModel.state.pending.singleOrNull()?.phase == InstallPhase.FAILED
        }
        assertTrue(
            viewModel.state.pending.single().error!!.contains("404"),
            viewModel.state.pending.single().error!!,
        )
        assertFalse(File(app.cacheDir, "push-com.example.gone.apk").exists())
        harness.scope.cancel()
    }
}
