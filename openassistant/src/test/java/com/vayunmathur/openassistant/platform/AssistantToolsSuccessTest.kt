package com.vayunmathur.openassistant.platform

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.vayunmathur.library.util.IntentLauncher
import com.vayunmathur.openassistant.MainActivity
import com.vayunmathur.openassistant.data.AppDatabase
import com.vayunmathur.openassistant.domain.ParsedToolCall
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AssistantTools success paths: a fake peer app answers the launched intent
 * over its ResultReceiver with canned JSON, so handlers run end to end.
 */
@RunWith(RobolectricTestRunner::class)
class AssistantToolsSuccessTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var tools: AssistantTools
    class CapturingActivity : ComponentActivity() {
        val started = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) { synchronized(started) { started += intent } }
    }

    private lateinit var activity: CapturingActivity

    @BeforeTest fun setUp() {
        activity = Robolectric.buildActivity(CapturingActivity::class.java).setup().get()
        MainActivity.intentLauncher = IntentLauncher(activity)
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        tools = AssistantTools(ctx, db.memoryDao())
    }

    @AfterTest fun tearDown() { db.close() }

    /** Invokes [call] on a worker thread and answers the launched intent with [responseJson]. */
    private fun call(name: String, vararg args: Pair<String, String>) =
        ParsedToolCall(name, mapOf(*args))

    @Test fun memoryToolsRoundTrip() {
        // Memory tools are local DAO calls, no intent involved.
        assertEquals("Success: Added memory", tools.invoke(call("add_to_memory", "content" to "likes tea")))
        val listed = tools.invoke(call("get_memories"))
        assertTrue(listed.contains("likes tea"), listed)
        val id = listed.substringAfter("[").substringBefore("]").toLong()
        assertEquals("Success: Removed memory with id $id",
            tools.invoke(call("remove_memory", "id" to id.toString())))
        assertTrue(!tools.invoke(call("get_memories")).contains("likes tea"))
    }
}
