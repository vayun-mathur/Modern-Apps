package com.vayunmathur.openassistant

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.vayunmathur.openassistant.util.AssistantDeviceTools
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MainActivityAndDeviceToolsTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun routesSerializeAndCompare() {
        val json = Json
        val page = Route.ConversationPage(42L)
        val text = json.encodeToString(Route.serializer(), page)
        assertEquals(page, json.decodeFromString(Route.serializer(), text))
        assertEquals(Route.SettingsPage, json.decodeFromString(Route.serializer(), json.encodeToString(Route.serializer(), Route.SettingsPage)))
        assertEquals(42L, page.id)
    }

    @Test fun mainActivityLaunchesAndReadsAssistContext() {
        com.vayunmathur.openassistant.platform.FakeAndroidKeyStore.install()
        // create() only: resuming would compose the download gate, which
        // needs WorkManager (not initialized under Robolectric).
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        assertNotNull(activity)
        assertNotNull(MainActivity.intentLauncher)
        val intent = Intent(ctx, MainActivity::class.java).putExtra("assist_screen_text", "Notes showing groceries")
        controller.newIntent(intent)
        assertEquals("Notes showing groceries", activity.intent.getStringExtra("assist_screen_text"))
        val blank = Intent(ctx, MainActivity::class.java).putExtra("assist_screen_text", "  ")
        controller.newIntent(blank)
        controller.destroy()
    }

    @Test fun deviceToolsLocalBehavior() {
        val tools = AssistantDeviceTools(ctx)
        val dateTime = tools.getLocalCurrentDateTime()
        assertTrue(dateTime.contains("epochMillis="), dateTime)
        assertEquals("Conversation title set successfully", tools.setConversationTitle("My Title"))
        com.vayunmathur.openassistant.util.InferenceService.newTitle = null
        var missing: String? = null
        val opened = tools.openApp("com.nonexistent.app.xyz") { pkg -> missing = pkg; "missing:$pkg" }
        assertEquals("missing:com.nonexistent.app.xyz", opened)
        assertEquals("com.nonexistent.app.xyz", missing)
        assertTrue(tools.sendMessage("555", "hello").isNotBlank())
        assertTrue(tools.makePhoneCall("555").isNotBlank())
        assertTrue(tools.getAppList().isNotBlank())
    }
}
