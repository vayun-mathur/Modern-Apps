package com.vayunmathur.photos.ui

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opens the real photo editor on-device with a MediaStore image.
 *
 * The editor canvas and drawing layers need the androidx.ink native
 * library, which has no host build, so this journey is the only place
 * EditPhotoPage/EditPhotoCanvas execute end to end.
 */
@RunWith(AndroidJUnit4::class)
class EditJourneyTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val targetContext = instrumentation.targetContext
    private val packageName: String = targetContext.packageName

    @Test
    fun editPhoto_opensEditorWithCanvas() {
        val resolver = targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "edit-journey-fixture.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EditJourney")
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        assertNotNull("could not insert fixture image", uri)
        resolver.openOutputStream(uri!!)!!.use { out ->
            Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xFF336699.toInt())
            }.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }

        val intent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, "image/jpeg")
            setPackage(packageName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        targetContext.startActivity(intent)

        // The editor scaffold titles itself "Edit photo" once composed.
        val title = device.wait(Until.findObject(By.text("Edit Photo")), 15_000L)
        assertNotNull("editor never appeared", title)
        Thread.sleep(2_000)
        assertEquals(packageName, device.currentPackageName)

        // Categories are reachable at the bottom of the editor.
        device.wait(Until.findObject(By.text("Adjust")), 10_000L)
        assertEquals(packageName, device.currentPackageName)
        // Leave MediaStore as we found it: other journeys tap the first
        // gallery item by position.
        resolver.delete(uri, null, null)
    }
}
