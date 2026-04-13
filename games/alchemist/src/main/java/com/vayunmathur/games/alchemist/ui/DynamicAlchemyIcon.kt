package com.vayunmathur.games.alchemist.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
    
    // 1. Construct the resource name string (e.g., "icon_1")
    val name = "icon_${iconId.toString().padStart(3, '0')}"
    
    // 2. Look up the internal Android resource ID (the Int)
    val resId = remember(iconId) { context.resources.getIdentifier(
        name,           // The name of the file
        "drawable",     // The folder/type
        context.packageName // Your app's package
    ) }

    if (resId != 0) {
        Image(
            painter = painterResource(id = resId),
            contentDescription = stringResource(R.string.alchemy_icon_content_description, iconId),
            modifier = modifier
        )
    } else {
        // Fallback if the icon ID doesn't exist
        Box(modifier = modifier.background(Color.Gray))
    }
}