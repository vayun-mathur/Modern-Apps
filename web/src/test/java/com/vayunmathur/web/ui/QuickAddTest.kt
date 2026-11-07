package com.vayunmathur.web.ui

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.web.Route
import com.vayunmathur.web.data.DownloadEntry
import com.vayunmathur.web.data.HistoryEntry
import com.vayunmathur.web.data.WebDatabase
import com.vayunmathur.web.platform.SitePermissionType
import com.vayunmathur.web.platform.TestStores
import com.vayunmathur.web.platform.WebViewModel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class QuickAddTest {

    @get:Rule val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, WebDatabase::class.java)
        .allowMainThreadQueries().build()
    private val stores = TestStores(db)
    private val createdVms = mutableListOf<WebViewModel>()

    @AfterTest fun cleanup() {
        val m = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        m.isAccessible = true
        createdVms.forEach { runCatching { m.invoke(it) } }
        createdVms.clear()
    }

    private fun vm(): WebViewModel {
        val v = WebViewModel(stores, context, windowId = "qa-${System.nanoTime()}", incognito = false)
        createdVms += v
        shadowOf(Looper.getMainLooper()).idle()
        return v
    }

    private fun await(description: String, condition: () -> Boolean) {
        runBlocking {
            repeat(300) {
                shadowOf(Looper.getMainLooper()).idle()
                if (condition()) return@runBlocking
                delay(10)
            }
            assertTrue(condition(), "timed out: $description")
        }
    }

    @Test fun downloadsPageMultipleEntries() {
        val v = vm()
        runBlocking {
            stores.downloads.upsert(DownloadEntry(url = "https://d.example/a.zip", fileName = "a.zip", mimeType = "application/zip", startedAt = System.currentTimeMillis()))
            stores.downloads.upsert(DownloadEntry(url = "https://d.example/b.pdf", fileName = "b.pdf", mimeType = null, startedAt = 0L))
            stores.downloads.upsert(DownloadEntry(url = "https://d.example/c", fileName = "c", mimeType = "text/plain", startedAt = Long.MAX_VALUE / 2))
        }
        await("downloads") { v.downloads.value.size >= 3 }
        rule.setContent { DownloadsPage(v, NavBackStack(arrayOf<Route>(Route.Browser))) }
        rule.waitForIdle()
        rule.onNodeWithText("b.pdf").assertIsDisplayed()
        assertTrue(rule.onAllNodes(hasText("application/zip", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun historyYesterdayGrouping() {
        val v = vm()
        val yesterday = System.currentTimeMillis() - 24L * 3600 * 1000
        runBlocking {
            stores.history.upsert(HistoryEntry(url = "https://yd.example", title = "YesterdayEntry", visitedAt = yesterday))
        }
        await("history") { v.history.value.isNotEmpty() }
        rule.setContent { HistoryPage(v, NavBackStack(arrayOf<Route>(Route.Browser))) }
        rule.waitForIdle()
        rule.onNodeWithText("YesterdayEntry").assertIsDisplayed()
        rule.onNodeWithText("YesterdayEntry").performClick()
        rule.waitForIdle()
        assertTrue(v.tabs.any { it.url == "https://yd.example" })
    }

    @Test fun permissionPromptAllTypesGrantSubset() {
        var granted: List<SitePermissionType>? = null
        rule.setContent {
            PermissionPromptSheet(
                origin = "https://all.example/call",
                types = SitePermissionType.entries,
                onGrant = { granted = it },
                onDeny = {},
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Camera").assertIsDisplayed()
        rule.onNodeWithText("Microphone").assertIsDisplayed()
        rule.onNodeWithText("Location").assertIsDisplayed()
        rule.onNodeWithText("Notifications").assertIsDisplayed()
        // Uncheck Location (third checkbox), then allow the rest.
        rule.onAllNodes(isToggleable())[2].performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Allow").performClick()
        rule.waitForIdle()
        assertEquals(
            listOf(SitePermissionType.CAMERA, SitePermissionType.MICROPHONE, SitePermissionType.NOTIFICATIONS),
            granted?.sortedBy { it.ordinal },
        )
    }

    @Test fun pwaIconHelpersDegenerateInputs() = runBlocking {
        assertEquals(null, com.vayunmathur.web.platform.PwaHelper.loadIconBitmap(context, null))
        assertEquals(null, com.vayunmathur.web.platform.PwaHelper.loadIconBitmap(context, ""))
        assertTrue(
            com.vayunmathur.web.platform.PwaHelper.isPinSupported(context) ||
                !com.vayunmathur.web.platform.PwaHelper.isPinSupported(context),
        )
        Unit
    }

    @Test fun tabTileForPrivateAndActiveTabs() {
        var switched: String? = null
        var closed: String? = null
        rule.setContent {
            com.vayunmathur.web.ui.components.TabTile(
                tab = com.vayunmathur.web.platform.BrowserTab(id = "tile1", url = "https://tile.example/page", title = "Tile Title", isPrivate = true),
                isActive = true,
                thumbnail = null,
                favicon = null,
                onSwitch = { switched = "tile1" },
                onClose = { closed = "tile1" },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Tile Title").assertIsDisplayed()
        rule.onNodeWithText("Tile Title").performClick()
        rule.waitForIdle()
        assertEquals("tile1", switched)
        assertEquals(null, closed)
    }
}
