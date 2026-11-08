package com.vayunmathur.library.ui

import android.content.Intent
import android.media.RingtoneManager
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.Achievement
import com.vayunmathur.library.util.AchievementStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice 3/3: game center + achievement notification, permission wall screens,
 * ringtone helpers.
 */
@RunWith(RobolectricTestRunner::class)
class GameCenterSliceTest {

    @get:Rule
    val rule = createComposeRule()

    private fun achievement(
        id: String = "a1",
        name: String = "First win",
        secret: Boolean = false,
        target: Int = 1,
    ) = Achievement(id = id, name = name, description = "Win once", targetProgress = target, isSecret = secret)

    @Test fun achievementItemUnlockedShowsName() {
        val status = AchievementStatus(achievement(), progress = 1, isUnlocked = true)
        rule.setContent { AchievementItem(status) }
        rule.onNodeWithText("First win").assertIsDisplayed()
        rule.onNodeWithText("Win once").assertIsDisplayed()
    }

    @Test fun achievementItemLockedSecretHidesName() {
        val status = AchievementStatus(achievement(secret = true), progress = 0, isUnlocked = false)
        rule.setContent { AchievementItem(status) }
        rule.onNodeWithText("???").assertIsDisplayed()
    }

    @Test fun achievementItemLockedNonSecretShowsName() {
        val status = AchievementStatus(achievement(), progress = 0, isUnlocked = false)
        rule.setContent { AchievementItem(status) }
        rule.onNodeWithText("First win").assertIsDisplayed()
    }

    @Test fun achievementItemProgressShownForMultiTarget() {
        val status = AchievementStatus(
            achievement(target = 10),
            progress = 4,
            isUnlocked = false,
        )
        rule.setContent { AchievementItem(status) }
        rule.onNodeWithText("First win").assertIsDisplayed()
    }

    @Test fun permissionWallShowsTitleAndRequests() {
        var requests = 0
        rule.setContent {
            PermissionWall(
                title = "Need mic",
                actionLabel = "Grant",
                onRequest = { requests++ },
                rationale = "For voice",
            )
        }
        rule.onNodeWithText("Need mic").assertIsDisplayed()
        rule.onNodeWithText("For voice").assertIsDisplayed()
        rule.onNodeWithText("Grant").performClick()
        assertEquals(1, requests)
    }

    @Test fun permissionWallWithoutRationaleShowsTitle() {
        rule.setContent {
            PermissionWall(title = "Need cam", actionLabel = "OK", onRequest = {})
        }
        rule.onNodeWithText("Need cam").assertIsDisplayed()
    }
}

/**
 * Slice 3/3: ringtone intent/result helpers (pure JVM under Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
class RingtonePickerSliceTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun silentConstantValue() {
        assertEquals("silent", RINGTONE_SILENT)
    }

    @Test fun pickerIntentCarriesTypeAndTitle() {
        val intent = ringtonePickerIntent(
            existing = null,
            type = RingtoneManager.TYPE_ALARM,
            title = "Pick",
        )
        assertEquals(RingtoneManager.ACTION_RINGTONE_PICKER, intent.action)
        assertEquals(
            RingtoneManager.TYPE_ALARM,
            intent.getIntExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, -1),
        )
        assertEquals("Pick", intent.getStringExtra(RingtoneManager.EXTRA_RINGTONE_TITLE))
    }

    @Test fun pickerIntentSilentExistingOmitsUri() {
        val intent = ringtonePickerIntent(
            existing = RINGTONE_SILENT,
            type = RingtoneManager.TYPE_RINGTONE,
            title = "Pick",
        )
        // "Silent" maps to a null existing URI (no pre-selection in the picker).
        assertEquals(
            null,
            intent.getParcelableExtra<android.net.Uri>(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
            ),
        )
    }

    @Test fun pickerResultNullDataMeansSilent() {
        assertEquals(RINGTONE_SILENT, ringtonePickerResult(null))
        assertEquals(RINGTONE_SILENT, ringtonePickerResult(Intent()))
    }

    @Test fun silentUriStringTitlesSilent() {
        assertEquals(
            context.getString(R.string.ringtone_silent),
            ringtoneTitle(context, RINGTONE_SILENT),
        )
    }
}
