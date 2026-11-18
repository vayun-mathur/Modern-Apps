package com.vayunmathur.camera.data

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** prepareStillSave routing: SAF tree falls back to MediaStore, never loses the shot. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafPrepareExtraTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = com.vayunmathur.camera.platform.CameraViewModel(
        context.applicationContext as android.app.Application,
    )

    @Test fun safTargetFallsBackToMediaStorePending() {
        val viewModel = vm()
        val tree = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3APictures%2FCamera",
        )
        viewModel.setSaveTreeUri(tree)
        val pending = with(viewModel) { prepareStillSave("IMG_saf.jpg") }
        assertNotNull(pending)
        assertEquals("IMG_saf.jpg", pending.displayName)
        // The harness cannot create SAF documents: MediaStore fallback has no
        // pre-created save Uri and no stream.
        assertNull(pending.saveUri)
        assertNull(pending.stream)
        pending.closeStream()
    }

    @Test fun safDocumentsHelpersDegrade() {
        val tree = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3APictures",
        )
        assertNull(SafDocuments.createImageDoc(context.contentResolver, tree, "x.jpg"))
        assertNull(SafDocuments.createVideoDoc(context.contentResolver, tree, "x.mp4"))
        assertEquals(
            false,
            SafDocuments.hasPersistedPermission(context.contentResolver, tree),
        )
        // Taking a permission the harness never granted must not throw.
        SafDocuments.takePersistablePermission(context.contentResolver, tree)
        assertNull(SafDocuments.displayName(context.contentResolver, tree))
    }

    @Test fun safPrimaryPathParsing() {
        val primary = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3APictures%2FTestCam",
        )
        val path = SafDocuments.primaryPath(primary)
        assertNotNull(path)
        assertTrue(path.endsWith("/Pictures/TestCam"))
        val sdCard = Uri.parse(
            "content://com.android.externalstorage.documents/tree/1234-5678%3APictures",
        )
        assertNull(SafDocuments.primaryPath(sdCard))
        assertNull(SafDocuments.primaryPath(Uri.parse("content://other/thing")))
    }
}
