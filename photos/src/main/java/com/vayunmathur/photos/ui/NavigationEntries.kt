package com.vayunmathur.photos.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.LocalActivity
import android.net.Uri
import com.vayunmathur.library.ui.LoadingIndicator
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.VaultPhotoDao
import com.vayunmathur.photos.platform.GalleryViewModel
import com.vayunmathur.photos.platform.SecureFolderViewModel
import com.vayunmathur.photos.platform.shouldRelock

/**
 * Opt-in re-lock: leaving the Secure Folder (any route other than the
 * folder itself or the vault viewer, which counts as inside) locks the
 * vault so the next open requires unlock again. Off by default, which
 * preserves the current stay-unlocked behaviour.
 */
@Composable
internal fun RelockEffect(
    backStack: NavBackStack<Route>,
    relockOnExit: Boolean,
    vaultPhotoDao: com.vayunmathur.photos.data.VaultPhotoDao?,
    secureFolderViewModel: SecureFolderViewModel,
) {
    val topRoute = backStack.backStack.lastOrNull()
    LaunchedEffect(topRoute, relockOnExit) {
        if (shouldRelock(relockOnExit, topRoute, vaultPhotoDao)) {
            secureFolderViewModel.lock()
        }
    }
}

/** Index one incoming URI once per launch (survives configuration change). */
@Composable
internal fun IndexViewUriEffect(viewUri: Uri?, galleryViewModel: GalleryViewModel) {
    var indexedViewUri by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewUri) {
        if (viewUri == null || indexedViewUri) return@LaunchedEffect
        indexedViewUri = true
        galleryViewModel.resolveAndIndex(viewUri) {}
    }
}

@Composable
internal fun VaultViewerEntry(
    backStack: NavBackStack<Route>,
    vaultId: Long,
    vaultPassword: String?,
    secureFolderViewModel: SecureFolderViewModel,
) {
    val password = vaultPassword
    if (password != null) {
        VaultViewerPage(backStack, vaultId, password, secureFolderViewModel)
    } else {
        SecureFolderEntry(backStack, secureFolderViewModel, false, null)
    }
}

@Composable
internal fun SecureFolderEntry(
    backStack: NavBackStack<Route>,
    secureFolderViewModel: SecureFolderViewModel,
    isUnlocked: Boolean,
    vaultPassword: String?,
) {
    val activity = LocalActivity.current as FragmentActivity
    if (!isUnlocked) {
        LaunchedEffect(Unit) {
            secureFolderViewModel.unlock(
                activity,
                onSuccess = { _, _ -> },
                onFailure = { message ->
                    if (message != null) {
                        AppMessages.show(message)
                    }
                    backStack.pop()
                },
            )
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingIndicator()
        }
    } else {
        SecureFolderPage(backStack, vaultPassword!!, secureFolderViewModel)
    }
}
