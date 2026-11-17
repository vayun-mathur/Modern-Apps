package com.vayunmathur.launcher.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.vayunmathur.library.ui.IconApps
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.animatedFloat
import com.vayunmathur.launcher.domain.TvRowOrder
import com.vayunmathur.launcher.platform.TvTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Space under each tile reserved for the focus caption. */
val TvCaptionReserve = 28.dp

/**
 * One 16:9 banner tile.
 *
 * Three states, each unmistakable from a sofa:
 * - idle: art only, no ring, no caption;
 * - focused: black outer outline + white inner ring at the tile's own corner radius,
 *   caption underneath. The black outline is what reads on white banners; the white ring
 *   is what reads on the dark background;
 * - held (being moved): like focused but the ring is the theme primary, plus a MOVING
 *   badge — and every *other* tile dims, so the held one is the only bright thing on
 *   screen.
 *
 * Geometry never changes between states: the ring and badge are overlays and the caption
 * slot is always reserved, so focusing or picking up a tile cannot shift the list.
 *
 * OK handling is deliberately single-path: [clickable] → [onPress] → ViewModel `pressTile`
 * (pick up / drop / open depending on mode). There is no long-press: reorder mode already
 * disambiguates pick-up from open, and a hold would pick up on KeyDown then instantly drop
 * on the KeyUp click. The preview handler owns arrows, Back-passthrough and Menu only.
 */
@Composable
fun TvBannerTile(
    tile: TvTile,
    tileWidth: Dp,
    artHeight: Dp,
    moving: Boolean,
    dimmed: Boolean,
    focusRequester: FocusRequester?,
    onPress: () -> Unit,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // No scale on focus: scaling changes painted bounds and pushes neighbours' captions.
    // States read through an overlay ring + caption + dim instead. The art box has fixed
    // geometry; the ring is a matchParentSize overlay, so gaining or losing focus cannot
    // shift anything.
    val ringOn = focused || moving

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(tileWidth)
            .height(artHeight + TvCaptionReserve),
    ) {
        Box(
            modifier = Modifier
                .width(tileWidth)
                .height(artHeight)
                .clip(MaterialTheme.shapes.largeIncreased)
                .background(
                    if (tile.banner != null) Color(0xFF1E1A1C) else Color(0x13FFFFFF),
                )
                .alpha(animatedFloat(if (dimmed && !moving) 0.45f else 1f))
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                // No separate focusable(): clickable() provides focus handling, and a
                // standalone focusable() before it splits focus-grab and press into two OK
                // pushes on a D-pad (first press focuses, second clicks).
                .clickable(onClick = onPress)
                .onPreviewKeyEvent { event ->
                    if (moving) {
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> {
                                onMove(-1)
                                true
                            }
                            Key.DirectionRight -> {
                                onMove(1)
                                true
                            }
                            Key.DirectionUp -> {
                                onMove(-TvRowOrder.PER_ROW)
                                true
                            }
                            Key.DirectionDown -> {
                                onMove(TvRowOrder.PER_ROW)
                                true
                            }
                            // Back cancels the move; the screen's BackHandler owns it, so let
                            // it through rather than swallowing it here.
                            Key.Back -> false
                            // Swallow the rest while moving so stray keys cannot open menus.
                            // OK (Enter/DirectionCenter) is deliberately NOT handled here: the
                            // click above is the single drop path.
                            Key.Menu -> true
                            else -> false
                        }
                    } else {
                        if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Menu -> {
                                onEdit()
                                true
                            }
                            else -> false
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            TvBannerArt(tile = tile)
            // Focus ring as an overlay: black outer for white banners, white inner for the
            // dark background, both at the tile's own corner radius. Overlays never affect
            // measurement, so this cannot shift the list. Held tiles get the ring in the
            // theme primary so held vs merely focused read differently at a glance.
            if (ringOn) {
                val ringColor = if (moving) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.White
                }
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(2.dp)
                        .border(3.dp, Color.Black, MaterialTheme.shapes.largeIncreased)
                        .padding(2.dp)
                        .border(3.dp, ringColor, MaterialTheme.shapes.largeIncreased),
                )
            }
            // Held badge: unmistakable "you are carrying this" marker, overlaid so it adds
            // no geometry.
            if (moving) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(10.dp),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color.Black, MaterialTheme.shapes.small)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "MOVING",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }
            }
        }
        // Caption slot is always reserved; the label fades in rather than appearing, so
        // focusing a tile never changes this tile's (or any neighbour's) measured height.
        Box(
            modifier = Modifier
                .width(tileWidth)
                .height(TvCaptionReserve)
                .padding(top = 8.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = tile.label,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(animatedFloat(if (focused || moving) 1f else 0f)),
            )
        }
    }
}

@Composable
private fun TvBannerArt(tile: TvTile) {
    val banner = tile.banner
    if (banner != null) {
        val bitmap = rememberRaster(banner, 320, 180)
        if (bitmap != null) {
            Image(
                painter = BitmapPainter(bitmap),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(320.dp),
            )
        } else {
            TvGlassIcon(tile = tile)
        }
    } else {
        TvGlassIcon(tile = tile)
    }
}


/** Quiet glass tile for apps without TV banner art: icon above label. */
@Composable
private fun TvGlassIcon(tile: TvTile) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val icon: Drawable? = tile.appIcon
        if (icon != null) {
            val bitmap = rememberRaster(icon, 128, 128)
            if (bitmap != null) {
                Image(
                    painter = BitmapPainter(bitmap),
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                )
            } else {
                IconApps(modifier = Modifier.size(44.dp), tint = Color.White)
            }
        } else {
            IconApps(modifier = Modifier.size(44.dp), tint = Color.White)
        }
        Text(
            text = tile.label,
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xE0FFFFFF),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp),
        )
    }
}

