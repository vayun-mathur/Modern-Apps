package com.vayunmathur.photos.photoslice2

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.databases
import com.vayunmathur.photos.data.PhotoDatabase
import com.vayunmathur.photos.ui.EditActivity
import com.vayunmathur.photos.ui.VideoEditActivity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1000dp-h1400dp")
class ActivityIdleEditingPass3Test {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @BeforeTest
    fun seedDb() {
        synchronized(databases) {
            if (databases[PhotoDatabase::class] == null) {
                databases[PhotoDatabase::class] = Room
                    .inMemoryDatabaseBuilder(context, PhotoDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
            }
        }
    }

    /** Pumps the paused main looper and gives IO-dispatched work a chance to land. */
    private fun pump() {
        repeat(40) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25L)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `video edit activity resolves a view intent to the stand-in photo`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse("content://fixture/idle-clip.mp4"), "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val controller = Robolectric.buildActivity(VideoEditActivity::class.java, intent).setup()
        pump()
        assertNotNull(controller.get())
        assertTrue(!controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun `video edit activity without an intent target finishes`() {
        val intent = Intent(context, VideoEditActivity::class.java)
        val controller = Robolectric.buildActivity(VideoEditActivity::class.java, intent).setup()
        pump()
        assertTrue(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun `edit activity resolves an edit intent to the stand-in photo`() {
        val intent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(Uri.parse("content://fixture/idle-photo.jpg"), "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val controller = Robolectric.buildActivity(EditActivity::class.java, intent).setup()
        pump()
        assertNotNull(controller.get())
        assertTrue(!controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

}
