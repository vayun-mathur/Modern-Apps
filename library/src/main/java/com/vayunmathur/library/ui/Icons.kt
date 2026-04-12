package com.vayunmathur.library.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.R

@Composable
fun IconAdd(tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.add_24px), "Add", tint = tint)
}

@Composable
fun IconSave(tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.save_24px), "Save", tint = tint)
}

@Composable
fun IconEdit(tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.edit_24px), "Edit", tint = tint)
}

@Composable
fun IconDelete(tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.delete_24px), "Delete", tint = tint)
}

@Composable
fun IconShare(tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.share_24px), "Share", tint = tint)
}

@Composable
fun IconClose(tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.close_24px), "Close", tint = tint)
}

@Composable
fun IconSettings(tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.settings_24px), "Settings", tint = tint)
}

@Composable
fun IconVisible(tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.visibility_24px), "Visible", tint = tint)
}

@Composable
fun IconSearch(tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.outline_search_24), "Search", tint = tint)
}

@Composable
fun IconCopy(tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.outline_content_copy_24), "Copy", tint = tint)
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
fun IconNavigation(backStack: NavBackStack<out NavKey>) {
    IconButton({
        backStack.pop()
    }) {
        Icon(painterResource(R.drawable.arrow_back_24px), "Back")
    }
}

@Composable
fun IconCheck(tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.outline_check_24), "Check", tint = tint)
}

@Composable
fun IconStar(tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.baseline_star_24), "Star", tint = tint)
}

@Composable
fun IconPlay(tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.outline_play_arrow_24), "Play", tint = tint)
}

@Composable
fun IconPause(tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.outline_pause_24), "Pause", tint = tint)
}

@Composable
fun IconStop(tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.baseline_stop_24), "Stop", tint = tint)
}

@Composable
fun IconMenu(tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.baseline_menu_24), "Menu", tint = tint)
}

@Composable
fun IconUpload(tint: Color = LocalContentColor.current) {
    Icon(painterResource(R.drawable.baseline_upload_24), "Upload", tint = tint)
}