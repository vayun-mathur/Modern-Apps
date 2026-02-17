package com.vayunmathur.photos.glance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import com.vayunmathur.library.ui.DynamicThemeGlance
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class PhotoGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {

        val db = context.buildDatabase<PhotoDatabase>()
        val viewModel = DatabaseViewModel(db, Photo::class to db.photoDao())
        val photos = viewModel.getAll<Photo>()

        provideContent {
            var photo by remember(photos) { mutableStateOf(photos.filter{it.videoData == null}.randomOrNull()) }
            val bitmap by produceState<Bitmap?>(initialValue = null, photo) {
                value = withContext(Dispatchers.IO) {
                    photo?.let { getResizedBitmap(context, it.uri.toUri(), 600) }
                }
            }
            DynamicThemeGlance(context) {
                bitmap?.let {
                    Content(it) { photo = photos.randomOrNull() }
                }
            }
        }
    }
}

@Composable
fun Content(bitmap: Bitmap, newPhoto: () -> Unit) {
    Box(
        modifier = GlanceModifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = null,
            modifier = GlanceModifier
                .fillMaxSize()
                .clickable { newPhoto() },
            contentScale = ContentScale.Crop
        )
    }
}

fun getResizedBitmap(context: Context, uri: Uri, maxSize: Int = 600): Bitmap? {
    return try {
        val contentResolver = context.contentResolver

        // 1. Get dimensions only (no memory used for pixels)
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }

        // 2. Calculate the sample size (power of 2)
        options.inSampleSize = calculateInSampleSize(options, maxSize, maxSize)
        options.inJustDecodeBounds = false

        // 3. Decode the downsampled bitmap
        val downsampledBitmap = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        downsampledBitmap
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height: Int, width: Int) = options.outHeight to options.outWidth
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}