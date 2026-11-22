package com.vayunmathur.photos.ui

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.vayunmathur.library.log.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.ui.NavigationBar
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.platform.pushPhoto
import com.vayunmathur.photos.platform.FaceCropTransformation
import com.vayunmathur.photos.platform.FaceRecognizer
import com.vayunmathur.photos.platform.GalleryViewModel
import com.vayunmathur.photos.platform.PeopleUiState
import com.vayunmathur.photos.platform.PersonCluster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Binds [GalleryViewModel] to the stateless [PeopleScreen]. */
@Composable
fun PeopleScreen(
    backStack: NavBackStack<Route>,
    state: PeopleUiState,
    /**
     * How a cluster paints its representative face. Previews pass a placeholder: the real
     * one crops a MediaStore image, which Layoutlib cannot decode.
     */
    faceThumbnail: @Composable (PersonCluster, Modifier) -> Unit = { person, modifier -> FaceThumbnail(person, modifier) },
    /**
     * Name (or rename) a cluster. Opens the contact picker, which needs an
     * activity-result launcher, so the binder owns it — a preview renders the
     * labels fine with the no-op default.
     */
    onNameClick: (PersonCluster) -> Unit = {},
    /** Hide a cluster from this grid (data retained). Binder persists it. */
    onHideClick: (PersonCluster) -> Unit = {},
    /** Delete a cluster and its face assignments (photos untouched). */
    onDeleteClick: (PersonCluster) -> Unit = {},
) {
    // Show progress while there are still photos left to scan (or the worker is
    // actively running); it disappears on its own once everything is scanned.
    val showProgress = state.faceTargetCount > 0 &&
        (state.indexing || state.faceScannedCount < state.faceTargetCount)

    AppScaffold(
        topBar = {},
        bottomBar = { NavigationBar(Route.People, backStack) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (!state.modelsAvailable) {
                Text(
                    text = stringResource(R.string.people_model_missing),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (showProgress) {
                        FaceIndexingProgress(done = state.faceScannedCount, total = state.faceTargetCount)
                    }
                    if (state.people.isNotEmpty()) {
                        PeopleGrid(
                            people = state.people,
                            modifier = Modifier.weight(1f),
                            faceThumbnail = faceThumbnail,
                            onNameClick = onNameClick,
                            onHideClick = onHideClick,
                            onDeleteClick = onDeleteClick,
                        ) { person ->
                            backStack.pushPhoto(Route.PhotoPage(person.coverPhoto.id, com.vayunmathur.photos.domain.PhotoSource.Person(person.id)))
                        }
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(
                                    if (showProgress) R.string.people_scanning else R.string.people_empty
                                ),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(32.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
