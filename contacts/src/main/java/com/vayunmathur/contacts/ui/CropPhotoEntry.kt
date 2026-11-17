package com.vayunmathur.contacts.ui

import androidx.compose.runtime.Composable
import com.vayunmathur.contacts.Route
import com.vayunmathur.contacts.platform.ContactViewModel
import com.vayunmathur.contacts.platform.setEditDraftPhotoFromBitmap
import com.vayunmathur.library.util.NavBackStack

@Composable
internal fun CropPhotoEntry(
    backStack: NavBackStack<Route>,
    viewModel: ContactViewModel,
    key: Route.CropPhoto,
) {
    val decodedUri = java.net.URLDecoder.decode(key.uri, "UTF-8")
    CropPhotoScreen(
        uri = decodedUri,
        onCropComplete = { bitmap ->
            viewModel.setEditDraftPhotoFromBitmap(bitmap)
            backStack.pop()
        },
        onCancel = { backStack.pop() },
    )
}
