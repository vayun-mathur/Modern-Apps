package com.vayunmathur.everysync

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.everysync.auth.AccountConfig
import com.vayunmathur.everysync.auth.AccountStore
import com.vayunmathur.everysync.provider.DataType
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Screenshot generator driven by `:everysync:metadata`. Seeds a couple of
 * accounts so the home screen renders a populated list, then captures it.
 */
@RunWith(AndroidJUnit4::class)
class MetadataScreenshots {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private val outDir: File by lazy {
        File(ctx.getExternalFilesDir(null), "metadata_screenshots").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun snap(index: Int) {
        val image = composeRule.onRoot().captureToImage()
        File(outDir, "$index.png").outputStream().use { out ->
            image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    @Test
    fun generateStoreScreenshots() {
        val store = AccountStore.getInstance(ctx)
        runBlocking {
            store.upsert(
                AccountConfig(
                    accountName = "jane@gmail.com (Google)",
                    providerId = "google",
                    enabledTypes = setOf(DataType.CONTACTS, DataType.CALENDAR),
                    lastSyncEpochMs = System.currentTimeMillis(),
                ),
            )
            store.upsert(
                AccountConfig(
                    accountName = "jane@icloud.com (Apple / iCloud)",
                    providerId = "icloud",
                    enabledTypes = setOf(DataType.CONTACTS, DataType.CALENDAR),
                    lastSyncEpochMs = System.currentTimeMillis(),
                ),
            )
            store.upsert(
                AccountConfig(
                    accountName = "jane@gmail.com (Google Health)",
                    providerId = "google_health",
                    enabledTypes = setOf(DataType.HEALTH),
                    lastSyncEpochMs = System.currentTimeMillis(),
                ),
            )
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithText("Google", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            Thread.sleep(500)
            snap(1)
        }
    }
}
