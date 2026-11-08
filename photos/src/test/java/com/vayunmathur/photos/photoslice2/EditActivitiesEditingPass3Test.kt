package com.vayunmathur.photos.photoslice2

import android.content.Intent
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.databases
import com.vayunmathur.photos.data.PhotoDatabase
import com.vayunmathur.photos.ui.EditActivity
import com.vayunmathur.photos.ui.VideoEditActivity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditActivitiesEditingPass3Test {

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

    @Test
    fun `edit activity creates with a photo id extra`() {
        val intent = Intent(context, EditActivity::class.java).putExtra("photo_id", 42L)
        val controller = Robolectric.buildActivity(EditActivity::class.java, intent).create()
        controller.start().resume()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun `edit activity creates from an edit intent with data`() {
        val intent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(android.net.Uri.parse("content://fixture/edit.jpg"), "image/jpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val controller = Robolectric.buildActivity(EditActivity::class.java, intent).create()
        controller.start().resume()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun `video edit activity creates with a photo id extra`() {
        val intent = Intent(context, VideoEditActivity::class.java).putExtra("photo_id", 7L)
        val controller = Robolectric.buildActivity(VideoEditActivity::class.java, intent).create()
        controller.start().resume()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun `video edit activity creates from a view intent`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(android.net.Uri.parse("content://fixture/clip.mp4"), "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val controller = Robolectric.buildActivity(VideoEditActivity::class.java, intent).create()
        controller.start().resume()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }
}
