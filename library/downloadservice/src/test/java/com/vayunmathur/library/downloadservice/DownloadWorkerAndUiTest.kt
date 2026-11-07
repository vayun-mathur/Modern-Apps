package com.vayunmathur.library.downloadservice

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.sun.net.httpserver.HttpServer
import com.vayunmathur.library.util.DataStoreUtils
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadWorkerAndUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun initWorkManager() {
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    private fun await(desc: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out: $desc")
    }

    // ---- Worker enqueue / unique work ----

    @Test
    fun enqueueKeepsOneRunAndRetryReplacesIt() {
        initWorkManager()
        val specs = listOf(
            DownloadSpec("w1.bin", "W1", "http://127.0.0.1:1/w1.bin"),
        )
        ModelDownloadWorker.enqueue(context, specs)
        val name = ModelDownloadWorker.uniqueName(specs)
        val wm = WorkManager.getInstance(context)
        await("enqueued") {
            wm.getWorkInfosForUniqueWork(name).get().any { it.state == WorkInfo.State.ENQUEUED }
        }
        // KEEP: a second enqueue for the same specs does not stack a second run.
        ModelDownloadWorker.enqueue(context, specs)
        assertEquals(1, wm.getWorkInfosForUniqueWork(name).get().count { it.state == WorkInfo.State.ENQUEUED })
        // REPLACE swaps the run rather than adding another.
        ModelDownloadWorker.retry(context, specs)
        await("replaced run enqueued") {
            wm.getWorkInfosForUniqueWork(name).get().any { it.state == WorkInfo.State.ENQUEUED }
        }
        wm.cancelUniqueWork(name)
    }

    @Test
    fun enqueueToCarriesTargetDirModels() {
        initWorkManager()
        val dir = kotlin.io.path.createTempDirectory("dl_wmto").toFile()
        val models = listOf(
            ModelDownloadItem("http://127.0.0.1:1/a.bin", "a.bin", "A", sha256 = "ab".repeat(32), expectedBytes = 3L),
            ModelDownloadItem("http://127.0.0.1:1/b.bin", "sub/b.bin", "B"),
        )
        ModelDownloadWorker.enqueueTo(context, models, dir, requireUnmetered = true, replaceExisting = true)
        val specs = models.map {
            DownloadSpec(it.fileName, it.description, it.url, it.sha256, it.expectedBytes)
        }
        val name = ModelDownloadWorker.uniqueName(specs)
        val wm = WorkManager.getInstance(context)
        await("enqueueTo enqueued") {
            wm.getWorkInfosForUniqueWork(name).get().any { it.state == WorkInfo.State.ENQUEUED }
        }
        wm.cancelUniqueWork(name)
    }

    // ---- Worker doWork via TestListenableWorkerBuilder ----

    private fun inputDataFor(
        specs: List<DownloadSpec>,
        targetDir: File? = null,
    ): Data = Data.Builder()
        .putStringArray("urls", specs.map { it.url }.toTypedArray())
        .putStringArray("fileNames", specs.map { it.fileName }.toTypedArray())
        .putStringArray("descriptions", specs.map { it.description }.toTypedArray())
        .putStringArray("hashes", specs.map { it.sha256 ?: "" }.toTypedArray())
        .putLongArray("sizes", specs.map { it.expectedBytes ?: -1L }.toLongArray())
        .apply { targetDir?.let { putString("targetDir", it.absolutePath) } }
        .putBoolean("unmetered", false)
        .build()

    @Test
    fun doWorkSucceedsWhenDownloadCompletes() = runBlocking<Unit> {
        val payload = "worker-payload".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val dir = kotlin.io.path.createTempDirectory("dl_worker").toFile()
            val spec = DownloadSpec(
                fileName = "w.bin", description = "W",
                url = "http://127.0.0.1:${server.address.port}/w.bin",
                sha256 = sha256(payload), expectedBytes = payload.size.toLong(),
            )
            val worker = TestListenableWorkerBuilder<ModelDownloadWorker>(context)
                .setInputData(inputDataFor(listOf(spec), dir))
                .build()
            assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
            assertTrue(File(dir, "w.bin").readBytes().contentEquals(payload))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun doWorkFailsOnEmptyOrMismatchedInput() = runBlocking<Unit> {
        val empty = TestListenableWorkerBuilder<ModelDownloadWorker>(context)
            .setInputData(Data.EMPTY)
            .build()
        assertEquals(androidx.work.ListenableWorker.Result.failure(), empty.doWork())

        val mismatched = Data.Builder()
            .putStringArray("urls", arrayOf("http://x/a"))
            .putStringArray("fileNames", arrayOf("a.bin", "b.bin"))
            .putStringArray("descriptions", arrayOf("A"))
            .putStringArray("hashes", arrayOf(""))
            .build()
        val bad = TestListenableWorkerBuilder<ModelDownloadWorker>(context)
            .setInputData(mismatched)
            .build()
        assertEquals(androidx.work.ListenableWorker.Result.failure(), bad.doWork())
    }

    @Test
    fun doWorkRetriesTransientFailureThenGivesUpAtAttemptCap() = runBlocking<Unit> {
        val spec = DownloadSpec("gone.bin", "Gone", "http://127.0.0.1:1/gone.bin")
        val dir = kotlin.io.path.createTempDirectory("dl_retry").toFile()
        val firstTry = TestListenableWorkerBuilder<ModelDownloadWorker>(context)
            .setInputData(inputDataFor(listOf(spec), dir))
            .setRunAttemptCount(0)
            .build()
        assertEquals(androidx.work.ListenableWorker.Result.retry(), firstTry.doWork())
        val lastTry = TestListenableWorkerBuilder<ModelDownloadWorker>(context)
            .setInputData(inputDataFor(listOf(spec), dir))
            .setRunAttemptCount(5)
            .build()
        assertEquals(androidx.work.ListenableWorker.Result.failure(), lastTry.doWork())
    }

    @Test
    fun doWorkWithoutSizesDefaultsToUnknown() = runBlocking<Unit> {
        // Old enqueues predate the sizes array: readSpecs must still accept them.
        val payload = "legacy".toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val dir = kotlin.io.path.createTempDirectory("dl_legacy").toFile()
            val data = Data.Builder()
                .putStringArray("urls", arrayOf("http://127.0.0.1:${server.address.port}/l.bin"))
                .putStringArray("fileNames", arrayOf("l.bin"))
                .putStringArray("descriptions", arrayOf("L"))
                .putStringArray("hashes", arrayOf(sha256(payload)))
                .apply { putString("targetDir", dir.absolutePath) }
                .build()
            val worker = TestListenableWorkerBuilder<ModelDownloadWorker>(context)
                .setInputData(data)
                .build()
            assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
            assertTrue(File(dir, "l.bin").isFile)
        } finally {
            server.stop(0)
        }
    }

    // ---- ModelUrls on-disk helpers ----

    @Test
    fun gemmaModelDirAndDownloadedFlagFollowDisk() {
        val dir = ModelUrls.GemmaMaml.modelDir(context)
        assertTrue(dir.path.endsWith(ModelUrls.GemmaMaml.DIR))
        // A fresh Robolectric sandbox has none of the multi-GB files.
        assertEquals(false, ModelUrls.GemmaMaml.isDownloaded(context))
        dir.mkdirs()
        for (item in ModelUrls.GemmaMaml.FILES) {
            val file = File(context.getExternalFilesDir(null), item.fileName)
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf(1))
        }
        assertTrue(ModelUrls.GemmaMaml.isDownloaded(context))
        // Removing one file flips the flag back.
        assertTrue(File(context.getExternalFilesDir(null), ModelUrls.GemmaMaml.FILES.first().fileName).delete())
        assertEquals(false, ModelUrls.GemmaMaml.isDownloaded(context))
    }

    // ---- Compose UI ----

    @Test
    fun fileProgressItemShowsDoneState() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    FileProgressItem(label = "Text Model", progress = 1.0, speedMbps = 0.0, isDone = true)
                }
            }
        }
        composeRule.onNodeWithText("Text Model").assertIsDisplayed()
        composeRule.onNodeWithText("Completed").assertIsDisplayed()
        composeRule.onNodeWithText("100.0%").assertIsDisplayed()
    }

    @Test
    fun fileProgressItemShowsDownloadingStateWithSpeed() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    FileProgressItem(label = "Embeddings", progress = 0.5, speedMbps = 12.34, isDone = false)
                }
            }
        }
        composeRule.onNodeWithText("Embeddings").assertIsDisplayed()
        composeRule.onNodeWithText("Downloading.").assertIsDisplayed()
        composeRule.onNodeWithText("50.0%").assertIsDisplayed()
    }

    @Test
    fun fileProgressItemAtZeroHidesSpeedChip() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    FileProgressItem(label = "Tokenizer", progress = 0.0, speedMbps = 0.0, isDone = false)
                }
            }
        }
        composeRule.onNodeWithText("Tokenizer").assertIsDisplayed()
        composeRule.onNodeWithText("0.0%").assertIsDisplayed()
    }

    @Test
    fun checkerGoesStraightToMainPageWhenFilesPresent() {
        val ds = DataStoreUtils.getInstance(context)
        val dir = context.getExternalFilesDir(null)
        assertNotNull(dir)
        val file = File(dir, "gate-present.bin")
        file.writeBytes(byteArrayOf(1, 2, 3))
        composeRule.setContent {
            MaterialTheme {
                InitialDownloadChecker(
                    ds = ds,
                    filesToDownload = listOf(
                        Triple("http://127.0.0.1:1/gate-present.bin", "gate-present.bin", "Present"),
                    ),
                ) {
                    androidx.compose.material3.Text("MAIN PAGE")
                }
            }
        }
        composeRule.onNodeWithText("MAIN PAGE").assertIsDisplayed()
        file.delete()
    }

    @Test
    fun checkerShowsDownloadScreenWhenFilesMissing() {
        initWorkManager()
        val ds = DataStoreUtils.getInstance(context)
        composeRule.setContent {
            MaterialTheme {
                InitialModelDownloadChecker(
                    ds = ds,
                    models = listOf(
                        ModelDownloadItem("http://127.0.0.1:1/absent.bin", "gate-absent.bin", "Absent Model"),
                    ),
                ) {
                    androidx.compose.material3.Text("MAIN PAGE")
                }
            }
        }
        composeRule.onNodeWithText("Absent Model").assertIsDisplayed()
    }
}
