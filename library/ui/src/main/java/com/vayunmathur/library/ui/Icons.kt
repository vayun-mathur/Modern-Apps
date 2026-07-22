package com.vayunmathur.library.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.ForkLeft
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material.icons.filled.RampLeft
import androidx.compose.material.icons.filled.RampRight
import androidx.compose.material.icons.filled.RoundaboutLeft
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.ArrowRightAlt
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.HideSource
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Pentagon
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.Rectangle
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Square
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.outlined.ArrowRightAlt
import androidx.compose.material.icons.outlined.ChangeHistory
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material.icons.outlined.Hexagon
import androidx.compose.material.icons.outlined.Pentagon
import androidx.compose.material.icons.outlined.Rectangle
import androidx.compose.material.icons.outlined.Square
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.ui.res.painterResource
import com.vayunmathur.library.R
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Attachment
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Iso
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey

/**
 * App-facing icon set. Every shared icon is rendered from `material-icons-extended`
 * (the single source of truth) and exposed only through these `IconXyz()` composables,
 * so apps never reference `androidx.compose.material.icons.*` directly.
 */
@Composable
fun AppIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(imageVector, contentDescription, modifier = modifier, tint = tint)
}

@Composable
fun IconAdd(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Add, "Add", modifier, tint)

@Composable
fun IconSave(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Save, "Save", modifier, tint)

@Composable
fun IconEdit(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Edit, "Edit", modifier, tint)

@Composable
fun IconDelete(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Delete, "Delete", modifier, tint)

@Composable
fun IconVerify(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.VerifiedUser, "Verify security code", modifier, tint)

@Composable
fun IconShare(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Share, "Share", modifier, tint)

@Composable
fun IconClose(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Close, "Close", modifier, tint)

@Composable
fun IconSettings(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Settings, "Settings", modifier, tint)

@Composable
fun IconVisible(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Visibility, "Visible", modifier, tint)

@Composable
fun IconSearch(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Search, "Search", modifier, tint)

@Composable
fun IconCopy(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.ContentCopy, "Copy", modifier, tint)

@Composable
fun IconCrop(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Crop, "Crop", modifier, tint)

@Composable
fun IconRotateLeft(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.RotateLeft, "Rotate Left", modifier, tint)

@Composable
fun IconRotateRight(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.RotateRight, "Rotate Right", modifier, tint)

@Composable
fun IconNavigation(navBack: () -> Unit) {
    IconButton({ navBack() }) {
        AppIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
    }
}

@Composable
fun IconNavigation(backStack: NavBackStack<out NavKey>, modifier: Modifier = Modifier) {
    IconButton({ backStack.pop() }, modifier = modifier) {
        AppIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
    }
}

@Composable
fun IconCheck(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Check, "Check", modifier, tint)

@Composable
fun IconStar(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Star, "Star", modifier, tint)

@Composable
fun IconPlay(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.PlayArrow, "Play", modifier, tint)

@Composable
fun IconPause(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Pause, "Pause", modifier, tint)

@Composable
fun IconStop(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Stop, "Stop", modifier, tint)

@Composable
fun IconMenu(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Menu, "Menu", modifier, tint)

@Composable
fun IconUpload(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Upload, "Upload", modifier, tint)

@Composable
fun IconUnarchive(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Unarchive, "Unarchive", modifier, tint)

@Composable
fun IconArchive(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Archive, "Archive", modifier, tint)

@Composable
fun IconChevronRight(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.ChevronRight, "Chevron", modifier, tint)

@Composable
fun IconUndo(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.AutoMirrored.Filled.Undo, "Undo", modifier, tint)

@Composable
fun IconForward(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.AutoMirrored.Filled.Forward, "Forward", modifier, tint)

@Composable
fun IconDraw(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Draw, "Draw", modifier, tint)

@Composable
fun IconBrush(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Brush, "Brush", modifier, tint)

@Composable
fun IconEraser(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.AutoMirrored.Filled.Backspace, "Eraser", modifier, tint)

