package com.vayunmathur.photos.ui

import android.app.WallpaperManager
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.FilledTonalButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SegmentedButton
import com.vayunmathur.library.ui.SingleChoiceSegmentedButtonRow
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.photos.R

/**
 * Bottom sheet controls: home/lock/both target, scrollable toggle, and the Set button.
 * The Set-button click still lives in [WallpaperPage] — it needs the coroutine scope,
 * WindowManager sizes, and snackbar host — so this takes an [onSetWallpaper] callback.
 */
@Composable
internal fun WallpaperControls(
    which: Int,
    onWhichChange: (Int) -> Unit,
    isScrollable: Boolean,
    onScrollableChange: (Boolean) -> Unit,
    canSet: Boolean,
    isSetting: Boolean,
    onSetWallpaper: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xFF121212),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // minSdk 31 — always show Home/Lock/Both
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth(),
            ) {
                SegmentedButton(
                    selected = which == WallpaperManager.FLAG_SYSTEM,
                    onClick = { onWhichChange(WallpaperManager.FLAG_SYSTEM) },
                    shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
                    label = { Text(stringResource(R.string.wallpaper_home)) },
                )
                SegmentedButton(
                    selected = which == WallpaperManager.FLAG_LOCK,
                    onClick = { onWhichChange(WallpaperManager.FLAG_LOCK) },
                    shape = RoundedCornerShape(0.dp),
                    label = { Text(stringResource(R.string.wallpaper_lock)) },
                )
                SegmentedButton(
                    selected = which ==
                        (WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK),
                    onClick = {
                        onWhichChange(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                    },
                    shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
                    label = { Text(stringResource(R.string.wallpaper_both)) },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.wallpaper_scrollable),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (isScrollable) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            stringResource(R.string.wallpaper_scrollable_desc),
                            color = Color.White.copy(alpha = 0.65f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = isScrollable,
                    onCheckedChange = onScrollableChange,
                )
            }

            FilledTonalButton(
                onClick = onSetWallpaper,
                modifier = Modifier.fillMaxWidth(),
                enabled = canSet && !isSetting,
            ) {
                if (isSetting) {
                    // Busy is encoded by the disabled button + label, no spinner icon.
                    Text(stringResource(R.string.setting_wallpaper))
                } else {
                    Text(stringResource(R.string.set_wallpaper))
                }
            }
        }
    }
}
