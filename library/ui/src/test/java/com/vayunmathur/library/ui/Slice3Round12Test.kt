package com.vayunmathur.library.ui

import android.text.format.DateFormat
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Slice 3/3 round 12: DateString remaining forms + sphere GL lifecycle. */
@RunWith(RobolectricTestRunner::class)
class Slice3Round12Test {

    @get:Rule val rule = createComposeRule()

    private val friJan3 = LocalDate(2025, 1, 3) // a Friday

    // ---- DateString ----

    @Test fun dateWeekdayIncludesWeekdayDayMonthYear() {
        assertEquals("Fri, Jan 3, 2025", DateString.dateWeekday(friJan3, Locale.US))
        assertEquals("Fri, 3 Jan 2025", DateString.dateWeekday(friJan3, Locale.UK))
    }

    @Test fun dateWeekdayNoYearDropsYear() {
        assertEquals("Fri, Jan 3", DateString.dateWeekdayNoYear(friJan3, Locale.US))
        assertEquals("Fri, 3 Jan", DateString.dateWeekdayNoYear(friJan3, Locale.UK))
    }

    @Test fun dateMonthDayUsesFullMonthName() {
        assertEquals("January 3", DateString.dateMonthDay(friJan3, Locale.US))
        assertEquals("3 January", DateString.dateMonthDay(friJan3, Locale.UK))
        assertEquals("Jan 3", DateString.monthDayShort(friJan3, Locale.US))
        assertEquals("3 Jan", DateString.monthDayShort(friJan3, Locale.UK))
    }

    @Test fun dateWithOptionalYearSwitchesForm() {
        assertEquals(
            DateString.dateLong(friJan3, Locale.US),
            DateString.dateWithOptionalYear(friJan3, includeYear = true, Locale.US),
        )
        assertEquals(
            "January 3",
            DateString.dateWithOptionalYear(friJan3, includeYear = false, Locale.US),
        )
    }

    @Test fun dateFieldOrderFollowsLocale() {
        assertEquals(
            listOf(
                DateString.DateField.MONTH,
                DateString.DateField.DAY,
                DateString.DateField.YEAR,
            ),
            DateString.dateFieldOrder(Locale.US),
        )
        assertEquals(
            listOf(
                DateString.DateField.DAY,
                DateString.DateField.MONTH,
                DateString.DateField.YEAR,
            ),
            DateString.dateFieldOrder(Locale.UK),
        )
        assertEquals(
            listOf(
                DateString.DateField.YEAR,
                DateString.DateField.MONTH,
                DateString.DateField.DAY,
            ),
            DateString.dateFieldOrder(Locale.JAPAN),
        )
    }

    @Test fun timeWithMarkerHonoursHourCycle() {
        assertEquals("3:05 PM", DateString.time(LocalTime(15, 5), is24Hour = false, Locale.US))
        assertEquals("15:05", DateString.time(LocalTime(15, 5), is24Hour = true, Locale.US))
        assertEquals("12:00 AM", DateString.time(LocalTime(0, 0), is24Hour = false, Locale.US))
        assertEquals("12:00 PM", DateString.time(LocalTime(12, 0), is24Hour = false, Locale.US))
        assertEquals("12:30 AM", DateString.time(LocalTime(0, 30), is24Hour = false, Locale.US))
    }

    @Test fun timeSecondsWithMarker() {
        assertEquals(
            "3:05:23 PM",
            DateString.timeSeconds(LocalTime(15, 5, 23), is24Hour = false, Locale.US),
        )
        assertEquals(
            "15:05:23",
            DateString.timeSeconds(LocalTime(15, 5, 23), is24Hour = true, Locale.US),
        )
    }

