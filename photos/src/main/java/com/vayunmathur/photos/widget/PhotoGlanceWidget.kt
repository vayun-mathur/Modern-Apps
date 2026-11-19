package com.vayunmathur.photos.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.vayunmathur.library.log.Log
import androidx.compose.runtime.Composable
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
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import com.vayunmathur.library.widgets.MaWidget
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.PhotoScanRepository
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.vayunmathur.library.widgets.MaWidgetReceiver

/** Photo Glance Widget: a ma widget implementation for photo glance widget. */
class PhotoGlanceWidget : MaWidget<List<String>>() {
    override suspend fun loadData(context: Context, id: GlanceId): List<String> =
        PhotoScanRepository.get(context.applicationContext).getStillPhotoUris().map { it.toString() }

    override fun previewData(context: Context): List<String> = emptyList()

    @Composable
    override fun Content(data: List<String>) {
        val uris = data.map { it.toUri() }
        var uri: Uri? by remember(uris) { mutableStateOf(uris.randomOrNull()) }
        val context = LocalContext.current
        val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
            value = withContext(Dispatchers.IO) {
                uri?.let { getResizedBitmap(context, it, 600) }
            }
        }
        bitmap?.let {
            Content(it) { uri = uris.randomOrNull() }
        }
    }

    @Composable
    override fun PreviewContent() {
        Box(
            modifier = GlanceModifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(R.drawable.widget_preview_photo),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
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
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    } catch (e: IOException) {
        Log.error("PhotoWidget", "getResizedBitmap failed for $uri", e)
        null
    } catch (e: SecurityException) {
        Log.error("PhotoWidget", "getResizedBitmap failed for $uri", e)
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

/** Photo Glance Widget Receiver: a ma widget receiver implementation for photo glance widget receiver. */
class PhotoGlanceWidgetReceiver : MaWidgetReceiver<PhotoGlanceWidget>(
    widgetClass = PhotoGlanceWidget::class,
    receiverClass = PhotoGlanceWidgetReceiver::class,
    widgetFactory = ::PhotoGlanceWidget,
)
