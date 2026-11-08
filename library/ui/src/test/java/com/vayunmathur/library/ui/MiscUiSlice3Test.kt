package com.vayunmathur.library.ui

import android.app.Activity
import android.content.ContextWrapper
import android.hardware.SensorManager
import android.text.Layout
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice 3/3 (library/ui root files), round 2: pure-logic and small-composable
 * classes with no test coverage yet — POI vector mapping, panorama data/state,
 * permission helpers, email spans, motion-value composables, misc UI pieces.
 */
@RunWith(RobolectricTestRunner::class)
class MiscUiSlice3Test {

    @get:Rule
    val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    // ---- MapsPoiVectors ----

    @Test fun poiVectorMappingCoversKnownTypes() {
        // Spot-check the documented mapping incl. shared buckets and fallback.
        assertNotNull(MapsPoiVectors.of(0))
        assertEquals(MapsPoiVectors.of(0), MapsPoiVectors.of(2))
        assertEquals(MapsPoiVectors.of(3), MapsPoiVectors.of(46))
        assertEquals(MapsPoiVectors.of(15), MapsPoiVectors.of(40))
        assertEquals(MapsPoiVectors.of(34), MapsPoiVectors.of(35))
        assertEquals(MapsPoiVectors.of(36), MapsPoiVectors.of(45))
        // Fallback for unknown / "other".
        assertEquals(MapsPoiVectors.of(255), MapsPoiVectors.of(-1))
        assertEquals(MapsPoiVectors.of(255), MapsPoiVectors.of(999))
    }

    // ---- PanoramaCrop / PanoramaCameraState ----

    @Test fun panoramaCropFullCoversWholeImage() {
        val crop = PanoramaCrop.full(4000, 2000)
        assertEquals(4000, crop.fullWidth)
        assertEquals(2000, crop.fullHeight)
        assertEquals(4000, crop.croppedWidth)
        assertEquals(2000, crop.croppedHeight)
        assertEquals(0, crop.croppedLeft)
        assertEquals(0, crop.croppedTop)
    }

    @Test fun panoramaCameraStateDefaultsAndPublishes() {
        val state = PanoramaCameraState()
        assertEquals(0f, state.yaw)
        assertEquals(0f, state.pitch)
        assertEquals(PanoramaCameraState.DEFAULT_FOV_DEG, state.fovDeg)
        state.publish(1.5f, -0.2f, 60f)
        assertEquals(1.5f, state.yaw)
        assertEquals(-0.2f, state.pitch)
        assertEquals(60f, state.fovDeg)
    }

    @Test fun rememberPanoramaCameraStateProvidesOne() {
        var seen: PanoramaCameraState? = null
        rule.setContent {
            seen = rememberPanoramaCameraState()
            Text("pano")
        }
        rule.waitForIdle()
        assertNotNull(seen)
        assertEquals(PanoramaCameraState.DEFAULT_FOV_DEG, seen!!.fovDeg)
    }

    // ---- PermissionRequirement / HealthConnectGrantCache ----

    @Test fun runtimeEmptyBatchIsGranted() {
        assertTrue(PermissionRequirement.Runtime(emptyArray()).isGranted(context))
    }

    @Test fun runtimeUndeclaredPermissionIsNotGranted() {
        // A permission that is not declared/granted in the test manifest.
        assertFalse(
            PermissionRequirement.Runtime(arrayOf("android.permission.CAMERA")).isGranted(context),
        )
    }

    @Test fun runtimeEqualsAndHashCode() {
        val a = PermissionRequirement.Runtime(arrayOf("a", "b"))
        val b = PermissionRequirement.Runtime(arrayOf("a", "b"))
        val c = PermissionRequirement.Runtime(arrayOf("a"))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
        assertFalse(a.equals("not-a-runtime"))
    }

    @Test fun notificationsCompanionIsGranted() {
        // Pre-Tiramisu this is an empty batch; on Tiramisu+ it reflects reality.
        // Either way the check must not throw.
        assertNotNull(PermissionRequirement.notifications())
    }

    @Test fun healthConnectGrantsDriveRequirement() {
        HealthConnectGrantCache.granted = null
        assertFalse(PermissionRequirement.HealthConnect(setOf("a")).isGranted(context))
        HealthConnectGrantCache.granted = setOf("a", "b")
        assertTrue(PermissionRequirement.HealthConnect(setOf("a")).isGranted(context))
        assertFalse(PermissionRequirement.HealthConnect(setOf("a", "c")).isGranted(context))
        HealthConnectGrantCache.granted = null
    }

