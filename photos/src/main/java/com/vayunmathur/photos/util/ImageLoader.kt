package com.vayunmathur.photos.util
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.IconPlayCircle
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.image.ImageLoader as LibImageLoader
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.DiskCache
import com.vayunmathur.library.image.MemoryCache
import com.vayunmathur.library.image.ImageRequest
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.util.sharedContainer
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.R

object ImageLoader {
    private const val VIDEO_FRAME_MILLIS = 1000L
    private const val THUMBNAIL_SIZE = 256

    @Volatile
    private var instance: LibImageLoader? = null

    /**
     * Built on first use rather than only from `MainActivity.onCreate`.
     *
     * [PhotoItem] is reached from entry points that never run MainActivity — the
     * home-screen widget, the assistant, an incoming ACTION_VIEW — where a
     * `lateinit` field threw `UninitializedPropertyAccessException`.
     */
    private fun loader(context: Context): LibImageLoader =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    /** Warms the loader during app start; not required for correctness. */
    fun init(context: Context) {
        loader(context)
    }

    private fun build(context: Context): LibImageLoader = LibImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder().build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("image_cache"))
                .build()
        }
        .respectCacheHeaders(false)
        .build()

    @Composable
    fun PhotoItem(photo: Photo, modifier: Modifier, onClick: (() -> Unit)? = null) {
        val context = LocalContext.current
        val isVideo = photo.videoData != null

        val clickableModifier = if(onClick != null) modifier.invisibleClickable(onClick) else modifier

        Box(
            modifier = clickableModifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.DarkGray),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(photo.uri.toUri())
                    .videoFrameMillis(VIDEO_FRAME_MILLIS)
                    .diskCacheKey("thumb_${photo.id}_${photo.dateModified}")
                    .memoryCacheKey("thumb_${photo.id}_${photo.dateModified}")
                    .crossfade(true)
                    .size(THUMBNAIL_SIZE)
                    .build(),
                imageLoader = loader(context),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            if (isVideo) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.4f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    IconPlayCircle(
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else if (photo.isGif) {
                Box(
                    modifier = Modifier
                        .background(
                            color = Color.Black.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = stringResource(R.string.gif_badge),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun SelectablePhotoItem(
        photo: Photo,
        isSelected: Boolean,
        isSelectionMode: Boolean,
        onToggleSelection: () -> Unit,
        onClick: () -> Unit,
        /**
         * How the tile paints its photo. A preview seam: the default decodes a MediaStore
         * URI through the shared loader, which Layoutlib cannot do, so the store-listing
         * previews substitute a placeholder here.
         */
        thumbnail: @Composable (Photo, Modifier) -> Unit = { p, m -> PhotoItem(p, m) },
        /**
         * Non-null makes this tile the origin of the container transform into the full-screen
         * viewer. Null for a tile that is a second copy of a photo already shown elsewhere on
         * screen - the morph cannot choose between two origins for one destination.
         */
        sharedKey: Any? = null,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onToggleSelection
                )
                .then(
                    if (sharedKey == null) Modifier
                    else Modifier.sharedContainer("photo-image-$sharedKey")
                )
        ) {
            thumbnail(photo, Modifier.fillMaxSize())

            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentAlignment = Alignment.TopStart
                ) {
                    if (isSelected) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        ) {
                            IconCheck(tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    } else {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.3f),
                            modifier = Modifier.size(24.dp)
                        ) {
                        }
                    }
                }
            }
        }
    }
}
