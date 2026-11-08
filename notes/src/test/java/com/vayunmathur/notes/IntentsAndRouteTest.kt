package com.vayunmathur.notes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.intents.notes.NoteData
import com.vayunmathur.notes.data.Note
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class IntentsAndRouteTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val repo get() = seedNotesDb(context)

    @BeforeTest fun purge() = runBlocking { repo.purge(); repo.deleteAllSnapshots() }

    private class CapturingReceiver : ResultReceiver(Handler(Looper.getMainLooper())) {
        val latch = CountDownLatch(1)
        @Volatile var code: Int = Int.MIN_VALUE
        @Volatile var data: Bundle? = null
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            code = resultCode; data = resultData; latch.countDown()
        }
    }

    private fun awaitReceiver(r: CapturingReceiver) {
        val deadline = System.currentTimeMillis() + 8000
        while (!r.latch.await(50, TimeUnit.MILLISECONDS)) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(System.currentTimeMillis() < deadline, "receiver never called, code=${r.code}")
        }
    }

    @Test fun insertIntentAddsNoteAndGetIntentListsNotes() {
        val insertReceiver = CapturingReceiver()
        val insertIntent = Intent(context, com.vayunmathur.notes.intents.InsertIntent::class.java).apply {
            putExtra("DATA", Json.encodeToString(NoteData.serializer(), NoteData("From Assistant", "assistant body")))
            putExtra("RECEIVER", insertReceiver)
        }
        Robolectric.buildActivity(com.vayunmathur.notes.intents.InsertIntent::class.java, insertIntent).create()
        awaitReceiver(insertReceiver)
        assertEquals(Activity.RESULT_OK, insertReceiver.code)
        assertEquals(listOf("From Assistant"), runBlocking { repo.getAll() }.map { it.title })

        val getReceiver = CapturingReceiver()
        val getIntent = Intent(context, com.vayunmathur.notes.intents.GetIntent::class.java).apply {
            putExtra("DATA", Json.encodeToString(Unit.serializer(), Unit))
            putExtra("RECEIVER", getReceiver)
        }
        Robolectric.buildActivity(com.vayunmathur.notes.intents.GetIntent::class.java, getIntent).create()
        awaitReceiver(getReceiver)
        assertEquals(Activity.RESULT_OK, getReceiver.code)
        val response = getReceiver.data!!.getString("RESPONSE_DATA")!!
        val notes = Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(NoteData.serializer()), response)
        assertEquals(listOf(NoteData("From Assistant", "assistant body")), notes)
    }

    @Test fun getIntentWithMissingDataCancels() {
        val receiver = CapturingReceiver()
        val intent = Intent(context, com.vayunmathur.notes.intents.GetIntent::class.java).apply {
            putExtra("RECEIVER", receiver)
        }
        Robolectric.buildActivity(com.vayunmathur.notes.intents.GetIntent::class.java, intent).create()
        awaitReceiver(receiver)
        assertEquals(Activity.RESULT_CANCELED, receiver.code)
    }

    @Test fun routesHoldValues() {
        assertEquals(Route.NotesList, Route.NotesList)
        assertEquals(42L, Route.Note(42).id)
        assertEquals(Route.Note(42), Route.Note(42))
        assertEquals("content://x/a.md", Route.ExternalNote("content://x/a.md").uri)
        assertNotNull(Route.Note.serializer())
    }

    @Test fun backupAgentProperties() {
        val agent = com.vayunmathur.notes.platform.AppBackupAgent()
        assertNotNull(agent.dbCodec)
        // The agent getters use the agent itself as the Context, and an
        // unattached agent has no base context, so exercise the delegates
        // directly with the app context: the note_images dir must travel
        // with the database backup, or a restore renders embedded images
        // blank.
        assertEquals(
            listOf(java.io.File(context.filesDir, "note_images").absolutePath),
            com.vayunmathur.notes.platform.NoteImageStore.backupFiles(context).map { it.absolutePath },
        )
        FakeAndroidKeyStore.install()
        runCatching { com.vayunmathur.library.util.DatabaseHelper(context).storePassphrase("test-passphrase") }
        // AppBackupAgent.dbConfigs delegates to noteDbConfigs with the agent as
        // context; an unattached agent has no base context, so exercise the
        // delegate directly with the app context.
        val configs = com.vayunmathur.notes.data.noteDbConfigs(context)
        assertEquals(1, configs.size)
        assertEquals(com.vayunmathur.notes.data.DB_NAME, configs.single().first)
        assertTrue(configs.single().second.isNotEmpty())
    }

    @Test fun routeSerializationRoundTrips() {
        val json = Json { }
        val list = Json.decodeFromString(Route.serializer(), json.encodeToString(Route.serializer(), Route.NotesList))
        assertEquals(Route.NotesList, list)
        val note = Json.decodeFromString(Route.serializer(), json.encodeToString(Route.serializer(), Route.Note(77)))
        assertEquals(Route.Note(77), note)
        val ext = Json.decodeFromString(Route.serializer(), json.encodeToString(Route.serializer(), Route.ExternalNote("content://a/b.md")))
        assertEquals(Route.ExternalNote("content://a/b.md"), ext)
    }
}
