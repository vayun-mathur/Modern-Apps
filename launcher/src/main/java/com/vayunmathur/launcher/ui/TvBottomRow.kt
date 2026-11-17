package com.vayunmathur.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconDragHandle
import com.vayunmathur.library.ui.IconHdmi
import com.vayunmathur.library.ui.IconPackage
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.animatedFloat
import com.vayunmathur.launcher.domain.TvRowOrder
import com.vayunmathur.launcher.platform.TvHdmiInput
import com.vayunmathur.launcher.platform.TvHomeState

/**
 * The bottom glass row: App Store, HDMI inputs, Settings, Reorder (the tv-detox bottom row
 * with the store swapped for ours and Add replaced by the reorder toggle).
 *
 * Glass tiles with icon above label; focused tiles lift to ~1.1 like the banner grid.
 */
@Composable
fun TvBottomRow(
    state: TvHomeState,
    onOpenAppStore: () -> Unit,
    onOpenInput: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onToggleReorder: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.showAppStore) {
            TvGlassTile(
                label = "App Store",
                onClick = onOpenAppStore,
                modifier = Modifier.weight(1f),
            ) {
                IconPackage(modifier = Modifier.size(44.dp), tint = Color.White)
            }
        }
        for (input in state.hdmiInputs) {
            key(input.id) {
                TvHdmiTile(
                    input = input,
                    onClick = { onOpenInput(input.id) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        TvGlassTile(
            label = "Settings",
            onClick = onOpenSettings,
            modifier = Modifier.weight(1f),
        ) {
            IconSettings(modifier = Modifier.size(44.dp), tint = Color.White)
        }
        TvGlassTile(
            label = if (state.reorderArmed) "Done" else "Reorder",
            onClick = onToggleReorder,
            modifier = Modifier.weight(1f),
        ) {
            if (state.reorderArmed) {
                IconCheck(modifier = Modifier.size(44.dp), tint = Color.White)
            } else {
                IconDragHandle(modifier = Modifier.size(44.dp), tint = Color.White)
            }
        }
        // Keep the row's rhythm when fewer tiles show: spacers hold the width so the grid
        // above and this row stay aligned.
        val shown = (if (state.showAppStore) 1 else 0) + state.hdmiInputs.size + 2
        repeat((TvRowOrder.PER_ROW - shown).coerceAtLeast(0)) {
            Box(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TvHdmiTile(
    input: TvHdmiInput,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvGlassTile(
        label = input.label,
        onClick = onClick,
        modifier = modifier,
    ) {
        IconHdmi(modifier = Modifier.size(44.dp), tint = Color.White)
    }
}

@Composable
private fun TvGlassTile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // Same unmissable focus as the banner grid: lift + white ring.
    val scale = animatedFloat(if (focused) 1.15f else 1f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .height(112.dp)
            .scale(scale)
            .clip(MaterialTheme.shapes.largeIncreased)
            .background(Color(0x13FFFFFF))
            .then(
                if (focused) {
                    // Matches the banner grid: dark outer outline for contrast on light art,
                    // theme-primary inner ring for the accent.
                    Modifier
                        .border(
                            width = 6.dp,
                            color = Color.Black,
                            shape = MaterialTheme.shapes.largeIncreased,
                        )
                        .padding(6.dp)
                        .border(
                            width = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = MaterialTheme.shapes.largeIncreased,
                        )
                } else {
                    Modifier.border(
                        width = 2.dp,
                        color = Color(0x17FFFFFF),
                        shape = MaterialTheme.shapes.largeIncreased,
                    )
                },
            )
            .onFocusChanged { focused = it.isFocused }
            // No separate focusable(): clickable() provides focus handling — see TvAppGrid.
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xE0FFFFFF),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

