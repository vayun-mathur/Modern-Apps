package com.vayunmathur.music.intents

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AudioPreviewActivity lifecycle under Robolectric: the no-uri finish, and
 * a file-uri preview that builds its player, resolves a file-name title
 * (metadata lookup fails soft) and releases everything on destroy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioPreviewActivityTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `activity without a data uri finishes immediately`() {
        ActivityScenario.launch(AudioPreviewActivity::class.java).use { scenario ->
            // Finishing in onCreate destroys the activity before it resumes.
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }

    @Test
    fun `preview with a file uri opens and destroys cleanly`() {
        val file = File(context.cacheDir, "preview-song.mp3")
        file.writeBytes(ByteArray(128))
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.fromFile(file),
            context,
            AudioPreviewActivity::class.java,
        )
        try {
            ActivityScenario.launch<AudioPreviewActivity>(intent).use { scenario ->
                assertEquals(Lifecycle.State.RESUMED, scenario.state)
                scenario.onActivity { activity ->
                    assertTrue(!activity.isFinishing, "preview with a uri must stay open")
                }
                scenario.moveToState(Lifecycle.State.DESTROYED)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `preview with a content uri opens`() {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("content://media/external/audio/media/42"),
            context,
            AudioPreviewActivity::class.java,
        )
        ActivityScenario.launch<AudioPreviewActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(!activity.isFinishing)
            }
        }
    }
}
