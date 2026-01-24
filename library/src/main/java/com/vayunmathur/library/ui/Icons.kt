package com.vayunmathur.library.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.library.R
import com.vayunmathur.library.util.pop

@Composable
fun IconAdd() {
    Icon(painterResource(R.drawable.add_24px), "Add")
}

@Composable
fun IconSave() {
    Icon(painterResource(R.drawable.save_24px), "Save")
}

@Composable
fun IconEdit() {
    Icon(painterResource(R.drawable.edit_24px), "Edit")
}

@Composable
fun IconDelete() {
    Icon(painterResource(R.drawable.delete_24px), "Delete")
}

@Composable
fun IconShare() {
    Icon(painterResource(R.drawable.share_24px), "Delete")
}

@Composable
fun IconClose() {
    Icon(painterResource(R.drawable.close_24px), "Close")
}

@Composable
fun IconSettings() {
    Icon(painterResource(R.drawable.settings_24px), "Settings")
}

@Composable
fun IconVisible() {
    Icon(painterResource(R.drawable.visibility_24px), "Visible")
}

@Composable
fun IconNavigation(navBack: () -> Unit) {
    IconButton({
        navBack()
    }) {
        Icon(painterResource(R.drawable.arrow_back_24px), "Navigation")
    }
}

@Composable
fun IconNavigation(backStack: NavBackStack<out NavKey>) {
    IconButton({
        backStack.pop()
    }) {
        Icon(painterResource(R.drawable.arrow_back_24px), "Navigation")
    }
}