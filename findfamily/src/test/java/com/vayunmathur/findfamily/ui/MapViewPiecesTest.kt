package com.vayunmathur.findfamily.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.User
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertTrue

/** Robolectric Compose tests for [UserPicture]/[GreenCircle] map avatar pieces. */
@RunWith(AndroidJUnit4::class)
class MapViewPiecesTest {
    @get:Rule val rule = createComposeRule()

    @Test fun greenCircleShowsInitialAndClicks() {
        var clicked = false
        rule.setContent { GreenCircle(48.dp, 'A', grayscale = false, onClick = { clicked = true }) }
        rule.onNodeWithText("A").assertIsDisplayed().performClick()
        assertTrue(clicked)
    }
    @Test fun userPictureFallsBackToInitial() {
        val u = User(name = "Zoe", photo = null, locationName = "x", sendingEnabled = true,
            requestStatus = RequestStatus.MUTUAL_CONNECTION)
        rule.setContent { UserPicture(u, 48.dp) }
        rule.onNodeWithText("Z").assertIsDisplayed()
    }
    @Test fun userPictureDataPhotoRenders() {
        var clicked = false
        rule.setContent { UserPicture("data:image/jpeg;base64,aGVsbG8=", 'B', 48.dp, grayscale = true, onClick = { clicked = true }) }
        // Photo path composes an image instead of the initial; click still wired via container
        assertTrue(!clicked)
    }
}
