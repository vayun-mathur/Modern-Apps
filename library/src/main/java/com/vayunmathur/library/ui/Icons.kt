package com.vayunmathur.library.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.R

@Composable
fun IconAdd(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.add_24px), "Add", modifier = modifier, tint = tint)
}

@Composable
fun IconSave(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.save_24px), "Save", modifier = modifier, tint = tint)
}

@Composable
fun IconEdit(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.edit_24px), "Edit", modifier = modifier, tint = tint)
}

@Composable
fun IconDelete(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.delete_24px), "Delete", modifier = modifier, tint = tint)
}

@Composable
fun IconShare(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.share_24px), "Share", modifier = modifier, tint = tint)
}

@Composable
fun IconClose(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.close_24px), "Close", modifier = modifier, tint = tint)
}

@Composable
fun IconSettings(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.settings_24px), "Settings", modifier = modifier, tint = tint)
}

@Composable
fun IconVisible(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.visibility_24px), "Visible", modifier = modifier, tint = tint)
}

@Composable
fun IconSearch(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.outline_search_24), "Search", modifier = modifier, tint = tint)
}

@Composable
fun IconCopy(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.outline_content_copy_24), "Copy", modifier = modifier, tint = tint)
}

@Composable
fun IconCrop(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.crop_24px), "Crop", modifier = modifier, tint = tint)
}

@Composable
fun IconRotateLeft(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.rotate_left_24px), "Rotate Left", modifier = modifier, tint = tint)
}

@Composable
fun IconRotateRight(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.rotate_right_24px), "Rotate Right", modifier = modifier, tint = tint)
}

@Composable
fun IconNavigation(navBack: () -> Unit) {
    IconButton({
        navBack()
    }) {
        Icon(painterResource(R.drawable.arrow_back_24px), "Back")
    }
}

@Composable
fun IconNavigation(backStack: NavBackStack<out NavKey>, modifier: Modifier = Modifier) {
    IconButton({
        backStack.pop()
    }, modifier = modifier) {
        Icon(painterResource(R.drawable.arrow_back_24px), "Back")
    }
}

@Composable
fun IconCheck(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.outline_check_24), "Check", modifier = modifier, tint = tint)
}

@Composable
fun IconStar(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.baseline_star_24), "Star", modifier = modifier, tint = tint)
}

@Composable
fun IconPlay(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.outline_play_arrow_24), "Play", modifier = modifier, tint = tint)
}

@Composable
fun IconPause(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.outline_pause_24), "Pause", modifier = modifier, tint = tint)
}

@Composable
fun IconStop(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.baseline_stop_24), "Stop", modifier = modifier, tint = tint)
}

@Composable
fun IconMenu(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.baseline_menu_24), "Menu", modifier = modifier, tint = tint)
}

@Composable
fun IconUpload(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.baseline_upload_24), "Upload", modifier = modifier, tint = tint)
}

@Composable
fun IconUnarchive(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.unarchive_24px), "Unarchive", modifier = modifier, tint = tint)
}

@Composable
fun IconArchive(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.archive_24px), "Archive", modifier = modifier, tint = tint)
}

@Composable
fun IconChevronRight(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.chevron_right_24px), "Chevron", modifier = modifier, tint = tint)
}

@Composable
fun IconUndo(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.undo_24px), "Undo", modifier = modifier, tint = tint)
}

@Composable
fun IconForward(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.outline_shortcut_24), "Forward", modifier = modifier, tint = tint)
}

@Composable
fun IconDraw(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.pen_24px), "Draw", modifier = modifier, tint = tint)
}

@Composable
fun IconBrush(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.brush_24px), "Brush", modifier = modifier, tint = tint)
}

@Composable
fun IconEraser(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.eraser_24px), "Eraser", modifier = modifier, tint = tint)
}

@Composable
fun IconCamera(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.camera_alt_24px), "Camera", modifier = modifier, tint = tint)
}

@Composable
fun IconBackup(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.outline_backup_24), "Backup", modifier = modifier, tint = tint)
}

@Composable
fun IconRestore(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.outline_settings_backup_restore_24), "Restore", modifier = modifier, tint = tint)
}
@Composable
fun IconMarkRead(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.outline_check_24), "Mark Read", modifier = modifier, tint = tint)
}

@Composable
fun IconMarkUnread(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.outline_mail_outline_24), "Mark Unread", modifier = modifier, tint = tint)
}

@Composable
fun IconFavorite(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.favorite_24px), "Favorite", modifier = modifier, tint = tint)
}

@Composable
fun IconFire(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.fire_24px), "Fire", modifier = modifier, tint = tint)
}

@Composable
fun IconInbox(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.outline_inbox_24), "Inbox", modifier = modifier, tint = tint)
}

@Composable
fun IconSend(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.outline_send_24), "Send", modifier = modifier, tint = tint)
}

@Composable
fun IconAttachment(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.outline_attachment_24), "Attachment", modifier = modifier, tint = tint)
}

@Composable
fun IconMail(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.outline_mail_outline_24), "Mail", modifier = modifier, tint = tint)
}

@Composable
fun IconDownload(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.outline_file_download_24), "Download", modifier = modifier, tint = tint)
}
