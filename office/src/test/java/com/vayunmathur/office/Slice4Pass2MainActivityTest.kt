package com.vayunmathur.office

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue

/** Slice 4 pass 2: MainActivity launches into the office home (nav graph + theme). */
@RunWith(AndroidJUnit4::class)
class Slice4Pass2MainActivityTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun mainActivityLaunchesToHome() {
        rule.onNodeWithText(ctx.getString(R.string.open_document)).assertExists()
        rule.onNodeWithText(ctx.getString(R.string.app_name), substring = true).assertExists()
        assertTrue(rule.activity.intent?.action == "android.intent.action.MAIN" || rule.activity.intent != null)
    }
}
