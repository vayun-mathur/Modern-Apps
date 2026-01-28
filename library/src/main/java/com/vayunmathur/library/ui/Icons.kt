package com.vayunmathur.library.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.library.R
import com.vayunmathur.library.util.pop

@Composable
fun IconAdd(tint: Color = Color.Unspecified) {
    Icon(painterResource(R.drawable.add_24px), "Add", tint = tint)
}

@Composable
fun IconSave(tint: Color = Color.Unspecified) {
    Icon(painterResource(R.drawable.save_24px), "Save", tint = tint)
}

@Composable
fun IconEdit(tint: Color = Color.Unspecified) {
    Icon(painterResource(R.drawable.edit_24px), "Edit", tint = tint)
}

@Composable
fun IconDelete(tint: Color = Color.Unspecified) {
    Icon(painterResource(R.drawable.delete_24px), "Delete", tint = tint)
}

@Composable
fun IconShare(tint: Color = Color.Unspecified) {
    Icon(painterResource(R.drawable.share_24px), "Delete", tint = tint)
}

@Composable
fun IconClose(tint: Color = Color.Unspecified) {
    Icon(painterResource(R.drawable.close_24px), "Close", tint = tint)
}

@Composable
fun IconSettings(tint: Color = Color.Unspecified) {
    Icon(painterResource(R.drawable.settings_24px), "Settings", tint = tint)
}

@Composable
fun IconVisible(tint: Color = Color.Unspecified) {
    Icon(painterResource(R.drawable.visibility_24px), "Visible", tint = tint)
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