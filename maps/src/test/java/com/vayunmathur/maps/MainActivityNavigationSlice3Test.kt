package com.vayunmathur.maps

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 3/3 (root package files M-Z): the [Navigation] hierarchy from
 * `MainActivity.kt` (the [Route] destinations and their entries).
 *
 * The map surface degrades without the native renderer (see `MapNative`:
 * unavailable renders the background colour), so the full hierarchy —
 * map list pane plus settings / saved-places detail entries — composes
 * under Robolectric and the overflow + settings-row actions drive the
 * shared back stack exactly as on device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityNavigationSlice3Test {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun settingsLabel(): String = context.getString(com.vayunmathur.library.ui.R.string.settings)

    private fun savedPlacesTitle(): String = context.getString(R.string.saved_places_title)

    @Test
    fun `navigation starts on the map with a settings overflow`() {
        composeRule.setContent {
            Navigation()
        }
        // The map's top bar carries the settings overflow action.
        composeRule.onNodeWithContentDescription(settingsLabel()).assertIsDisplayed()
    }

    @Test
    fun `settings overflow opens the settings page`() {
        composeRule.setContent {
            Navigation()
        }
        composeRule.onNodeWithContentDescription(settingsLabel()).performClick()
        // The settings entry renders its display section.
        composeRule.onNodeWithText(context.getString(R.string.settings_section_display)).assertIsDisplayed()
    }

    @Test
    fun `settings page links through to saved places`() {
        composeRule.setContent {
            Navigation()
        }
        composeRule.onNodeWithContentDescription(settingsLabel()).performClick()
        // The places section sits below the fold: scroll the row into view first
        // (clicking an off-screen node silently no-ops).
        composeRule.onNodeWithText(savedPlacesTitle()).performScrollTo()
        composeRule.onNodeWithText(savedPlacesTitle()).performClick()
        // The saved-places entry renders its (empty) saved section. (The title
        // text also exists on the settings row behind the detail pane, so pin
        // the destination by its unique empty-list copy.)
        composeRule.onNodeWithText(context.getString(R.string.saved_list_empty)).assertIsDisplayed()
    }

    @Test
    fun `route objects are distinct nav keys`() {
        assertEquals(3, setOf(Route.MapPage, Route.SettingsPage, Route.SavedPlacesPage).size)
    }

    // --- Activity Content() (same file): download gate, permission gate,
    // offline banner, Navigation host ---

    private fun initWorkManager() {
        runCatching {
            androidx.work.WorkManager.initialize(
                context,
                androidx.work.Configuration.Builder()
                    .setMinimumLoggingLevel(android.util.Log.DEBUG)
                    .setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())
                    .build(),
            )
        }
    }

    /**
     * Launch the real activity with the download gate satisfied (a basemap
     * archive on disk) and runtime permissions granted, then dump the
     * activity's semantics tree. The app must reach the map: the download
     * screen, the permission wall and the offline banner stay hidden, and the
     * map chrome (search, settings overflow) is displayed.
     */
    private fun activitySemanticsTexts(): List<String> {
        initWorkManager()
        context.getExternalFilesDir(null)!!.resolve("basemap.mamaps").writeBytes(ByteArray(64))
        val shadowApp = org.robolectric.Shadows.shadowOf(context as android.app.Application)
        shadowApp.grantPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            "android.permission.POST_NOTIFICATIONS",
        )
        val intent = android.content.Intent(context, MainActivity::class.java)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        try {
            val decor = controller.get().window!!.decorView as android.view.ViewGroup
            fun findOwner(v: android.view.View): androidx.compose.ui.platform.ViewRootForTest? {
                (v as? androidx.compose.ui.platform.ViewRootForTest)?.let { return it }
                if (v is android.view.ViewGroup) {
                    for (i in 0 until v.childCount) {
                        findOwner(v.getChildAt(i))?.let { return it }
                    }
                }
                return null
            }
            val root = findOwner(decor) ?: return emptyList()
            val out = mutableListOf<String>()
            fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
                val texts = node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
                texts.mapTo(out) { it.text }
                val cds = node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { emptyList() }
                cds.mapTo(out) { it }
                for (child in node.children) walk(child)
            }
            walk(root.semanticsOwner.rootSemanticsNode)
            return out
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `activity content passes the download gate with an archive on disk`() {
        val texts = activitySemanticsTexts()
        assertNotNull(texts)
        // The download screen never appears: its label is absent from the tree.
        kotlin.test.assertFalse(
            texts.any { it.contains(context.getString(R.string.downloading_basemap)) },
            "download screen shown despite the archive: $texts",
        )
    }

    @Test
    fun `activity content passes the permission gate when grants are held`() {
        val texts = activitySemanticsTexts()
        // The permission wall never appears: its title is absent from the tree.
        kotlin.test.assertFalse(
            texts.any { it == context.getString(com.vayunmathur.library.ui.R.string.permission_gate_title) },
            "permission wall shown despite grants: $texts",
        )
    }

    @Test
    fun `activity content reaches the map with search and settings`() {
        val texts = activitySemanticsTexts()
        // The Navigation host renders the map chrome inside Content(): the
        // search bar and the settings overflow land in the semantics tree
        // (overflow icon via its content description).
        kotlin.test.assertTrue(
            texts.any { it.contains("Search") },
            "map search missing from activity content: $texts",
        )
        kotlin.test.assertTrue(
            texts.any { it == context.getString(com.vayunmathur.library.ui.R.string.settings) },
            "settings overflow missing from activity content: $texts",
        )
    }
}
