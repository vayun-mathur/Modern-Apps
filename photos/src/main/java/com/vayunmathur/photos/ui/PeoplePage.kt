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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconVisibilityOff
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.adaptiveGridCells
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
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
fun PeoplePage(
    backStack: NavBackStack<Route>,
    galleryViewModel: GalleryViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val people by galleryViewModel.people.collectAsState()
    val indexing by galleryViewModel.faceIndexing.collectAsState()
    val scanned by galleryViewModel.faceScannedCount.collectAsState()
    val target by galleryViewModel.indexTargetCount.collectAsState()
    // Off the main thread: `modelsAvailable` brings the models up, and on `:library:ml`
    // that means parsing two `.maml` blobs and uploading 7.9 MB to the GPU. Doing it in a
    // bare `remember { }` blocked the first composition of this screen for as long as
    // that took.
    val modelsAvailable by produceState(initialValue = false, context) {
        value = withContext(Dispatchers.IO) { FaceRecognizer.modelsAvailable(context) }
    }

    // The contact picker's result carries only the picked contact, with nothing to
    // say which cluster was being named, so the cluster id is parked here across
    // the launch — the same pending-state shape PhotoPage's delete flow uses.
    // An activity-result launcher can't live on a ViewModel, so it lives here in
    // the binder and PeopleScreen just gets a lambda (see GalleryActions' KDoc).
    var pendingClusterId by remember { mutableStateOf<Long?>(null) }
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        val clusterId = pendingClusterId
        pendingClusterId = null
        // A cancelled picker returns null; nothing to do.
        if (uri == null || clusterId == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = withContext(Dispatchers.IO) { contactDisplayName(context, uri) }
            if (name != null) galleryViewModel.setPersonName(clusterId, name)
        }
    }

    PeopleScreen(
        backStack = backStack,
        state = PeopleUiState(
            people = people,
            modelsAvailable = modelsAvailable,
            indexing = indexing,
            faceScannedCount = scanned,
            faceTargetCount = target,
        ),
        onNameClick = { person ->
            pendingClusterId = person.id
            pickContact.launch(null)
        },
        onHideClick = { person -> galleryViewModel.hidePerson(person.id) },
        onDeleteClick = { person -> galleryViewModel.deletePerson(person.id) },
    )
}

/**
 * The display name behind a URI from [ActivityResultContracts.PickContact], which
 * grants read access to that one contact — so this works without READ_CONTACTS.
 */
internal fun contactDisplayName(context: Context, uri: Uri): String? = try {
    context.contentResolver.query(
        uri,
        arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
        null, null, null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
    }
} catch (e: SecurityException) {
    Log.status("PeoplePage", "Could not read the picked contact's name", e)
    null
} catch (e: IllegalArgumentException) {
    Log.status("PeoplePage", "Could not read the picked contact's name", e)
    null
}

