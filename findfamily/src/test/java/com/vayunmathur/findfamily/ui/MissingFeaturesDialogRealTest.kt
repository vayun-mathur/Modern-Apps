package com.vayunmathur.findfamily.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.MissingFeaturesDialog
import com.vayunmathur.findfamily.Route
import com.vayunmathur.library.util.NavBackStack
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals

/** The missing-features dialog explains the gap and dismisses via the back stack. */
@RunWith(AndroidJUnit4::class)
class MissingFeaturesDialogRealTest {

    @get:Rule val rule = createComposeRule()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val ctx: Context get() = app

    @Test fun dialogDismissesThroughBackStack() {
        val stack = NavBackStack<Route>(arrayOf(Route.MainPage, Route.AddLinkDialog))
        rule.setContent { MissingFeaturesDialog(stack) }
        rule.onNodeWithText("Required Features Disabled").assertIsDisplayed()
        rule.onNodeWithText("OK").performClick()
        assertEquals(Route.MainPage, stack.last())
    }
}
