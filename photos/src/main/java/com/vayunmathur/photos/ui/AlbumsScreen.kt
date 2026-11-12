package com.vayunmathur.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.res.pluralStringResource
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconFavoriteBorder
import com.vayunmathur.library.ui.IconLock
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
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
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.NavigationBar
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.util.Album
import com.vayunmathur.photos.util.AlbumsUiState
import com.vayunmathur.photos.util.DefaultAlbum
import com.vayunmathur.photos.util.DefaultAlbumKind

/**
 * The albums grid, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 *
 * Built-in collections (Favorites, Trash, Secure Folder) render first as icon
 * tiles; Favorites shows its newest photo as the cover once it has one. Trash
 * and the Secure Folder open the same pages the bottom nav used to hold.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    backStack: NavBackStack<Route>,
    state: AlbumsUiState,
    /**
     * How an album tile paints its cover photo. Previews pass a placeholder,
     * because Layoutlib has neither MediaStore nor the ImageLoader singleton
     * MainActivity initialises.
     */
    thumbnail: @Composable (Photo, Modifier) -> Unit = { photo, modifier -> AlbumCover(photo, modifier) },
) {
    AppScaffold(
        title = stringResource(R.string.label_albums),
        bottomBar = { NavigationBar(Route.Albums, backStack) },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(padding).padding(8.dp),
        ) {
            // Stable key = kind so Compose reuses tiles across re-emissions
            // instead of recreating them during syncs.
            items(state.defaultAlbums, key = { it.kind }) { album ->
                DefaultAlbumTile(
                    album = album,
                    thumbnail = thumbnail,
                    onClick = {
                        backStack.add(
                            when (album.kind) {
                                DefaultAlbumKind.FAVORITES -> Route.Favorites
                                DefaultAlbumKind.TRASH -> Route.Trash
                                DefaultAlbumKind.SECURE_FOLDER -> Route.SecureFolder
                            }
                        )
                    },
                )
            }
            if (state.albums.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(R.string.albums_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
                    )
                }
            } else {
                // Stable key = album name so Compose reuses tiles across re-emissions
                // instead of recreating (and re-loading) them during syncs.
                items(state.albums, key = { it.name }) { album ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .invisibleClickable { backStack.add(Route.AlbumDetail(album.name)) },
                        ) {
                            thumbnail(album.coverPhoto, Modifier.fillMaxSize())
                        }
                        Text(
                            text = albumDisplayName(album),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            text = pluralStringResource(R.plurals.album_photo_count, album.count, album.count),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A built-in collection tile: the collection's icon on a tonal surface (or its
 * [DefaultAlbum.cover], when it has one), with the same name/count caption as
 * an album.
 */
@Composable
private fun DefaultAlbumTile(
    album: DefaultAlbum,
    thumbnail: @Composable (Photo, Modifier) -> Unit,
    onClick: () -> Unit,
) {
    val title = when (album.kind) {
        DefaultAlbumKind.FAVORITES -> stringResource(R.string.label_favorites)
        DefaultAlbumKind.TRASH -> stringResource(R.string.label_trash)
        DefaultAlbumKind.SECURE_FOLDER -> stringResource(R.string.label_secure_folder)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .invisibleClickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val cover = album.cover
            when {
                cover != null -> thumbnail(cover, Modifier.fillMaxSize())
                album.kind == DefaultAlbumKind.FAVORITES -> IconFavoriteBorder(
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    contentDescription = null,
                )
                album.kind == DefaultAlbumKind.TRASH -> IconDelete(
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> IconLock(
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        // A null count hides the line (the locked vault stays private).
        album.count?.let { count ->
            Text(
                text = pluralStringResource(R.plurals.album_photo_count, count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The user-visible album name; the unknown-bucket sentinel shows R.string.albums_unknown. */
@Composable
private fun albumDisplayName(album: Album): String =
    if (album.name == Album.UNKNOWN_NAME) stringResource(R.string.albums_unknown) else album.name

/** An album's cover: the newest photo in the bucket, cropped square. */
@Composable
private fun AlbumCover(photo: Photo, modifier: Modifier) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(photo.uri.toUri())
            .videoFrameMillis(1000)
            .diskCacheKey("thumb_${photo.id}_${photo.dateModified}")
            .memoryCacheKey("thumb_${photo.id}_${photo.dateModified}")
            .crossfade(false)
            .size(512)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}
