package com.vayunmathur.clock.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.MaterialTheme
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.clock.wear.data.WearClockPersisted
import com.vayunmathur.clock.wear.domain.ArmedAlarmRecord
import com.vayunmathur.clock.wear.domain.WearClockAlarm
import com.vayunmathur.clock.wear.domain.WearClockSnapshot

/** Round watch, 454x454px class (227dp at 320dpi) — Pixel Watch / Galaxy Watch size. */
private const val ROUND = "spec:width=227dp,height=227dp,dpi=320"

/** Small square watch, 336px class. */
private const val SQUARE = "spec:width=192dp,height=192dp,dpi=280"

/** Fixed preview instant: 2026-11-25 14:41 UTC. Times render from this, never the wall clock. */
private const val FIXED_NOW_MILLIS = 1795617660000L

/**
 * Store listing images for `:clock:wear`, rendered from Compose previews instead of
 * from an instrumented test on a device.
 *
 * `./gradlew :clock:wear:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/clock-wear/wear/` — the wear module's own bucket, not the
 * phone Clock app's folder — where the release build picks them up.
 *
 * Rules (same as every other module's MetadataPreviews): order comes from the
 * function names, each preview needs `@PreviewTest` plus `group = "wear"`, previews
 * are class members, and everything is a literal fixture — the instant is fixed so
 * the clock faces never drift. Layoutlib renders a rectangular canvas, so round
 * previews clip to a circle: what is visible is what a round watch shows.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-home-round", device = ROUND, group = "wear")
    @Composable
    fun Preview1HomeRound() {
        RoundPreview { WearHomeContent(nowMillis = FIXED_NOW_MILLIS, onOpen = {}) }
    }

    @PreviewTest
    @Preview(name = "2-alarms-round", device = ROUND, group = "wear")
    @Composable
    fun Preview2AlarmsRound() {
        RoundPreview {
            WearAlarmsContent(
                persisted = WearClockPersisted(
                    snapshot = WearClockSnapshot(
                        alarms = listOf(
                            WearClockAlarm(alarmId = 1, label = "Wake up", hour = 7, minute = 0, daysMask = 0b0111110, enabled = true),
                            WearClockAlarm(alarmId = 2, label = "Gym", hour = 17, minute = 30, daysMask = 0, enabled = true),
                            WearClockAlarm(alarmId = 3, label = "Weekend", hour = 9, minute = 15, daysMask = 0b1000001, enabled = false),
                        ),
                    ),
                    armedRecords = listOf(
                        ArmedAlarmRecord(alarmId = 1, nextTriggerMillis = FIXED_NOW_MILLIS + 3_600_000L, exact = true),
                        ArmedAlarmRecord(alarmId = 2, nextTriggerMillis = FIXED_NOW_MILLIS + 7_200_000L, exact = true),
                    ),
                ),
                nowMillis = FIXED_NOW_MILLIS,
                onRequestSync = {},
                onBack = {},
                onOpenAlarm = {},
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-home-square", device = SQUARE, group = "wear")
    @Composable
    fun Preview3HomeSquare() {
        MaterialTheme {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
                WearHomeContent(nowMillis = FIXED_NOW_MILLIS, onOpen = {})
            }
        }
    }
}

/** Round-watch framing: wear screens are round-safe by design; clip so the render is too. */
@Composable
private fun RoundPreview(content: @Composable () -> Unit) {
    MaterialTheme {
        Box(
            Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colors.background),
        ) { content() }
    }
}
