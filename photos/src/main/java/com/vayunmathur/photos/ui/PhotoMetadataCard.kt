package com.vayunmathur.photos.ui

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconFavorite
import com.vayunmathur.library.ui.IconFavoriteBorder
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconWallpaper
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.Photo
import kotlin.time.Instant

/**
 * Bottom metadata overlay: name, date, location, resolution, size, people count,
 * and the favorite/wallpaper/edit/share/delete actions. Fades with the pager swipe distance.
 */
@Composable
internal fun PhotoMetadataCard(
    photo: Photo,
    context: Context,
    countryName: String?,
    fileSize: Long?,
    pageOffset: Float,
    peopleCount: Int = 0,
    isSphere: Boolean = false,
    isMotionPhoto: Boolean = false,
    onSetWallpaper: (Photo) -> Unit = {},
    onEditPhoto: () -> Unit,
    onDelete: (Photo) -> Unit = {},
    isFavorite: Boolean = false,
    onToggleFavorite: (Photo) -> Unit = {},
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .graphicsLayer {
                    // Keep the metadata fade-out tied to the swiping distance
                    alpha = 1f - pageOffset.coerceIn(0f, 1f)
                }
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(16.dp)
    ) {
        Text(
            text = photo.name,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge
        )

        val dateFormatted =
            remember(photo.date) {
                DateString.dateLong(Instant.fromEpochMilliseconds(photo.date))
            }

        Text(
            text = stringResource(R.string.taken_on, dateFormatted),
            color = Color.LightGray
        )
        if (photo.exifSet) {
            Text(
                text =
                    stringResource(
                        R.string.location,
                        countryName ?: stringResource(R.string.detecting)
                    ),
                color = Color.LightGray
            )
        }
        Text(
            text = stringResource(R.string.resolution, photo.width, photo.height),
            color = Color.LightGray
        )
        fileSize?.takeIf { it > 0 }?.let { bytes ->
            Text(
                text = stringResource(
                    R.string.file_size,
                    Formatter.formatShortFileSize(context, bytes)
                ),
                color = Color.LightGray
            )
        }
        if (photo.panoData != null) {
            Text(text = if (isSphere) "360°" else stringResource(R.string.panorama), color = Color.LightGray)
        }
        // Motion badge lives in the viewer metadata only: the grid shows no
        // motion marking, since detecting it needs per-file EXIF reads.
        if (isMotionPhoto) {
            Text(text = stringResource(R.string.motion_badge), color = Color.LightGray)
        }
        if (peopleCount > 0) {
            Text(
                text = pluralStringResource(R.plurals.people_in_photo, peopleCount, peopleCount),
                color = Color.LightGray
            )
        }

        Row(
            modifier = Modifier.align(Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onToggleFavorite(photo) }) {
                if (isFavorite) {
                    IconFavorite(tint = Color.White, contentDescription = stringResource(R.string.action_unfavorite))
                } else {
                    IconFavoriteBorder(tint = Color.White, contentDescription = stringResource(R.string.action_favorite))
                }
            }
            IconButton(onClick = { onSetWallpaper(photo) }) {
                IconWallpaper(tint = Color.White)
            }
            IconButton(onClick = onEditPhoto) { IconEdit(tint = Color.White) }
            IconButton(
                onClick = {
                    val intent =
                        Intent(Intent.ACTION_SEND).apply {
                            type =
                                if (photo.videoData != null) "video/*"
                                else "image/*"
                            putExtra(Intent.EXTRA_STREAM, photo.uri.toUri())
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    ExternalIntents.launch(context, Intent.createChooser(intent, context.getString(UiR.string.share)))
                }
            ) { IconShare(tint = Color.White) }
            IconButton(onClick = { onDelete(photo) }) {
                IconDelete(tint = Color.White)
            }
        }
    }
}
