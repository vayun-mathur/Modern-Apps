package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice 3/3 (continued): icon batches 6+ and lane/navigation glyphs. Split
 * from [IconsSliceTest] to keep each test class under detekt's LargeClass
 * threshold; together they render every shared `IconX()` composable.
 */
@RunWith(RobolectricTestRunner::class)
class IconsSlicePart2Test {

    @get:Rule
    val rule = createComposeRule()

    @Test fun iconBatch6RendersAllGlyphs() {
        rule.setContent {
            Column {
                IconPartlyCloudyDay()
                IconPartlyCloudyNight()
                IconPaste()
                IconPause()
                IconPerson()
                IconPersonAdd()
                IconPhotoLibrary()
                IconPlay()
                IconPlayCircle()
                IconPoll()
                IconPolyline()
                IconPregnancy()
                IconPressure()
                IconProvider()
                IconQrCode()
                IconRain()
                IconRampLeft()
                IconRampRight()
                IconReceipt()
                IconRedact()
                IconRedo()
                IconRefresh()
                IconRemove()
                IconRemoveCircle()
                IconRepeat()
                IconRepeatOne()
                IconRestartAlt()
                IconRestaurant()
                IconRestore()
                IconReturn()
            }
        }
        rule.onAllNodesWithContentDescription("Add contact").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Partly cloudy").assertCountEquals(2)
        rule.onAllNodesWithContentDescription("Paste").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Pause").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Person").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Photos").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Play").assertCountEquals(2)
        rule.onAllNodesWithContentDescription("Poll").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Polyline").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Pregnancy").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Pressure").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Provider").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("QR code").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Rain").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Ramp left").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Ramp right").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Receipt").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Redact").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Redo").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Refresh").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Remove").assertCountEquals(2)
        rule.onAllNodesWithContentDescription("Repeat").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Repeat one").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Restart").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Restaurant").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Restore").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Return").assertCountEquals(1)
    }

    @Test fun iconBatch7RendersAllGlyphs() {
        rule.setContent {
            Column {
                IconRotateLeft()
                IconRotateRight()
                IconRoundaboutLeft()
                IconRoundaboutRight()
                IconRuler()
                IconSave()
                IconSchedule()
                IconScience()
                IconScreenShare()
                IconSearch()
                IconSelect()
                IconSend()
                IconSettings()
                IconShapeArrowFill()
                IconShapeArrowOutline()
                IconShapeDiamondFill()
                IconShapeDiamondOutline()
                IconShapeHexagonFill()
                IconShapeHexagonOutline()
                IconShapeOvalFill()
                IconShapeOvalOutline()
                IconShapePentagonFill()
                IconShapePentagonOutline()
                IconShapeRectFill()
                IconShapeRectOutline()
                IconShapeRoundRectFill()
                IconShapeRoundRectOutline()
                IconShapeStarFill()
                IconShapeStarOutline()
                IconShapeTriangleFill()
            }
        }
        rule.onAllNodesWithContentDescription("Arrow").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Arrow outline").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Diamond").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Diamond outline").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Hexagon").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Hexagon outline").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Lab result").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Oval").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Oval outline").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Pentagon").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Pentagon outline").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Rectangle").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Rectangle outline").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Rotate Left").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Rotate Right").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Roundabout").assertCountEquals(2)
        rule.onAllNodesWithContentDescription("Rounded rectangle").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Rounded rectangle outline").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Ruler").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Save").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Schedule").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Search").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Select").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Send").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Settings").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Share screen").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Star").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Star outline").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Triangle").assertCountEquals(1)
    }

    @Test fun iconBatch8RendersAllGlyphs() {
        rule.setContent {
            Column {
                IconShapeTriangleOutline()
                IconShare()
                IconShield()
                IconShift()
                IconShoppingCart()
                IconShuffle()
                IconSignalFull()
                IconSignalNone()
                IconSignature()
                IconSim()
                IconSkipNext()
                IconSkipPrevious()
                IconSmoking()
                IconSms()
                IconSnooze()
                IconSnow()
                IconSort()
                IconSpaceBar()
                IconSpeak()
                IconSpeaker()
                IconSportsEsports()
                IconSquiggly()
                IconStar()
                IconStarBorder()
                IconStop()
                IconStore()
                IconStraight()
                IconStrikethrough()
                IconStyle()
                IconSubscriptions()
            }
        }
        rule.onAllNodesWithContentDescription("Cart").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Games").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Message").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Next").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("No signal").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Previous").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("SIM").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Share").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Shields").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Shift").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Shuffle").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Signal").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Signature").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Smoking").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Snooze").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Snow").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Sort").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Space").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Speak").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Speaker").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Squiggly").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Star").assertCountEquals(2)
        rule.onAllNodesWithContentDescription("Stop").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Store").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Straight").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Strikeout").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Style").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Subscriptions").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Triangle outline").assertCountEquals(1)
    }

    @Test fun iconBatch9RendersAllGlyphs() {
        rule.setContent {
            Column {
                IconSunny()
                IconSwapLanguages()
                IconSwipe()
                IconTerminal()
                IconTextTool()
                IconThumbDown()
                IconThumbUp()
                IconThunder()
                IconTimer()
                IconToday()
                IconTranslate()
                IconTune()
                IconTurnLeft()
                IconTurnRight()
                IconTurnSharpLeft()
                IconTurnSharpRight()
                IconTurnSlightLeft()
                IconTurnSlightRight()
                IconTv()
                IconUTurn()
                IconUTurnRight()
                IconUnarchive()
                IconUndo()
                IconUnfoldLess()
                IconUnfoldMore()
                IconUninstall()
                IconUpload()
                IconVaccine()
                IconVerify()
                IconVideoCamera()
            }
        }
        rule.onAllNodesWithContentDescription("Adjustments").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Decrease spacing").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Dislike").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Increase spacing").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Like").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Sharp left").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Sharp right").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Slight left").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Slight right").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Sunny").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Swap languages").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Swipe").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("TV").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Terminal").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Text").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Thunderstorm").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Timer").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Today").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Translate").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Turn left").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Turn right").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("U-turn").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("U-turn right").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Unarchive").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Undo").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Uninstall").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Upload").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Vaccine").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Verify security code").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Video").assertCountEquals(1)
    }

    @Test fun iconBatch10RendersAllGlyphs() {
        rule.setContent {
            Column {
                IconVisibilityOff()
                IconVisible()
                IconVoicemail()
                IconVolumeOff()
                IconVolumeUp()
                IconWallpaper()
                IconWarning()
                IconWidgets()
                IconWifiFull()
                IconWifiOff()
                IconWind()
                IconWork()
                IconWrapText()
                IconZoomIn()
                IconZoomOut()
            }
        }
        rule.onAllNodesWithContentDescription("Audio on").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Decrease size").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Hide").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Increase size").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Mute").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Offline").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Visible").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Voicemail").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Wallpaper").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Warning").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Wi-Fi").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Widgets").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Wind").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Work").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Wrap lines").assertCountEquals(1)
    }

    @Test fun laneIconsRender() {
        rule.setContent {
            Column {
                IconLaneThroughSlightLeft()
                IconLaneThroughSlightRight()
                IconLaneThroughLeftRight()
            }
        }
        rule.onAllNodesWithContentDescription("Straight or slight left lane").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Straight or slight right lane").assertCountEquals(1)
        rule.onAllNodesWithContentDescription("Straight, left or right lane").assertCountEquals(1)
    }

    @Test fun navigationIconsTriggerBack() {
        var backs = 0
        rule.setContent {
            Column {
                IconNavigation(navBack = { backs++ })
                IconBack()
            }
        }
        rule.onAllNodesWithContentDescription("Back").assertCountEquals(2)
        kotlin.test.assertEquals(0, backs)
    }
}
