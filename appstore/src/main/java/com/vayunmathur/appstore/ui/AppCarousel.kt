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

/** One horizontally scrolling row of [AppTile]s. */
@Composable
fun AppCarousel(
    apps: List<UnifiedApp>,
    installedPackages: Set<String>,
    installedIcons: Map<String, Drawable>,
    onAppClick: (UnifiedApp) -> Unit,
    modifier: Modifier = Modifier,
    stages: Map<String, InstallStage> = emptyMap(),
    /** Per-app, because only the caller knows which copy of an app owns the morph. */
    sharedKey: (UnifiedApp) -> Any? = { null },
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(apps, key = { it.packageName }) { app ->
            AppTile(
                app = app,
                isInstalled = app.packageName in installedPackages,
                stage = stages[app.packageName],
                installedIcon = installedIcons[app.packageName],
                onClick = { onAppClick(app) },
                sharedKey = sharedKey(app),
            )
        }
    }
}
