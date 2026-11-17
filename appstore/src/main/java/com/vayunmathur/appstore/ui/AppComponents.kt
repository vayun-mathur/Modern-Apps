package com.vayunmathur.appstore.ui
import com.vayunmathur.appstore.R
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.util.sharedCrop
import java.util.Locale

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text


/** Where an app came from. Purely factual — the sources are not ranked. */
@Composable
fun SourceChip(source: AppSource, modifier: Modifier = Modifier) {
    val label = stringResource(
        when (source) {
            AppSource.MODERN_APPS -> R.string.source_chip_modern_apps
            AppSource.FDROID -> R.string.source_chip_fdroid
            AppSource.IZZY -> R.string.source_chip_izzy
            AppSource.PLAYSTORE -> R.string.source_chip_play
            AppSource.ACCRESCENT -> R.string.source_chip_accrescent
            AppSource.PROPRIETARY -> R.string.source_chip_proprietary
        }
    )
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