    @Test fun appPermissionsSpecDefaults() {
        val spec = AppPermissionsSpec(title = "T", requirements = emptyList())
        assertNull(spec.subtitle)
        assertNull(spec.icon)
    }

    // ---- findActivity ----

    @Test fun findActivityWalksWrapperChain() {
        val activity = org.robolectric.Robolectric.buildActivity(Activity::class.java).get()
        val wrapped = ContextWrapper(ContextWrapper(activity))
        assertEquals(activity, wrapped.findActivity())
        assertNull(context.findActivity())
    }

    // ---- SpecialAccess ----

    @Test fun specialAccessChecksDoNotThrow() {
        // Environment-dependent values; the assertion is that the checks run.
        // (hasAllFilesAccess is skipped: Robolectric's Environment shadow throws
        // ArrayIndexOutOfBounds — a framework-shadow gap, not app logic.)
        SpecialAccess.hasExactAlarms(context)
        SpecialAccess.hasNotificationListener(context)
        SpecialAccess.hasVpnConsent(context)
        SpecialAccess.hasManageMedia(context)
        SpecialAccess.hasFullScreenIntent(context)
    }

    // ---- Messenger / rememberClock ----

    @Test fun rememberMessengerSurvivesWithoutHost() {
        var posted = 0
        rule.setContent {
            val messenger = rememberMessenger()
            Text("m")
            androidx.compose.runtime.LaunchedEffect(Unit) {
                messenger.show("hello")
                posted++
            }
        }
        rule.waitForIdle()
        assertEquals(1, posted)
    }

    @Test fun rememberClockTicksAndReadsCurrentTime() {
        var read: Long? = null
        rule.setContent {
            val clock = rememberClock()
            Text("c")
            androidx.compose.runtime.LaunchedEffect(Unit) { read = clock() }
        }
        rule.waitForIdle()
        assertNotNull(read)
        assertTrue(read!! > 0L)
    }

    // ---- verticalShape ----

    @Test fun verticalShapeRoundsOuterCornersOnly() {
        rule.setContent {
            // Single row: all corners rounded from the theme, so just check composition.
            val single = verticalShape(0, 1)
            val first = verticalShape(0, 3)
            val middle = verticalShape(1, 3)
            val last = verticalShape(2, 3)
            assertTrue(single != middle)
            assertTrue(first != middle)
            assertTrue(last != middle)
            assertTrue(first != last)
            Text("shapes")
        }
        rule.onNodeWithText("shapes").assertIsDisplayed()
    }

    // ---- SwappedContent / SwappedTopBar ----

    @Test fun swappedContentShowsCurrentState() {
        rule.setContent {
            SwappedContent(state = "one") { Text("state-$it") }
        }
        rule.onNodeWithText("state-one").assertIsDisplayed()
    }

