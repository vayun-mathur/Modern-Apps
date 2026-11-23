package com.vayunmathur.photos.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.platform.SecureFolderManager
import com.vayunmathur.photos.platform.SecureFolderViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Binder for the full-screen vault viewer. Collects the [VaultPhoto] by id,
 * decrypts it to the app cache off the main thread, and renders
 * [VaultViewerScreen] once ready. The plaintext cache file is deleted when the
 * viewer leaves the composition.
 */
@Composable
fun VaultViewerPage(
    backStack: NavBackStack<Route>,
    vaultId: Long,
    password: String,
    secureFolderViewModel: SecureFolderViewModel,
) {
    val context = LocalContext.current
    val photos by secureFolderViewModel.photos.collectAsState()
    val photo = photos.firstOrNull { it.id == vaultId }

    val decryptFailedMessage = stringResource(R.string.vault_viewer_decrypt_failed)

    // Null = still decrypting; non-null but file missing/failed handled below.
    val decryptedFile by produceState<File?>(initialValue = null, photo, password) {
        value = if (photo == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                SecureFolderManager(context).decryptToCacheFile(
                    photo.path,
                    password,
                    context.cacheDir,
                )
            }
        }
        if (photo != null && value == null) {
            AppMessages.show(decryptFailedMessage)
        }
    }

    // Plaintext must not outlive the viewer: delete the decrypted file (and any
    // stale ones from a killed process) on dispose.
    DisposableEffect(photo?.path) {
        onDispose {
            decryptedFile?.delete()
            SecureFolderManager(context).clearViewerCache(context.cacheDir)
        }
    }

    if (photo == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.vault_viewer_not_found))
        }
        return
    }

    if (decryptedFile == null) {
        // Viewer context without a scaffold: text affordance, no loading icon.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Loading…")
        }
        return
    }

    VaultViewerScreen(
        fileName = photo.name,
        file = decryptedFile,
        isVideo = photo.videoDuration != null,
        onBack = { backStack.pop() },
    )
}
