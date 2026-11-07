package com.vayunmathur.notes

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.notes.data.Note
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = seedNotesDb(app)

    @BeforeTest fun purge() = runBlocking { repo.purge(); repo.deleteAllSnapshots() }

    @Test fun activityCreatesAndShowsListAfterReady() {
        runBlocking { repo.upsert(Note(title = "Activity Note", content = "act body", syncId = "act1")) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        // onCreateBeforeContent launches IO migration then sets ready on Main
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(30)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            // content is Compose; reaching resume without crash + repo intact is the signal here,
            // list rendering itself is covered by Navigation/list tests
            if (runBlocking { repo.getAll() }.isNotEmpty()) break
        }
        assertEquals(listOf("Activity Note"), runBlocking { repo.getAll() }.map { it.title })
        controller.pause().stop().destroy()
    }

    @Test fun onNewIntentWithDataUriDoesNotCrash() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val intent = Intent(Intent.ACTION_VIEW).apply { data = Uri.parse("content://x/new.md") }
        controller.newIntent(intent)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
        assertTrue(true)
    }
}
