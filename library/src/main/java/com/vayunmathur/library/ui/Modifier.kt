package com.vayunmathur.library.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier

fun Modifier.invisibleClickable(onClick: () -> Unit) = clickable(null, null) {onClick()}