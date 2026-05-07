package com.vayunmathur.games.alchemist.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.alchemist.R

@Composable
fun DynamicAlchemyIcon(iconId: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    
    // 1. Construct the resource name string (e.g., "icon_001")
    val name = "icon_${iconId.toString().padStart(3, '0')}"
    
    // 2. Look up the internal Android resource ID
    val resId = remember(iconId) { 
        val id = context.resources.getIdentifier(
            name,
            "drawable",
            context.packageName
        )
        if (id == 0) {
            // Fallback to explicit package name
            context.resources.getIdentifier(
                name,
                "drawable",
                "com.vayunmathur.games.alchemist"
            )
        } else id
    }

    if (resId != 0) {
        Image(
            painter = painterResource(id = resId),
            contentDescription = stringResource(R.string.alchemy_icon_content_description, iconId),
            modifier = modifier.fillMaxSize()
        )
    } else {
        // Fallback if the icon ID doesn't exist
        Box(modifier = modifier.fillMaxSize())
    }
}