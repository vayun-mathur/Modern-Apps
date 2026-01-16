package com.vayunmathur.library.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import com.vayunmathur.library.R

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
fun IconNavigation(navBack: () -> Unit) {
    IconButton({
        navBack
    }) {
        Icon(painterResource(R.drawable.arrow_back_24px), "Navigation")
    }
}