    @Test fun hourLabelsForAxis() {
        assertEquals("3 PM", DateString.hourLabel(LocalTime(15, 0), is24Hour = false, Locale.US))
        assertEquals("15:00", DateString.hourLabel(LocalTime(15, 0), is24Hour = true, Locale.US))
        assertEquals("12 AM", DateString.hourLabel(0, is24Hour = false, Locale.US))
        assertEquals("12 PM", DateString.hourLabel(12, is24Hour = false, Locale.US))
        assertEquals("09:00", DateString.hourLabel(9, is24Hour = true, Locale.US))
        assertEquals("23:00", DateString.hourLabel(23, is24Hour = true, Locale.US))
    }

    @Test fun instantOverloadsConvertInZone() {
        // 2025-01-03T15:05:00Z
        val instant = Instant.fromEpochSeconds(1_735_916_700L)
        val utc = TimeZone.UTC
        assertEquals("1/3/25", DateString.dateShort(instant, utc, Locale.US))
        assertEquals("January 3, 2025", DateString.dateLong(instant, utc, Locale.US))
        assertEquals("3:05 PM", DateString.time(instant, is24Hour = false, utc, Locale.US))
        assertEquals(
            "1/3/25 3:05 PM",
            DateString.dateTime(instant, is24Hour = false, utc, Locale.US),
        )
        // Sydney in January is UTC+11 (DST): 15:05Z is 02:05 the next day there.
        assertEquals(
            "2:05 AM",
            DateString.time(
                instant,
                is24Hour = false,
                TimeZone.of("Australia/Sydney"),
                Locale.US,
            ),
        )
    }

    @Test fun rememberIs24HourReadsSystemSetting() {
        var observed: Boolean? = null
        rule.setContent {
            observed = rememberIs24Hour()
            Text("clock-host")
        }
        rule.onNodeWithText("clock-host").assertIsDisplayed()
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        assertEquals(DateFormat.is24HourFormat(context), observed)
        assertEquals(DateFormat.is24HourFormat(context), is24Hour(context))
    }

    // ---- ListPage interactions ----

    private data class LPItem(override val id: Long, val name: String) :
        com.vayunmathur.library.util.DatabaseItem

    private object LPHome : com.vayunmathur.library.util.NavKey
    private object LPEdit : com.vayunmathur.library.util.NavKey
    private object LPSettings : com.vayunmathur.library.util.NavKey
    private data class LPView(val id: Long) : com.vayunmathur.library.util.NavKey

    private fun setListPage(
        stack: com.vayunmathur.library.util.NavBackStack<com.vayunmathur.library.util.NavKey>,
        data: List<LPItem>,
        searchEnabled: Boolean = false,
        sortOrder: Comparator<LPItem>? = null,
        withChrome: Boolean = false,
    ) {
        rule.setContent {
            ListPage<LPItem, com.vayunmathur.library.util.NavKey, LPEdit>(
                backStack = stack,
                data = data,
                title = "Things",
                headlineContent = { Text(it.name) },
                supportingContent = { Text("sub-${it.name}") },
                viewPage = { LPView(it) },
                scrollBehavior = appBarScrollBehavior(),
                editPage = if (withChrome) ({ LPEdit }) else null,
                settingsPage = if (withChrome) LPSettings else null,
                otherActions = { if (withChrome) Text("extra-action") },
                bottomBar = { if (withChrome) Text("bottom-bar") },
                fab = if (withChrome) ({ Text("custom-fab") }) else null,
                searchEnabled = searchEnabled,
                sortOrder = sortOrder,
                searchString = { it.name },
            )
        }
        rule.waitForIdle()
    }

    @Test fun listSearchFiltersBySearchString() {
        val stack = com.vayunmathur.library.util.NavBackStack(
            arrayOf<com.vayunmathur.library.util.NavKey>(LPHome),
        )
        setListPage(
            stack,
            listOf(LPItem(1, "Alpha"), LPItem(2, "Beta"), LPItem(3, "Alpine")),
            searchEnabled = true,
        )
        rule.onNode(androidx.compose.ui.test.hasSetTextAction())
            .performTextInput("alp")
        rule.waitForIdle()
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithText("Alpine").assertIsDisplayed()
        rule.onNodeWithText("Beta").assertDoesNotExist()

        rule.onNode(androidx.compose.ui.test.hasSetTextAction())
            .performTextReplacement("")
        rule.waitForIdle()
        rule.onNodeWithText("Beta").assertIsDisplayed()
    }

