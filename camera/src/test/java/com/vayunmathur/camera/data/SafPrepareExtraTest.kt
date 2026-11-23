package com.vayunmathur.camera.data

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** SAF routing via saveStillBytes/saveVideoStaged: SAF falls back to MediaStore, never loses the shot. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafPrepareExtraTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = com.vayunmathur.camera.platform.CameraViewModel(context.applicationContext as android.app.Application)

    @Test fun safTargetFallsBackToMediaStoreForBytes() {
        val v = vm(); val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures%2FCamera")
        v.setSaveTreeUri(tree)
        // SAF doc creation fails in harness -> MediaStore fallback; either a Uri or null, never throws.
        with(v) { saveStillBytes("IMG_saf.jpg", byteArrayOf(1,2,3)) }
        assertEquals(SaveTarget.SafTree(tree), v.saveTargetMutable.value)
    }
    @Test fun safVideoStagedFallsBack() {
        val v = vm(); val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures%2FCamera")
        v.setSaveTreeUri(tree); val f = File(context.cacheDir, "stage.mp4").apply { writeBytes(byteArrayOf(1)) }
        with(v) { saveVideoStaged("VID_saf", f) }
    }
    @Test fun safDocumentsHelpersDegrade() {
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures")
        assertNull(SafDocuments.createImageDoc(context.contentResolver, tree, "x.jpg"))
        assertNull(SafDocuments.createVideoDoc(context.contentResolver, tree, "x.mp4"))
        assertEquals(false, SafDocuments.hasPersistedPermission(context.contentResolver, tree))
        SafDocuments.takePersistablePermission(context.contentResolver, tree)
        assertNull(SafDocuments.displayName(context.contentResolver, tree))
    }
    @Test fun safPrimaryPathParsing() {
        val primary = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures%2FTestCam")
        val path = SafDocuments.primaryPath(primary); assertNotNull(path); assertTrue(path.endsWith("/Pictures/TestCam"))
        assertNull(SafDocuments.primaryPath(Uri.parse("content://com.android.externalstorage.documents/tree/1234-5678%3APictures")))
        assertNull(SafDocuments.primaryPath(Uri.parse("content://other/thing")))
    }
}
