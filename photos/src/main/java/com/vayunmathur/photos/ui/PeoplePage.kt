package com.vayunmathur.photos.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.NavigationBar
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.ImageLoader
import com.vayunmathur.photos.util.PersonGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeoplePage(
    backStack: NavBackStack<Route>,
    galleryViewModel: GalleryViewModel,
) {
    val context = LocalContext.current
    val enabled by galleryViewModel.faceMatchEnabled.collectAsState()
    val people by galleryViewModel.people.collectAsState()

    // Only ask for READ_CONTACTS when the user opts in. If granted, enable the
    // feature; if denied, leave it off so the rest of the app is unaffected.
    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) galleryViewModel.setFaceMatchEnabled(true)
    }

    fun requestEnable() {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            galleryViewModel.setFaceMatchEnabled(true)
        } else {
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.label_people)) },
                actions = {
                    if (enabled) {
                        Switch(
                            checked = true,
                            onCheckedChange = { galleryViewModel.setFaceMatchEnabled(false) },
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }
                },
            )
        },
        bottomBar = { NavigationBar(Route.People, backStack) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (!enabled) {
                EnableFacesCard(onEnable = { requestEnable() })
            } else if (people.isEmpty()) {
                Text(
                    text = stringResource(R.string.people_empty),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
            } else {
                PeopleGrid(people) { person ->
                    backStack.add(Route.PhotoPage(person.coverPhoto.id, person.photos))
                }
            }
        }
    }
}

@Composable
private fun EnableFacesCard(onEnable: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painterResource(R.drawable.people_24px),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.match_faces_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = stringResource(R.string.match_faces_description),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onEnable, modifier = Modifier.padding(top = 24.dp)) {
            Text(stringResource(R.string.match_faces_enable))
        }
    }
}

@Composable
private fun PeopleGrid(people: List<PersonGroup>, onClick: (PersonGroup) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().padding(8.dp),
    ) {
        items(people, key = { it.name }) { person ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ImageLoader.PhotoItem(
                    photo = person.coverPhoto,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape),
                    onClick = { onClick(person) },
                )
                Text(
                    text = person.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = stringResource(R.string.people_photo_count, person.photos.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
