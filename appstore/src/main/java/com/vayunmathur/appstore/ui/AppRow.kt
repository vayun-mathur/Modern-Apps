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
 * A full-width list row: icon, name, summary, and whatever the caller puts on the right.
 *
 * [stage] draws its own progress bar underneath, so a row that is mid-install says which
 * part of the install it is in rather than showing a bar that sits at 100% through
 * verification and commit.
 */
@Composable
fun AppRow(
    app: UnifiedApp,
    modifier: Modifier = Modifier,
    isInstalled: Boolean = false,
    stage: InstallStage? = null,
    installedIcon: Drawable? = null,
    versionLabel: String? = null,
    /**
     * Non-null makes this row the origin of the [sharedCrop] container transform into the app's
     * detail page. Null for a row that is a second copy of an app already shown elsewhere on
     * screen - the morph cannot choose between two origins for one destination.
     */
    sharedKey: Any? = null,
    onClick: () -> Unit = {},
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier
            .then(if (sharedKey == null) Modifier else Modifier.sharedCrop(sharedKey))
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app, installedIcon, size = 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    app.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = app.summary.ifBlank { app.author.orEmpty() }
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SourceChip(app.source)
                    RatingLabel(app)
                    if (isInstalled) {
                        IconCheck(Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (versionLabel != null) {
                    Text(
                        versionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Old and new versions share one "a (1) → b (2)" line, and long
                        // version names ellipsized the tail — the new code — away (#813).
                        // Two lines keeps both codes visible.
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        StageProgress(stage, Modifier.padding(top = 8.dp))
    }
}
