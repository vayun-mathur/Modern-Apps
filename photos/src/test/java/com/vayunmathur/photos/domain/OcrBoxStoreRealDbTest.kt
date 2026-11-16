package com.vayunmathur.photos.domain

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.OcrBox
import com.vayunmathur.photos.data.OcrLayout
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.data.VideoData
import com.vayunmathur.photos.data.toJson
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [OcrBoxStore.layoutFor] resolution order: videos and tiny images are
 * skipped, stored geometry is parsed back, and photos with nothing stored
 * fall through to the on-demand engine (absent on this host, so null).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OcrBoxStoreRealDbTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var installed: TestPhotosRepository.Installed

    @BeforeTest
    fun setup() {
        installed = TestPhotosRepository.install(app)
    }

    private fun photo(name: String, width: Int = 800, height: Int = 600, video: Boolean = false) = Photo(
        name = name, uri = "content://media/$name", date = 1L, width = width, height = height,
        dateModified = 1L, exifSet = true, lat = null, long = null,
        videoData = if (video) VideoData(1_000L) else null, panoData = null,
    )

    @Test fun storedLayoutIsParsedWithoutEngine() = runBlocking {
        val id = installed.photos.upsertAll(listOf(photo("stored"))).let { installed.photos.getAll().first().id }
        val stored = installed.photos.getAll().first { it.id == id }
        val layout = OcrLayout(
            w = 800, h = 600,
            boxes = listOf(OcrBox("hello world", 80, 120, 400, 240)),
        )
        installed.scan.setOcrBoxes(id, layout.toJson())
        val resolved = OcrBoxStore.layoutFor(app, stored)
        assertNotNull(resolved)
        assertEquals(800, resolved.w)
        assertEquals(listOf("hello world"), resolved.boxes.map { it.text })
        Unit
    }

    @Test fun videoAndTinyPhotosAreSkipped() = runBlocking {
        installed.photos.upsertAll(listOf(photo("clip", video = true), photo("tiny", width = 32, height = 32)))
        val all = installed.photos.getAll().associateBy { it.name }
        assertNull(OcrBoxStore.layoutFor(app, all.getValue("clip")))
        assertNull(OcrBoxStore.layoutFor(app, all.getValue("tiny")))
        Unit
    }

    @Test fun missingLayoutFallsThroughToUnavailableEngine() = runBlocking {
        installed.photos.upsertAll(listOf(photo("plain")))
        val stored = installed.photos.getAll().first()
        // No stored boxes and no OCR model on the host: null, not a crash,
        // and no boxes are written back.
        assertNull(OcrBoxStore.layoutFor(app, stored))
        assertNull(installed.scan.getOcrBoxes(stored.id))
        Unit
    }

}
