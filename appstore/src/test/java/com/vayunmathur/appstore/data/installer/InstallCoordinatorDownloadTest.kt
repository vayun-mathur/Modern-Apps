package com.vayunmathur.appstore.data.installer

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.SplitFile
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.accrescent.AccrescentRepository
import com.vayunmathur.appstore.data.play.PlayRepository
import com.vayunmathur.appstore.data.security.ApkCertificates
import com.vayunmathur.appstore.data.security.VerificationResult
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The direct-download install path against a loopback server: bytes are
 * downloaded, size-checked (with one retry), verified, and the staged
 * files are cleaned up whatever the verdict. The served bytes are not a
 * valid APK, so every attempt must end refused at the verifier — never
 * committed to a PackageInstaller session.
 */
@RunWith(RobolectricTestRunner::class)
class InstallCoordinatorDownloadTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: InstallCoordinator
    private lateinit var context: Context
    private val apkHits = AtomicInteger(0)

    private val junk = "not-a-real-apk-but-served-bytes".toByteArray()

    @BeforeTest
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/app.apk") { exchange ->
            apkHits.incrementAndGet()
            exchange.sendResponseHeaders(200, junk.size.toLong())
            exchange.responseBody.use { it.write(junk) }
        }
        server.createContext("/split.apk") { exchange ->
            exchange.sendResponseHeaders(200, junk.size.toLong())
            exchange.responseBody.use { it.write(junk) }
        }
        server.createContext("/missing.apk") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        scope = CoroutineScope(Dispatchers.Unconfined)
        coordinator = InstallCoordinator(
            context = context,
            db = db,
            play = PlayRepository(context),
            accrescent = AccrescentRepository(context, db),
            scope = scope,
            ownSigningCertificates = { emptySet() },
        )
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        scope.cancel()
        db.close()
    }

    private fun fdroidApp(
        pkg: String,
        url: String? = "$base/app.apk",
        size: Long = junk.size.toLong(),
        sha: String? = null,
        splits: List<SplitFile> = emptyList(),
    ) = UnifiedApp(
        packageName = pkg,
        source = AppSource.FDROID,
        name = pkg,
        apkUrl = url,
        sizeBytes = size,
        apkSha256 = sha,
        splitFiles = splits,
    )

    @Test
    fun `downloaded junk is refused by the verifier and staged files are cleaned up`() = runTest {
        val pkg = "com.example.junkdl"
        val outcome = coordinator.install(fdroidApp(pkg, sha = ApkCertificates.sha256(junk)))
        assertFalse(outcome.started)
        val rejected = outcome.verification as VerificationResult.Rejected
        assertTrue(rejected.reason.contains("not a valid"), rejected.reason)
        assertIs<InstallStage.Failed>(coordinator.stages.value[pkg])
        assertEquals(rejected, coordinator.verification.value[pkg])
        // The staged APK must not linger in the cache after the attempt.
        assertFalse(File(context.cacheDir, "$pkg.apk").exists())
    }

    @Test
    fun `an http error fails the install with the status in the reason`() = runTest {
        val pkg = "com.example.missingdl"
        val outcome = coordinator.install(fdroidApp(pkg, url = "$base/missing.apk"))
        assertFalse(outcome.started)
        val rejected = outcome.verification as VerificationResult.Rejected
        assertTrue(rejected.reason.contains("404"), rejected.reason)
        assertIs<InstallStage.Failed>(coordinator.stages.value[pkg])
    }

    @Test
    fun `a truncated download is retried once then fails the install`() = runTest {
        val pkg = "com.example.truncated"
        val before = apkHits.get()
        val outcome = coordinator.install(fdroidApp(pkg, size = junk.size + 100L))
        assertFalse(outcome.started)
        val rejected = outcome.verification as VerificationResult.Rejected
        assertTrue(rejected.reason.contains("expected"), rejected.reason)
        // The path downloads once up front, again inside the size check,
        // and exactly once more as the truncation retry.
        assertEquals(before + 3, apkHits.get())
    }

    @Test
    fun `split sets download every file before verification refuses the set`() = runTest {
        val pkg = "com.example.splitdl"
        val app = fdroidApp(
            pkg,
            splits = listOf(SplitFile("split.apk", "$base/split.apk", ApkCertificates.sha256(junk), junk.size.toLong())),
        )
        val outcome = coordinator.install(app)
        assertFalse(outcome.started)
        assertIs<VerificationResult.Rejected>(outcome.verification)
        assertFalse(File(context.cacheDir, "$pkg.apk").exists())
        assertFalse(File(context.cacheDir, "$pkg.split0.apk").exists())
    }
}
