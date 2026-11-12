package com.vayunmathur.translate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.translate.R
import com.vayunmathur.translate.Route
import com.vayunmathur.translate.data.SharedImageCache
import com.vayunmathur.translate.platform.SharedImageArrival

/**
 * Acts once on a shared/opened image: a ready one replaces the stack with
 * Text → Image, a rejected one posts why through [AppMessages].
 */
@Composable
internal fun SharedImageEffect(
    sharedImage: SharedImageArrival?,
    backStack: NavBackStack<Route>,
    onConsumed: () -> Unit,
) {
    val tooLarge = stringResource(R.string.shared_image_too_large)
    val unreadable = stringResource(R.string.could_not_read_image)
    LaunchedEffect(sharedImage) {
        when (val arrival = sharedImage ?: return@LaunchedEffect) {
            is SharedImageArrival.Ready -> backStack.reset(Route.Text, Route.Image(arrival.imageUri))
            is SharedImageArrival.Rejected -> AppMessages.show(
                if (arrival.reason == SharedImageCache.Result.TooLarge) tooLarge else unreadable,
            )
        }
        onConsumed()
    }
}