@Composable
fun IconCamera(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.CameraAlt, "Camera", modifier, tint)

@Composable
fun IconCameraOff(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.NoPhotography, "Camera off", modifier, tint)

@Composable
fun IconBackup(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Backup, "Backup", modifier, tint)

@Composable
fun IconRestore(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.SettingsBackupRestore, "Restore", modifier, tint)

@Composable
fun IconMarkRead(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Check, "Mark Read", modifier, tint)

@Composable
fun IconMarkUnread(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Outlined.MailOutline, "Mark Unread", modifier, tint)

@Composable
fun IconFavorite(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Favorite, "Favorite", modifier, tint)

@Composable
fun IconFire(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Whatshot, "Fire", modifier, tint)

@Composable
fun IconInbox(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Outlined.Inbox, "Inbox", modifier, tint)

@Composable
fun IconSend(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.AutoMirrored.Filled.Send, "Send", modifier, tint)

@Composable
fun IconAttachment(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Attachment, "Attachment", modifier, tint)

@Composable
fun IconMail(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Outlined.MailOutline, "Mail", modifier, tint)

@Composable
fun IconDownload(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Download, "Download", modifier, tint)

@Composable
fun IconNavigationArrow(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Navigation, "Navigation arrow", modifier, tint)

@Composable
fun IconBack(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", modifier, tint)

@Composable
fun IconRefresh(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Refresh, "Refresh", modifier, tint)

@Composable
fun IconHome(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Home, "Home", modifier, tint)

@Composable
fun IconWork(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Work, "Work", modifier, tint)

@Composable
fun IconApartment(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Apartment, "Apartment", modifier, tint)

@Composable
fun IconReceipt(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Receipt, "Receipt", modifier, tint)

@Composable
fun IconImage(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Image, "Image", modifier, tint)

@Composable
fun IconKeyboardArrowUp(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.KeyboardArrowUp, "Up", modifier, tint)

@Composable
fun IconKeyboardArrowDown(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.KeyboardArrowDown, "Down", modifier, tint)

@Composable
fun IconDragHandle(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.DragHandle, "Reorder", modifier, tint)

@Composable
fun IconEmojiEvents(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.EmojiEvents, "Badges", modifier, tint)

@Composable
fun IconHighlightAlt(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Outlined.HighlightAlt, "Select", modifier, tint)

@Composable
fun IconArrowDropDown(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.ArrowDropDown, "Dropdown", modifier, tint)

@Composable
fun IconArrowForward(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.AutoMirrored.Filled.ArrowForward, "Forward", modifier, tint)

@Composable
fun IconSchedule(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Schedule, "Schedule", modifier, tint)

@Composable
fun IconGlobe(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Public, "Website", modifier, tint)

@Composable
fun IconDescription(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Description, "Details", modifier, tint)

@Composable
fun IconSunny(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.WbSunny, "Sunny", modifier, tint)

@Composable
fun IconContrast(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Contrast, "Contrast", modifier, tint)

@Composable
fun IconBlur(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.BlurOn, "Blur", modifier, tint)

@Composable
fun IconFlashlight(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.FlashlightOn, "Flashlight", modifier, tint)

@Composable
fun IconFlashOn(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.FlashOn, "Flash on", modifier, tint)

@Composable
fun IconFlashOff(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.FlashOff, "Flash off", modifier, tint)

@Composable
fun IconFlashAuto(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.FlashAuto, "Flash auto", modifier, tint)

@Composable
fun IconGrid(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.GridOn, "Grid", modifier, tint)

@Composable
fun IconTimer(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Timer, "Timer", modifier, tint)

@Composable
fun IconMic(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Mic, "Mic", modifier, tint)

@Composable
fun IconMicOff(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.MicOff, "Mic off", modifier, tint)

@Composable
fun IconBedtime(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Bedtime, "Sleep", modifier, tint)

@Composable
fun IconPhotoLibrary(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.PhotoLibrary, "Photos", modifier, tint)

@Composable
fun IconFlipCamera(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.FlipCameraAndroid, "Flip camera", modifier, tint)

@Composable
fun IconVideoCamera(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Videocam, "Video", modifier, tint)

@Composable
fun IconRestartAlt(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.RestartAlt, "Restart", modifier, tint)

@Composable
fun IconAlarm(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Alarm, "Alarm", modifier, tint)

@Composable
fun IconAccessTime(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.AccessTime, "Clock", modifier, tint)

@Composable
fun IconHourglass(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.HourglassBottom, "Timer", modifier, tint)

@Composable
fun IconPerson(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Person, "Person", modifier, tint)

@Composable
fun IconGroup(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Group, "Group", modifier, tint)

@Composable
fun IconStarBorder(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.StarBorder, "Star", modifier, tint)

@Composable
fun IconCall(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Call, "Call", modifier, tint)

@Composable
fun IconChat(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Chat, "Chat", modifier, tint)

@Composable
fun IconLocationOn(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.LocationOn, "Location", modifier, tint)

@Composable
fun IconDirections(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Directions, "Directions", modifier, tint)

@Composable
fun IconCake(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Cake, "Birthday", modifier, tint)

@Composable
fun IconEvent(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Event, "Event", modifier, tint)

@Composable
fun IconSms(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Sms, "Message", modifier, tint)

@Composable
fun IconRemoveCircle(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.RemoveCircleOutline, "Remove", modifier, tint)

@Composable
fun IconAddPhoto(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.AddPhotoAlternate, "Add photo", modifier, tint)

@Composable
fun IconFolder(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Folder, "Folder", modifier, tint)

@Composable
fun IconFile(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.InsertDriveFile, "File", modifier, tint)

@Composable
fun IconLink(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Link, "Link", modifier, tint)

@Composable
fun IconDirectionsWalk(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.AutoMirrored.Filled.DirectionsWalk, "Walk", modifier, tint)

@Composable
fun IconMenuBook(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.AutoMirrored.Filled.MenuBook, "Menu", modifier, tint)

@Composable
fun IconShuffle(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Shuffle, "Shuffle", modifier, tint)

@Composable
fun IconLibraryMusic(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.LibraryMusic, "Music", modifier, tint)

@Composable
fun IconAlbum(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Album, "Album", modifier, tint)

@Composable
fun IconMoreVert(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.MoreVert, "More", modifier, tint)

@Composable
fun IconRepeat(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Repeat, "Repeat", modifier, tint)

@Composable
fun IconRepeatOne(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.RepeatOne, "Repeat one", modifier, tint)

@Composable
fun IconSkipPrevious(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.SkipPrevious, "Previous", modifier, tint)

@Composable
fun IconSkipNext(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.SkipNext, "Next", modifier, tint)

@Composable
fun IconFormatAlignLeft(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.AutoMirrored.Filled.FormatAlignLeft, "Align left", modifier, tint)

@Composable
fun IconFormatAlignCenter(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.FormatAlignCenter, "Align center", modifier, tint)

@Composable
fun IconFormatAlignRight(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.AutoMirrored.Filled.FormatAlignRight, "Align right", modifier, tint)

@Composable
fun IconFormatAlignJustify(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.FormatAlignJustify, "Justify", modifier, tint)

@Composable
fun IconFormatColorText(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.FormatColorText, "Text color", modifier, tint)

@Composable
fun IconFormatIndentIncrease(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.AutoMirrored.Filled.FormatIndentIncrease, "Indent", modifier, tint)

@Composable
fun IconFormatIndentDecrease(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.AutoMirrored.Filled.FormatIndentDecrease, "Outdent", modifier, tint)

@Composable
fun IconFormatListBulleted(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.AutoMirrored.Filled.FormatListBulleted, "Bulleted list", modifier, tint)

@Composable
fun IconFormatListNumbered(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.FormatListNumbered, "Numbered list", modifier, tint)

@Composable
fun IconCheckBox(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.CheckBox, "Checkbox", modifier, tint)

@Composable
fun IconRedo(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.AutoMirrored.Filled.Redo, "Redo", modifier, tint)

@Composable
fun IconVisibilityOff(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.VisibilityOff, "Hide", modifier, tint)

@Composable
fun IconKey(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Key, "Key", modifier, tint)

@Composable
fun IconLock(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Lock, "Lock", modifier, tint)

@Composable
fun IconPlayCircle(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.PlayCircle, "Play", modifier, tint)

@Composable
fun IconMap(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Map, "Map", modifier, tint)

@Composable
fun IconMyLocation(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.MyLocation, "My location", modifier, tint)

@Composable
fun IconCalendar(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.CalendarMonth, "Calendar", modifier, tint)

@Composable
fun IconCloudy(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Cloud, "Cloudy", modifier, tint)

@Composable
fun IconRain(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.WaterDrop, "Rain", modifier, tint)

@Composable
fun IconDrizzle(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Grain, "Drizzle", modifier, tint)

@Composable
fun IconWind(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Air, "Wind", modifier, tint)

@Composable
fun IconGrass(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Grass, "Pollen", modifier, tint)

@Composable
fun IconClearNight(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.DarkMode, "Clear night", modifier, tint)

@Composable
fun IconThumbUp(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.ThumbUp, "Like", modifier, tint)

@Composable
fun IconThumbDown(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.ThumbDown, "Dislike", modifier, tint)

@Composable
fun IconFullscreen(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Fullscreen, "Fullscreen", modifier, tint)

@Composable
fun IconFullscreenExit(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.FullscreenExit, "Exit fullscreen", modifier, tint)

@Composable
fun IconList(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.AutoMirrored.Filled.List, "List", modifier, tint)

@Composable
fun IconSubscriptions(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Subscriptions, "Subscriptions", modifier, tint)

@Composable
fun IconHistory(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.History, "History", modifier, tint)

@Composable
fun IconIso(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Iso, "ISO", modifier, tint)

@Composable
fun IconInfo(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Info, "Info", modifier, tint)

@Composable
fun IconNote(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.AutoMirrored.Filled.Note, "Note", modifier, tint)

// --- Camera / health custom (Material Symbol vectors bundled in library res) ---
@Composable
fun IconLightbulb(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Lightbulb, "Warmth", modifier, tint)

@Composable
fun IconBodySystem(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    Icon(painterResource(R.drawable.body_system_24px), "Body", modifier = modifier, tint = tint)

@Composable
fun IconToolsLevel(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    Icon(painterResource(R.drawable.tools_level_24px), "Level", modifier = modifier, tint = tint)

// --- Sync provider (generic) ---
@Composable
fun IconProvider(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.CloudSync, "Provider", modifier, tint)

// --- Weather conditions ---
@Composable
fun IconSnow(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.AcUnit, "Snow", modifier, tint)

@Composable
fun IconThunder(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Thunderstorm, "Thunderstorm", modifier, tint)

@Composable
fun IconFog(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Dehaze, "Fog", modifier, tint)

@Composable
fun IconPressure(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Compress, "Pressure", modifier, tint)

@Composable
fun IconPartlyCloudyDay(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.WbCloudy, "Partly cloudy", modifier, tint)

@Composable
fun IconPartlyCloudyNight(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.NightsStay, "Partly cloudy", modifier, tint)

// --- Navigation maneuvers ---
@Composable
fun IconTurnLeft(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.TurnLeft, "Turn left", modifier, tint)

@Composable
fun IconTurnRight(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.TurnRight, "Turn right", modifier, tint)

@Composable
fun IconTurnSlightLeft(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.TurnSlightLeft, "Slight left", modifier, tint)

@Composable
fun IconTurnSlightRight(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.TurnSlightRight, "Slight right", modifier, tint)

@Composable
fun IconTurnSharpLeft(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.TurnSharpLeft, "Sharp left", modifier, tint)

@Composable
fun IconTurnSharpRight(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.TurnSharpRight, "Sharp right", modifier, tint)

@Composable
fun IconUTurn(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.UTurnLeft, "U-turn", modifier, tint)

@Composable
fun IconStraight(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Straight, "Straight", modifier, tint)

@Composable
fun IconMerge(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Merge, "Merge", modifier, tint)

@Composable
fun IconForkLeft(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.ForkLeft, "Fork left", modifier, tint)

@Composable
fun IconForkRight(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.ForkRight, "Fork right", modifier, tint)

@Composable
fun IconRampLeft(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.RampLeft, "Ramp left", modifier, tint)

@Composable
fun IconRampRight(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.RampRight, "Ramp right", modifier, tint)

@Composable
fun IconRoundaboutLeft(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.RoundaboutLeft, "Roundabout", modifier, tint)

@Composable
fun IconRoundaboutRight(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.RoundaboutRight, "Roundabout", modifier, tint)

// --- PDF editor tools ---
@Composable
fun IconSelect(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.NearMe, "Select", modifier, tint)

@Composable
fun IconTextTool(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.TextFields, "Text", modifier, tint)

@Composable
fun IconHighlight(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Highlight, "Highlight", modifier, tint)

@Composable
fun IconLine(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.HorizontalRule, "Line", modifier, tint)

@Composable
fun IconPolyline(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Polyline, "Polyline", modifier, tint)

@Composable
fun IconBezier(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.ShowChart, "Bézier curve", modifier, tint)

@Composable
fun IconCallout(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.ChatBubbleOutline, "Callout", modifier, tint)

@Composable
fun IconRedact(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.HideSource, "Redact", modifier, tint)

@Composable
fun IconStyle(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Style, "Style", modifier, tint)

@Composable
fun IconSignature(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Gesture, "Signature", modifier, tint)

// --- PDF text markup ---
@Composable
fun IconFormatUnderlined(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.FormatUnderlined, "Underline", modifier, tint)

@Composable
fun IconStrikethrough(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.StrikethroughS, "Strikeout", modifier, tint)

@Composable
fun IconSquiggly(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Waves, "Squiggly", modifier, tint)

// --- PDF annotation shapes (fill = Filled theme, outline = Outlined theme) ---
@Composable
fun IconShapeRectFill(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Rectangle, "Rectangle", modifier, tint)

@Composable
fun IconShapeRectOutline(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Outlined.Rectangle, "Rectangle outline", modifier, tint)

@Composable
fun IconShapeRoundRectFill(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Square, "Rounded rectangle", modifier, tint)

@Composable
fun IconShapeRoundRectOutline(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Outlined.Square, "Rounded rectangle outline", modifier, tint)

@Composable
fun IconShapeOvalFill(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Circle, "Oval", modifier, tint)

@Composable
fun IconShapeOvalOutline(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Outlined.Circle, "Oval outline", modifier, tint)

@Composable
fun IconShapeTriangleFill(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.ChangeHistory, "Triangle", modifier, tint)

@Composable
fun IconShapeTriangleOutline(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Outlined.ChangeHistory, "Triangle outline", modifier, tint)

@Composable
fun IconShapeDiamondFill(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Diamond, "Diamond", modifier, tint)

@Composable
fun IconShapeDiamondOutline(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Outlined.Diamond, "Diamond outline", modifier, tint)

@Composable
fun IconShapePentagonFill(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Pentagon, "Pentagon", modifier, tint)

@Composable
fun IconShapePentagonOutline(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Outlined.Pentagon, "Pentagon outline", modifier, tint)

@Composable
fun IconShapeHexagonFill(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Hexagon, "Hexagon", modifier, tint)

@Composable
fun IconShapeHexagonOutline(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Outlined.Hexagon, "Hexagon outline", modifier, tint)

@Composable
fun IconShapeStarFill(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.Star, "Star", modifier, tint)

@Composable
fun IconShapeStarOutline(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.StarOutline, "Star outline", modifier, tint)

@Composable
fun IconShapeArrowFill(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Filled.ArrowRightAlt, "Arrow", modifier, tint)

@Composable
fun IconShapeArrowOutline(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) =
    AppIcon(Icons.Outlined.ArrowRightAlt, "Arrow outline", modifier, tint)
