package com.vayunmathur.appstore.ui
import com.vayunmathur.appstore.R

import android.graphics.drawable.Drawable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.sharedContainer

/**
 * A carousel tile: icon over name over rating, in a fixed-width column.
 *
 * Fixed width rather than intrinsic so that the tiles in a row line up regardless of how
 * long each app's name is — a carousel of ragged columns reads as broken.
 */
@Composable
fun AppTile(
    app: UnifiedApp,
    modifier: Modifier = Modifier,
    isInstalled: Boolean = false,
    stage: InstallStage? = null,
    installedIcon: Drawable? = null,
    /** As [AppRow]: null for a tile whose app is already an origin elsewhere on screen. */
    sharedKey: Any? = null,
    onClick: () -> Unit = {},
) {
    Column(
        modifier
            // Deliberately [sharedContainer] where [AppRow] uses [sharedCrop]: a tile centres its
            // icon in 96dp and the detail header puts it hard left in a full-width row, so the two
            // ends are not congruent at their top-left and a crop would jump the icon across. A
            // reflow absorbs that. Do not "tidy" this into matching AppRow.
            .then(if (sharedKey == null) Modifier else Modifier.sharedContainer(sharedKey))
            .width(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(app, installedIcon, size = 72.dp, corner = 18.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            app.name,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // Two lines' worth whatever the name's length, so tiles stay aligned.
            modifier = Modifier.heightIn(min = 32.dp),
        )
        // An in-flight install replaces the rating line rather than adding a third one, so a
        // tile mid-install stays roughly the height of its neighbours. The slot holds a label
        // line even when it has nothing to say, because most catalogue entries carry no rating
        // and RatingLabel then draws nothing at all — an installed tile would be a whole line
        // taller than the ones either side of it, and a LazyRow takes the height of its tallest
        // visible child, so the carousel and everything below it would move as those tiles
        // scrolled in.
        val statusHeight = with(LocalDensity.current) {
            MaterialTheme.typography.labelSmall.lineHeight.toDp()
        }
        Box(Modifier.heightIn(min = statusHeight), contentAlignment = Alignment.Center) {
            when {
                stage != null -> StageProgress(stage)
                isInstalled -> Text(
                    stringResource(R.string.installed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                else -> RatingLabel(app)
            }
        }
    }
}