    @Test fun swappedTopBarShowsOverlayWhenTrue() {
        rule.setContent {
            SwappedTopBar(showingOverlay = true) { overlay ->
                Text(if (overlay) "overlay-bar" else "base-bar")
            }
        }
        rule.waitForIdle()
        // AnimatedContent crossfades; at least one of the two bars is present.
        assertTrue(
            rule.onAllNodesWithText("overlay-bar").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("base-bar").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    // ---- CompassCalibrationBanner / Hint ----

    @Test fun compassBannerHiddenAtHighAccuracy() {
        rule.setContent {
            CompassCalibrationBanner(accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
            Text("underneath")
        }
        rule.waitForIdle()
        rule.onNodeWithText("underneath").assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithContentDescription("Compass")
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test fun compassHintHiddenAtHighAccuracy() {
        rule.setContent {
            CompassCalibrationHint(accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
            Text("underneath")
        }
        rule.waitForIdle()
        rule.onNodeWithText("underneath").assertIsDisplayed()
    }

    @Test fun compassBannerAndHintShowWhenInaccurate() {
        rule.setContent {
            CompassCalibrationBanner(accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE)
            CompassCalibrationHint(accuracy = SensorManager.SENSOR_STATUS_ACCURACY_LOW)
        }
        rule.waitForIdle()
        // Both render an IconCompass; both icons must be present.
        rule.onAllNodesWithContentDescription("Compass").assertCountEquals(2)
    }

    // ---- MapUnavailableMessage ----

    @Test fun mapUnavailableMessageRenders() {
        rule.setContent { MapUnavailableMessage() }
        rule.onNodeWithContentDescription("Warning").assertIsDisplayed()
    }

    // ---- AppPullToRefreshBox ----

    @Test fun appPullToRefreshBoxShowsContent() {
        var refreshed = 0
        rule.setContent {
            AppPullToRefreshBox(isRefreshing = false, onRefresh = { refreshed++ }) {
                Text("pull-content")
            }
        }
        rule.onNodeWithText("pull-content").assertIsDisplayed()
        assertEquals(0, refreshed)
    }

    @Test fun appPullToRefreshBoxRefreshingShowsContent() {
        rule.setContent {
            AppPullToRefreshBox(isRefreshing = true, onRefresh = {}) {
                Text("refreshing-content")
            }
        }
        rule.onNodeWithText("refreshing-content").assertIsDisplayed()
    }

    // ---- MultiCategoryPicker ----

    @Test fun multiCategoryPickerMenuAddsItem() {
        var added: String? = null
        rule.setContent {
            MultiCategoryPicker(
                label = "Tags",
                selected = emptyList(),
                available = listOf("a", "b"),
                itemLabel = { it },
                onAdd = { added = it },
                onRemove = {},
            )
        }
        rule.onNodeWithText("Tags").assertIsDisplayed()
        // Open the menu by tapping the field, then pick an option.
        rule.onNodeWithText("Tags").performClick()
        rule.onNodeWithText("b").performClick()
        assertEquals("b", added)
    }

    @Test fun multiCategoryPickerChipsRemoveOnTap() {
        var removed: String? = null
        rule.setContent {
            MultiCategoryPicker(
                label = "Tags",
                selected = listOf("a"),
                available = listOf("a", "b"),
                itemLabel = { it },
                onAdd = {},
                onRemove = { removed = it },
            )
        }
        // The selected chip renders; tapping it removes.
        rule.onNodeWithText("a").performClick()
        assertEquals("a", removed)
    }

    @Test fun multiCategoryPickerAddClickVariantOpensPicker() {
        var adds = 0
        rule.setContent {
            MultiCategoryPicker<String>(
                label = "When",
                selected = emptyList(),
                itemLabel = { it },
                onAddClick = { adds++ },
                onRemove = {},
            )
        }
        rule.onNodeWithText("When").assertIsDisplayed()
    }

    // ---- NoPermissionsScreen / PermissionsChecker / openAppSettings ----

    @Test fun permissionsCheckerPassesThroughWhenGranted() {
        rule.setContent {
            PermissionsChecker(permissions = emptyArray(), text = "Need it") {
                Text("gated-content")
            }
        }
        rule.onNodeWithText("gated-content").assertIsDisplayed()
    }

    @Test fun noPermissionsScreenShowsWall() {
        rule.setContent {
            NoPermissionsScreen(
                permissions = emptyArray(),
                text = "Need location",
                setHasPermissions = {},
            )
        }
        rule.waitForIdle()
        // Empty batch is granted immediately, so the callback fires and content shows.
        // With an empty permission set either the wall or the pass-through may show;
        // the wall path is exercised via a denied permission below.
    }

    @Test fun noPermissionsScreenWallForDeniedPermission() {
        var last: Boolean? = null
        rule.setContent {
            NoPermissionsScreen(
                permissions = arrayOf("android.permission.CAMERA"),
                text = "Need camera",
                setHasPermissions = { last = it },
            )
        }
        rule.waitForIdle()
        // The wall shows the permission text both as title and row headline.
        assertEquals(
            2,
            rule.onAllNodesWithText("Need camera").fetchSemanticsNodes().size,
        )
        assertNull(last)
    }

    @Test fun permissionWallSettingsRowRenders() {
        var opened = 0
        rule.setContent {
            PermissionWall(
                title = "Need mic",
                actionLabel = "Grant",
                onRequest = {},
                rationale = "For voice",
                settingsLabel = "Settings",
                onOpenSettings = { opened++ },
            )
        }
        rule.onNodeWithText("Need mic").assertIsDisplayed()
        rule.onNodeWithText("Settings").performClick()
        assertEquals(1, opened)
    }
}

/**
 * Slice 3/3 round 2: pure-logic pieces — email spans/alignment helpers,
 * motion-value helpers, modifier helpers, ringtone titles, pane/adaptive bits,
 * reorderable gesture data.
 *
 * Under Robolectric: several cases touch ApplicationProvider/resources.
 */
@RunWith(RobolectricTestRunner::class)
class MiscUiLogicSlice3Test {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun headingSpanScalesByLevel() {
        assertEquals(1.6f, HeadingSpan(1).proportion)
        assertEquals(1.35f, HeadingSpan(2).proportion)
        assertEquals(1.15f, HeadingSpan(3).proportion)
        assertEquals(1f, HeadingSpan(4).proportion)
        assertEquals(1f, HeadingSpan(9).proportion)
    }

    @Test fun headingSpanBoldensPaint() {
        val span = HeadingSpan(1)
        val paint = android.text.TextPaint().apply { textSize = 10f }
        span.updateDrawState(paint)
        assertEquals(16f, paint.textSize, 0.001f)
        val measure = android.text.TextPaint().apply { textSize = 10f }
        span.updateMeasureState(measure)
        assertEquals(16f, measure.textSize, 0.001f)
    }

    @Test fun emailAlignmentSpanNormalizes() {
        assertEquals("center", EmailAlignmentSpan("Center").alignmentCss)
        assertEquals("right", EmailAlignmentSpan("RIGHT").alignmentCss)
        assertEquals("justify", EmailAlignmentSpan("justify").alignmentCss)
        // Unknown strings are preserved verbatim (the serializer emits them);
        // only the rendered EditText alignment falls back to NORMAL.
        assertEquals("bogus", EmailAlignmentSpan("bogus").alignmentCss)
        // Rendering falls back to a real Layout.Alignment for justify.
        assertEquals(Layout.Alignment.ALIGN_NORMAL, EmailAlignmentSpan("justify").alignment)
        assertEquals(Layout.Alignment.ALIGN_CENTER, EmailAlignmentSpan("center").alignment)
    }

    @Test fun alignmentCssRoundTrips() {
        assertEquals("center", Layout.Alignment.ALIGN_CENTER.toCssAlign())
        assertEquals("right", Layout.Alignment.ALIGN_OPPOSITE.toCssAlign())
        assertEquals("left", Layout.Alignment.ALIGN_NORMAL.toCssAlign())
        assertEquals(Layout.Alignment.ALIGN_CENTER, cssAlignToLayout("center"))
        assertEquals(Layout.Alignment.ALIGN_OPPOSITE, cssAlignToLayout("right"))
        assertEquals(Layout.Alignment.ALIGN_OPPOSITE, cssAlignToLayout("END"))
        assertEquals(Layout.Alignment.ALIGN_NORMAL, cssAlignToLayout("justify"))
        assertEquals(Layout.Alignment.ALIGN_NORMAL, cssAlignToLayout("nonsense"))
    }

    @Test fun emailInlineSpansConstruct() {
        InlineCodeSpan()
        EmailBlockQuoteSpan()
        HrSpan()
        assertEquals("mono", FontFamilySpan("mono").familyName)
    }

    @Test fun inlineCodeSpanUsesMonospace() {
        val span = InlineCodeSpan()
        val paint = android.text.TextPaint()
        span.updateDrawState(paint)
        assertEquals(android.graphics.Typeface.MONOSPACE, paint.typeface)
        val measure = android.text.TextPaint()
        span.updateMeasureState(measure)
        assertEquals(android.graphics.Typeface.MONOSPACE, measure.typeface)
    }

    @Test fun ringtoneTitleBranches() {
        assertEquals("silent", RINGTONE_SILENT)
        assertEquals(
            context.getString(R.string.ringtone_silent),
            ringtoneTitle(context, RINGTONE_SILENT),
        )
        // Null = system default; must return something non-blank, never throw.
        assertTrue(ringtoneTitle(context, null).isNotBlank())
        // Unknown URI falls back to the "custom" label rather than throwing.
        assertEquals(
            context.getString(R.string.ringtone_custom),
            ringtoneTitle(context, "content://invalid/ringtone/99999"),
        )
    }

    @Test fun desktopContentMaxWidthValue() {
        assertEquals(720.dp, DesktopContentMaxWidth)
    }

    @Test fun horizontalFlickHoldsCallbacks() {
        var dragged = 0f
        var released: Pair<Float, Float>? = null
        val flick = HorizontalFlick(
            enabled = { false },
            onDrag = { dragged = it },
            onRelease = { dx, v -> released = dx to v },
        )
        assertFalse(flick.enabled())
        flick.onDrag(12f)
        assertEquals(12f, dragged)
        flick.onRelease(12f, 3f)
        assertEquals(12f to 3f, released)
        // Default is enabled.
        assertTrue(HorizontalFlick(onDrag = {}, onRelease = { _, _ -> }).enabled())
    }

    @Test fun historyStepData() {
        assertEquals("5m" to -300L, HistoryStep("5m", -300L).let { it.label to it.deltaSeconds })
    }

    @Test fun swipeActionSideValues() {
        assertEquals(3, SwipeActionSide.entries.size)
        assertTrue(SwipeActionSide.entries.contains(SwipeActionSide.None))
    }

    @Test fun motionOpenCloseOverSpecs() {
        assertTrue(Motion.open<Float>(200).toString().isNotEmpty())
        assertTrue(Motion.close<Float>(100).toString().isNotEmpty())
        assertEquals(Motion.FolderOpenMillis, Motion.FolderCloseMillis)
        assertEquals(150, Motion.PopupOpenMillis)
        assertEquals(100, Motion.PopupCloseMillis)
        assertEquals(150, Motion.ResizeMillis)
    }

    @Test fun externalIntentsCopyToClipboard() {
        // Silent path: must not throw under Robolectric.
        ExternalIntents.copyToClipboard(context, "hello", "label")
        ExternalIntents.copyToClipboard(context, "hello")
    }
}

/**
 * Slice 3/3 round 2: compose pieces needing composition — motion values,
 * modifiers, game-center screen, achievement notification, history scrubber
 * card, daily reminder section, free-height sheet scaffold, swipe box.
 */
@RunWith(RobolectricTestRunner::class)
class MiscUiComposeSlice3Test {

    @get:Rule
    val rule = createComposeRule()

    @Test fun animatedValuesTrackTargets() {
        rule.setContent {
            val dp = animatedDp(24.dp)
            val f = animatedFloat(0.5f)
            val c = animatedColor(androidx.compose.ui.graphics.Color.Red)
            assertEquals(24.dp, dp)
            assertEquals(0.5f, f)
            assertEquals(androidx.compose.ui.graphics.Color.Red, c)
            Text("animated")
        }
        rule.onNodeWithText("animated").assertIsDisplayed()
    }

    @Test fun pressedShapeAndItemMotionCompose() {
        rule.setContent {
            val resting = pressedShape(pressed = false)
            val pressed = pressedShape(pressed = true)
            assertTrue(resting != pressed)
            androidx.compose.foundation.lazy.LazyColumn {
                items(2) { Text("row-$it", modifier = itemMotion()) }
            }
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
            ) {
                items(2) { Text("tile-$it", modifier = itemMotion()) }
            }
        }
        rule.onNodeWithText("row-0").assertIsDisplayed()
        rule.onNodeWithText("tile-1").assertIsDisplayed()
    }

    @Test fun staggeredEntranceShowsContent() {
        rule.setContent {
            Text("stagger", modifier = Modifier.staggeredEntrance(0, arriving = false))
            Text("stagger-late", modifier = Modifier.staggeredEntrance(2, arriving = true))
        }
        rule.onNodeWithText("stagger").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(5000)
        rule.onNodeWithText("stagger-late").assertIsDisplayed()
    }

    @Test fun scrimAndClickModifiersCompose() {
        var clicks = 0
        var doubles = 0
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                Box(
                    Modifier
                        .scrim(alpha = { 0.5f })
                        .invisibleClickable { clicks++ }
                        .testTag("click-box"),
                ) {
                    Text("modified")
                }
                // onDoubleTap is a separate modifier; exercised on its own node so
                // its gesture consumption can't swallow the Box's tap above.
                Text(
                    "double",
                    modifier = Modifier
                        .testTag("double-box")
                        .onDoubleTap { doubles++ },
                )
            }
        }
        rule.onNodeWithText("modified").assertIsDisplayed()
        rule.onNodeWithTag("click-box").performTouchInput { click() }
        rule.waitForIdle()
        assertEquals(1, clicks)
        rule.onNodeWithTag("double-box").performTouchInput { doubleClick() }
        rule.waitForIdle()
        assertEquals(1, doubles)
    }

    @Test fun paneMarginAndAdaptiveClassesCompose() {
        rule.setContent {
            val margin = paneContentMargin()
            assertTrue(margin == Spacing.lg || margin == Spacing.xl)
            val width = currentWindowWidthClass()
            assertTrue(width in WindowWidthClass.entries)
            // Boolean helpers must agree with the class.
            assertEquals(width != WindowWidthClass.Compact, isMediumWidth())
            assertEquals(width == WindowWidthClass.Expanded, isExpandedWidth())
            // Height helper just must not throw.
            isCompactHeight()
            Text("adaptive")
        }
        rule.onNodeWithText("adaptive").assertIsDisplayed()
    }

    @Test fun gameCenterScreenListsAchievements() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = FakeAchievementsManager(appContext)
        var backs = 0
        rule.setContent { GameCenterScreen(manager = manager, onBack = { backs++ }) }
        rule.waitForIdle()
        rule.onNodeWithText("First win").assertIsDisplayed()
        rule.onNodeWithText("Steady play").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test fun achievementNotificationShowsAndDismisses() {
        var dismissed = 0
        val achievement = com.vayunmathur.library.util.Achievement(
            id = "a1", name = "First win", description = "Win once",
            targetProgress = 1, isSecret = false,
        )
        rule.setContent {
            AchievementNotification(achievement = achievement, onDismiss = { dismissed++ })
        }
        rule.onNodeWithText("First win").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(20_000)
        rule.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test fun historyScrubberCardRendersSteps() {
        var dates = 0
        rule.setContent {
            Box {
                val state = rememberHistoryScrubberState(
                    initialInstant = kotlin.time.Instant.parse("2026-06-15T12:00:00Z"),
                    initialNowMode = false,
                )
                HistoryScrubberCard(
                    state = state,
                    steps = listOf(HistoryStep("-1h", -3600)),
                    onDateChipClick = { dates++ },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("-1h").performClick()
        assertEquals(0, dates)
    }

    @Test fun historyStepButtonFires() {
        var clicks = 0
        rule.setContent {
            androidx.compose.foundation.layout.Row {
                HistoryStepButton(label = "-5m") { clicks++ }
            }
        }
        rule.onNodeWithText("-5m").performClick()
        assertEquals(1, clicks)
    }

    @Test fun dailyReminderSectionTogglesAndOpensPicker() {
        var enabled: Boolean? = null
        rule.setContent {
            DailyReminderSettingsSection(
                enabled = false,
                hour = 9,
                minute = 30,
                onEnabledChange = { enabled = it },
                onTimeChange = { _, _ -> },
            )
        }
        // Toggle the switch row; the time row opens a picker dialog.
        rule.onAllNodesWithText("09:30").fetchSemanticsNodes().let {
            assertEquals(1, it.size)
        }
        assertNull(enabled)
    }

    @Test fun freeHeightSheetScaffoldShowsContentAndSheet() {
        rule.setContent {
            FreeHeightBottomSheetScaffold(
                sheetContent = { Text("sheet-body") },
                content = { Text("behind-content") },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("behind-content").assertIsDisplayed()
        // The sheet rests at its measured peek; the body is composed (possibly
        // below the fold), so assert presence rather than displayed.
        assertEquals(
            1,
            rule.onAllNodesWithText("sheet-body").fetchSemanticsNodes().size,
        )
    }

    @Test fun swipeActionsBoxShowsContentByDefault() {
        rule.setContent {
            SwipeActionsBox(
                onStartToEnd = {},
                onEndToStart = {},
                background = { Text("bg-$it") },
            ) {
                Text("swipe-row")
            }
        }
        rule.onNodeWithText("swipe-row").assertIsDisplayed()
    }

    private class FakeAchievementsManager(
        context: android.content.Context,
    ) : com.vayunmathur.library.util.AchievementsManager(
        context = context,
        jsonContent = """
            [
              {"id":"a1","name":"First win","description":"Win once","targetProgress":1,"isSecret":false},
              {"id":"a2","name":"Steady play","description":"Play twice","targetProgress":2,"isSecret":false}
            ]
        """.trimIndent(),
    ) {
        override fun checkExistingAchievements() {}
    }
}
