package com.vayunmathur.appstore.ui

import com.vayunmathur.appstore.platform.reason
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.sharedCrop
import java.util.Locale

/**
 * The icon, from whichever of the places actually has one.
 *
 * A locally installed app has a real launcher icon in PackageManager, which beats
 * re-downloading the listing's copy; a catalogue entry has a URL; a Play cluster row
 * sometimes has neither. Every screen needs the same fallback chain, so it lives here
 * rather than being written out again per call site.
 */
@Composable
fun AppIcon(
    app: UnifiedApp,
    installedIcon: Drawable?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    corner: Dp = 12.dp,
) {
    val shape = RoundedCornerShape(corner)
    val box = modifier.size(size).clip(shape)
    val bitmap = installedIcon?.let {
        runCatching {
            val px = (size.value * 2).toInt().coerceAtLeast(1)
            it.toBitmap(width = px, height = px).asImageBitmap()
        }.getOrNull()
    }
    when {
        bitmap != null -> Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = box,
            contentScale = ContentScale.Crop,
        )
        app.iconUrl != null -> AsyncImage(
            model = app.iconUrl,
            contentDescription = null,
            modifier = box,
            contentScale = ContentScale.Crop,
        )
        // No icon anywhere: an initial on a tinted tile reads better than a blank hole,
        // and keeps rows the same height whether or not the artwork loaded.
        else -> Box(
            box.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                app.name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
