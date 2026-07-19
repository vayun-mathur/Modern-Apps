package com.vayunmathur.weather

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.data.WeatherDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Screenshot generator driven by `:weather:metadata`. Seeds a saved city so the app
 * fetches its live forecast on launch, then captures the home page.
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
        val dao = ctx.buildDatabase<WeatherDatabase>(dbName = "weather-db").weatherDao()
        runBlocking {
            dao.insertLocation(SavedLocation(name = "San Francisco", country = "United States", latitude = 37.7749, longitude = -122.4194, displayOrder = 0))
            dao.insertLocation(SavedLocation(name = "Tokyo", country = "Japan", latitude = 35.6762, longitude = 139.6503, displayOrder = 1))
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            // Wait until the live Open-Meteo forecast has loaded and rendered (network
            // latency varies), rather than a fixed sleep.
            composeRule.waitUntil(timeoutMillis = 45_000) {
                composeRule.onAllNodesWithText("Feels like", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            Thread.sleep(1000)
            snap(1)
        }
    }
}