    @Test fun listSortsWithComparator() {
        val stack = com.vayunmathur.library.util.NavBackStack(
            arrayOf<com.vayunmathur.library.util.NavKey>(LPHome),
        )
        setListPage(
            stack,
            listOf(LPItem(1, "Charlie"), LPItem(2, "Alpha"), LPItem(3, "Beta")),
            sortOrder = compareBy { it.name },
        )
        val matcher = androidx.compose.ui.test.hasText("Alpha") or
            androidx.compose.ui.test.hasText("Beta") or
            androidx.compose.ui.test.hasText("Charlie")
        val texts = rule.onAllNodes(matcher).fetchSemanticsNodes().map { node ->
            // ListItem merges headline + supporting into one semantics node;
            // the headline is its first text element.
            node.config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                .first().text
        }
        assertEquals(listOf("Alpha", "Beta", "Charlie"), texts)
    }

    @Test fun listItemClickNavigatesToViewPage() {
        val stack = com.vayunmathur.library.util.NavBackStack(
            arrayOf<com.vayunmathur.library.util.NavKey>(LPHome),
        )
        setListPage(stack, listOf(LPItem(7, "Alpha"), LPItem(8, "Beta")))
        rule.onNodeWithText("Beta").performClick()
        rule.waitForIdle()
        assertEquals(listOf(LPHome, LPView(8)), stack.backStack)
    }

    @Test fun listChromeActionsNavigate() {
        val stack = com.vayunmathur.library.util.NavBackStack(
            arrayOf<com.vayunmathur.library.util.NavKey>(LPHome),
        )
        setListPage(stack, listOf(LPItem(1, "Alpha")), withChrome = true)
        rule.onNodeWithText("extra-action").assertIsDisplayed()
        rule.onNodeWithText("bottom-bar").assertIsDisplayed()
        rule.onNodeWithText("custom-fab").assertIsDisplayed()

        rule.onNodeWithContentDescription("Settings").performClick()
        rule.waitForIdle()
        assertEquals(LPSettings, stack.backStack.last())

        rule.onNodeWithContentDescription("Add").performClick()
        rule.waitForIdle()
        assertEquals(listOf(LPHome, LPSettings, LPEdit), stack.backStack)
    }

    // ---- SphereRenderer GL lifecycle (Robolectric GL shadows) ----

    @Test fun sphereRendererSurfaceLifecycleWithNullTexture() {
        val renderer = SphereRenderer(PanoramaCrop.full(800, 400), null) { null }
        renderer.onSurfaceCreated(null, null)
        renderer.onSurfaceChanged(null, 640, 480)
        renderer.onSurfaceChanged(null, 640, 0) // height 0 -> aspect stays safe
        renderer.onDrawFrame(null)
        // Look direction is untouched by surface events.
        assertEquals((-kotlin.math.PI).toFloat(), renderer.yaw, 1e-5f)
        assertEquals(0f, renderer.pitch, 1e-5f)
        assertTrue(renderer.fov == PanoramaCameraState.DEFAULT_FOV_DEG)
    }

    @Test fun sphereRendererSurfaceLifecycleWithBitmapTexture() {
        val bitmap = android.graphics.Bitmap.createBitmap(4, 2, android.graphics.Bitmap.Config.ARGB_8888)
        var requestedMax = -1
        val renderer = SphereRenderer(PanoramaCrop.full(800, 400), null) { max ->
            requestedMax = max
            bitmap
        }
        renderer.onSurfaceCreated(null, null)
        renderer.onSurfaceChanged(null, 320, 240)
        renderer.onDrawFrame(null)
        // The loader is asked with at least the 2048 floor for max texture size.
        assertTrue(requestedMax >= 2048, "max texture size floor, was $requestedMax")
    }
}
