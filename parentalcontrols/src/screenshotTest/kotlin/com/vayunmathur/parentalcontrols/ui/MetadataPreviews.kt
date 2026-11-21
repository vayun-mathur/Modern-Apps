package com.vayunmathur.parentalcontrols.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.parentalcontrols.data.AppRule
import com.vayunmathur.parentalcontrols.data.BedtimeSchedule
import com.vayunmathur.parentalcontrols.data.DowntimeSchedule
import com.vayunmathur.parentalcontrols.domain.TimeWindow
import com.vayunmathur.parentalcontrols.platform.Enforcer
import com.vayunmathur.parentalcontrols.platform.SupervisableApp
import com.vayunmathur.parentalcontrols.platform.SupervisionUiState

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store-listing images for `:parentalcontrols`, rendered from Compose previews.
 *
 * Enforcement itself is system-mediated (supervision APIs) and cannot run here, so literal
 * state is the only reproducible source — which is exactly why the screens are stateless.
 * `./gradlew :parentalcontrols:metadata` renders these into
 * `metadata_data/photos/parentalcontrols/`.
 *
 * Each preview must carry @PreviewTest as well as @Preview and be a member of a class
 * (not a top-level function) or the screenshot engine silently skips it.
 */
class MetadataPreviews {

    private val apps = listOf(
        SupervisableApp(
            packageName = "com.example.galaxyrunner",
            label = "Galaxy Runner",
            rule = AppRule(
                packageName = "com.example.galaxyrunner",
                dailyLimitMinutes = 60,
            ),
        ),
        SupervisableApp(
            packageName = "com.example.videotube",
            label = "Video Tube",
            rule = AppRule(
                packageName = "com.example.videotube",
                blockedAtBedtime = true,
            ),
        ),
        SupervisableApp(
            packageName = "com.example.mathmentor",
            label = "Math Mentor",
            rule = AppRule(
                packageName = "com.example.mathmentor",
                allowedInDowntime = true,
            ),
        ),
    )

    private val dashboardState = SupervisionUiState(
        controlsEnabled = true,
        schedule = BedtimeSchedule(
            enabled = true,
            startMinute = 21 * 60,
            endMinute = 7 * 60,
        ),
        downtime = DowntimeSchedule(
            enabled = true,
            startMinute = 12 * 60,
            endMinute = 14 * 60,
        ),
        dailyLimitMinutes = 180,
        apps = apps,
        loading = false,
    )

    private val noActions = DashboardActions(
        onSetControls = {},
        onOpenDailyLimit = {},
        onOpenAppLimits = {},
        onOpenBedtime = {},
        onOpenDowntime = {},
        onOpenSchoolTime = {},
        onOpenWebFilters = {},
        onOpenPin = {},
    )

    @PreviewTest
    @Preview(name = "1-dashboard", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview01Dashboard() {
        DynamicTheme(darkTheme = true) {
            DashboardScreen(state = dashboardState, actions = noActions)
        }
    }

    @PreviewTest
    @Preview(name = "2-app-limits", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview02AppLimits() {
        DynamicTheme(darkTheme = true) {
            AppLimitsScreen(
                state = dashboardState,
                actions = AppLimitsActions(onLimitChange = { _, _ -> }),
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-bedtime", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview03Bedtime() {
        DynamicTheme(darkTheme = true) {
            BedtimeScreen(
                state = dashboardState,
                actions = BedtimeActions(
                    onEnabledChange = {},
                    onStartChange = { _, _ -> },
                    onEndChange = { _, _ -> },
                    onToggleDay = {},
                    onAppBedtimeChange = { _, _ -> },
                ),
            )
        }
    }

    @PreviewTest
    @Preview(name = "4-daily-limit", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview04DailyLimit() {
        DynamicTheme(darkTheme = true) {
            DailyLimitScreen(
                currentMinutes = 180,
                actions = DailyLimitActions(onLimitChange = {}),
            )
        }
    }

    @PreviewTest
    @Preview(name = "5-downtime", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview05Downtime() {
        DynamicTheme(darkTheme = true) {
            WindowScheduleScreen(
                title = "Downtime",
                enableLabel = "Use a downtime schedule",
                enableHint = "Everything except the apps you approve below stays closed during the hours you set.",
                startLabel = "Starts at",
                endLabel = "Ends at",
                daysLabel = "Days",
                appsLabel = "Approved apps",
                dayInitials = "M,T,W,T,F,S,S",
                enabled = true,
                startMinute = 12 * 60,
                endMinute = 14 * 60,
                daysMask = TimeWindow.ALL_DAYS,
                apps = apps,
                loading = false,
                actions = WindowScheduleActions(
                    onEnabledChange = {},
                    onStartChange = { _, _ -> },
                    onEndChange = { _, _ -> },
                    onToggleDay = {},
                    onAppAllowChange = { _, _ -> },
                ),
            )
        }
    }

    @PreviewTest
    @Preview(name = "6-school-time", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview06SchoolTime() {
        DynamicTheme(darkTheme = true) {
            WindowScheduleScreen(
                title = "School time",
                enableLabel = "Use a school time schedule",
                enableHint = "Only the learning apps you approve below can be opened during school hours.",
                startLabel = "Starts at",
                endLabel = "Ends at",
                daysLabel = "Days",
                appsLabel = "Learning apps",
                dayInitials = "M,T,W,T,F,S,S",
                enabled = true,
                startMinute = 8 * 60,
                endMinute = 15 * 60,
                daysMask = TimeWindow.WEEKDAYS,
                apps = apps,
                loading = false,
                actions = WindowScheduleActions(
                    onEnabledChange = {},
                    onStartChange = { _, _ -> },
                    onEndChange = { _, _ -> },
                    onToggleDay = {},
                    onAppAllowChange = { _, _ -> },
                ),
            )
        }
    }

    @PreviewTest
    @Preview(name = "7-lock-bedtime", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview07LockBedtime() {
        DynamicTheme(darkTheme = true) {
            LockScreen(
                reason = Enforcer.BlockReason.Bedtime,
                appLabel = "Video Tube",
                actions = LockActions(onAskParent = {}, onClose = {}),
            )
        }
    }

    @PreviewTest
    @Preview(name = "8-pin-gate-error", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview08PinGateError() {
        DynamicTheme(darkTheme = true) {
            PinGateScreen(
                error = "Wrong PIN. 2 attempts left.",
                lockedOut = false,
                actions = PinGateActions(
                    onVerify = { false },
                    onCancel = {},
                ),
            )
        }
    }

    @PreviewTest
    @Preview(name = "9-pin-setup", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview09PinSetup() {
        DynamicTheme(darkTheme = true) {
            PinSetupScreen(
                actions = PinSetupActions(
                    onSet = { false },
                    onCancel = {},
                ),
            )
        }
    }

    @PreviewTest
    @Preview(name = "10-web-filters", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview10WebFilters() {
        DynamicTheme(darkTheme = true) {
            WebContentFiltersScreen(enabled = true, onEnabledChange = {})
        }
    }
}
