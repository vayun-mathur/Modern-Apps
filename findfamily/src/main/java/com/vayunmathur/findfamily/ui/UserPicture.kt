package com.vayunmathur.findfamily.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.invisibleClickable
import kotlin.io.encoding.Base64

@Composable
fun UserPicture(user: User, size: Dp, grayscale: Boolean = false, onClick: () -> Unit = {}) {
    UserPicture(user.photo, user.name.first(), size, grayscale, onClick)
}

@Composable
fun UserPicture(userPhoto: String?, firstChar: Char, size: Dp, grayscale: Boolean, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val modifier = Modifier.clip(CircleShape).size(size).border(2.dp, MaterialTheme.colorScheme.primary, CircleShape).invisibleClickable(onClick)
    if(userPhoto != null) {
        val userOutput: Any = if(userPhoto.startsWith("data")) {
            val cleanString = userPhoto.substringAfter("base64,")
            Base64.UrlSafe.decode(cleanString)
        } else userPhoto
        AsyncImage(
            ImageRequest.Builder(context)
                .data(userOutput)
                .memoryCacheKey("user-photo-${userPhoto.hashCode()}")
                .build(),
            null,
            modifier,
            contentScale = ContentScale.FillWidth,
            colorFilter = if (grayscale) ColorFilter.GrayScale else null
        )
    } else {
        GreenCircle(size, firstChar, grayscale, onClick = onClick)
    }
}