/**
 * The people grid, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FaceIndexingProgress(done: Int, total: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(R.string.face_indexing_progress, done, total),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { if (total > 0) done.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

@Composable
internal fun PeopleGrid(
    people: List<PersonCluster>,
    modifier: Modifier = Modifier,
    faceThumbnail: @Composable (PersonCluster, Modifier) -> Unit,
    onNameClick: (PersonCluster) -> Unit,
    onHideClick: (PersonCluster) -> Unit = {},
    onDeleteClick: (PersonCluster) -> Unit = {},
    onClick: (PersonCluster) -> Unit,
) {
    // Cluster whose overflow menu is open, and cluster awaiting the delete
    // confirmation — the same pending-state shape as the album delete flow.
    var menuPersonId by remember { mutableStateOf<Long?>(null) }
    var pendingDelete by remember { mutableStateOf<PersonCluster?>(null) }
    LazyVerticalGrid(
        columns = adaptiveGridCells(100.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize().padding(8.dp),
    ) {
        // Stable key = cluster id so Compose reuses items across re-emissions
        // instead of recreating (and re-loading) them during indexing.
        items(people, key = { it.id }) { person ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    faceThumbnail(
                        person,
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .invisibleClickable { onClick(person) },
                    )
                    Box(Modifier.align(Alignment.TopEnd)) {
                        IconButton(onClick = { menuPersonId = person.id }) {
                            IconMoreVert()
                        }
                        DropdownMenu(
                            expanded = menuPersonId == person.id,
                            onDismissRequest = { menuPersonId = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.people_name)) },
                                leadingIcon = { IconEdit() },
                                onClick = {
                                    menuPersonId = null
                                    onNameClick(person)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.people_hide)) },
                                leadingIcon = { IconVisibilityOff() },
                                onClick = {
                                    menuPersonId = null
                                    onHideClick(person)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.people_delete)) },
                                leadingIcon = { IconDelete() },
                                onClick = {
                                    menuPersonId = null
                                    pendingDelete = person
                                },
                            )
                        }
                    }
                }
                // The name is its own tap target: the avatar keeps opening the
                // person's photos, and tapping an existing name is how renaming
                // works (it just reopens the picker).
                Text(
                    text = person.name ?: stringResource(R.string.people_unnamed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .invisibleClickable { onNameClick(person) },
                )
                Text(
                    text = pluralStringResource(R.plurals.people_photo_count, person.photos.size, person.photos.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    pendingDelete?.let { person ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.people_delete)) },
            text = {
                Text(
                    stringResource(
                        R.string.people_delete_confirm,
                        person.name ?: stringResource(R.string.people_unnamed),
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDeleteClick(person)
                }) {
                    Text(stringResource(R.string.people_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** The representative face of a cluster, cropped from its cover photo. */
@Composable
internal fun FaceThumbnail(person: PersonCluster, modifier: Modifier) {
    val context = LocalContext.current
    // `size` downsamples at *decode* time and transformations run after it, so
    // asking for 256 would decode the whole photo to 256px and only then cut the
    // face out — a face spanning 15% of the frame would arrive as ~38px stretched
    // across a full-width circle. Scaling the request up by the box fraction puts
    // the cropped result near TARGET_FACE_PX instead; the clamp stops a tiny face
    // asking for an enormous decode.
    val boxFraction = minOf(person.faceRight - person.faceLeft, person.faceBottom - person.faceTop)
    val requestSize = remember(boxFraction) {
        if (boxFraction <= 0f) TARGET_FACE_PX
        else (TARGET_FACE_PX / boxFraction).toInt().coerceIn(TARGET_FACE_PX, MAX_FACE_DECODE_PX)
    }
    // The representative face changes as indexing finds a better one, so the
    // memory key has to carry the box and the decode size or a stale crop would be
    // served forever. The disk cache holds the *pre-transform* fetched bytes (the
    // original file, see ImageLoader), so its key is photo identity alone —
    // including the box or the size there would just store the same file twice.
    val diskKey = "face_src_${person.coverPhoto.id}_${person.coverPhoto.dateModified}"
    val memoryKey = "face_${person.id}_${diskKey}_${person.faceLeft}_${person.faceTop}_" +
        "${person.faceRight}_${person.faceBottom}_$requestSize"
    val request = remember(person.coverPhoto.uri, memoryKey, diskKey, requestSize) {
        ImageRequest.Builder(context)
            .data(person.coverPhoto.uri.toUri())
            .transformations(
                FaceCropTransformation(person.faceLeft, person.faceTop, person.faceRight, person.faceBottom)
            )
            .diskCacheKey(diskKey)
            .memoryCacheKey(memoryKey)
            .crossfade(false)
            .size(requestSize)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

/** Roughly the on-screen size of a face circle in the 3-column grid. */
private const val TARGET_FACE_PX = 256

/** Ceiling on the decode a small face may ask for, to bound memory and time. */
private const val MAX_FACE_DECODE_PX = 1536
