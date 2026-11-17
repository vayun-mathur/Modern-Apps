// detekt's TooManyFunctions counts per file; the renderers below are one per template kind
// plus the shared blocks they all use (TemplateListBody, ActionRow, ArtworkTile). Splitting
// them into separate files would scatter a single cohesive renderer. FileLength still caps size.
@file:Suppress("TooManyFunctions")

package com.vayunmathur.library.carhost

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.car.app.model.Template
import androidx.car.app.model.TemplateWrapper
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/**
 * Renders a parsed [HostTemplate] the way MA Auto's head unit draws it, styled to
 * match Android Auto's list/grid/now-playing conventions.
 *
 * Extracted from the `:auto` app so any module (the head unit, or an app's
 * screenshot test) can render a car view without depending on `:auto`. Two
 * couplings the app used to own are parameters: the [template] is passed in
 * directly, and the map surface is a [mapContent] slot (`:auto` supplies its real
 * `TextureView` island; headless previews get the flat placeholder).
 *
 * Template dispatch plus one renderer per template kind; the shared blocks below
 * (TemplateListBody, ActionRow, ArtworkTile) are used by most renderers, so moving
 * them out would scatter the file's single concern. FileLength (fatal lint)
 * still caps file size.
 */
@Composable
fun CarTemplateView(
    template: HostTemplate?,
    modifier: Modifier = Modifier,
    mapContent: @Composable (Modifier) -> Unit = { CarMapPlaceholder(it) },
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.padding(CAR_PADDING)) {
            when (template) {
                is HostTemplate.Navigation -> NavigationRenderer(template, mapContent)
                is HostTemplate.PlaceList -> PlaceListRenderer(template)
                is HostTemplate.TemplateList -> ListRenderer(template)
                is HostTemplate.Grid -> GridRenderer(template)
                is HostTemplate.Pane -> PaneRenderer(template)
                is HostTemplate.Search -> SearchRenderer(template)
                is HostTemplate.Message -> MessageRenderer(template)
                else -> RenderSecondaryTemplate(template, mapContent)
            }
        }
    }
}

/** Second half of the template dispatch, split so each `when` stays under the complexity cap. */
@Composable
private fun RenderSecondaryTemplate(
    template: HostTemplate?,
    mapContent: @Composable (Modifier) -> Unit,
) {
    when (template) {
        is HostTemplate.Tabs -> TabsRenderer(template, mapContent)
        is HostTemplate.MediaPlayback -> MediaPlaybackRenderer(template)
        is HostTemplate.SignIn -> SignInRenderer(template)
        is HostTemplate.InCall -> InCallRenderer(template)
        is HostTemplate.Keypad -> KeypadRenderer(template)
        is HostTemplate.MapWithContent -> MapWithContentRenderer(template, mapContent)
        is HostTemplate.LegacyNav -> LegacyNavRenderer(template, mapContent)
        null -> LoadingRenderer()
        else -> Unit
    }
}

/**
 * Convenience entry: parse a real car-app [Template] and render it. Used by
 * screenshot tests that build representative Templates with the same builders the
 * screens use, so the shots track the real component choices.
 */
@Composable
fun CarTemplateView(
    carTemplate: Template,
    modifier: Modifier = Modifier,
    mapContent: @Composable (Modifier) -> Unit = { CarMapPlaceholder(it) },
) {
    val parsed = remember(carTemplate) {
        HostTemplateParsers.parse(TemplateWrapper.wrap(carTemplate))
    }
    CarTemplateView(parsed, modifier, mapContent)
}

/** Flat stand-in for the app's map surface (Layoutlib has no GL renderer). */
@Composable
fun CarMapPlaceholder(modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant) {}
}

// ── Renderers ──────────────────────────────────────────────────────────────

@Composable
private fun NavigationRenderer(
    template: HostTemplate.Navigation,
    mapContent: @Composable (Modifier) -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(CAR_GAP)) {
        ManeuverCard(template)
        mapContent(Modifier.fillMaxWidth().weight(1f).clip(CAR_SHAPE))
        if (template.loading) LoadingRow()
        ActionRow(template.actions)
    }
}

@Composable
private fun ManeuverCard(template: HostTemplate.Navigation) {
    val cue = template.cue ?: template.road ?: "Navigating"
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(CAR_GAP),
            horizontalArrangement = Arrangement.spacedBy(CAR_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            template.distanceText?.let {
                Text(it, style = MaterialTheme.typography.headlineMedium)
            }
            Column(Modifier.weight(1f)) {
                Text(cue, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                template.road?.takeIf { it != cue }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                template.lanesText?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium)
                }
            }
            template.etaText?.let {
                InfoChip(it)
            }
        }
    }
}

@Composable
private fun PlaceListRenderer(template: HostTemplate.PlaceList) {
    TemplateListBody(template.title, listOf(HostUiSection(rows = template.rows)), template.loading, template.actions)
}

@Composable
private fun ListRenderer(template: HostTemplate.TemplateList, modifier: Modifier = Modifier) {
    TemplateListBody(template.title, template.sections, template.loading, template.actions, modifier)
}

@Composable
private fun GridRenderer(template: HostTemplate.Grid, modifier: Modifier = Modifier) {
    val tiles = template.items.map { Tile(it.image, it.title, it.text, it.loading, it.onClick) }
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(CAR_GAP)) {
        template.title?.let { item { ScreenTitle(it) } }
        gridRows(tiles)
        if (template.loading) item { LoadingRow() }
        if (template.actions.isNotEmpty()) item { ActionRow(template.actions) }
    }
}

@Composable
private fun PaneRenderer(template: HostTemplate.Pane) {
    TemplateListBody(template.title, listOf(HostUiSection(rows = template.rows)), template.loading, template.actions)
}

@Composable
private fun SearchRenderer(template: HostTemplate.Search, modifier: Modifier = Modifier) {
    var text by remember(template.initialText) { mutableStateOf(template.initialText) }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(CAR_GAP)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; template.onSearchTextChanged?.invoke(it) },
            label = { Text(template.hint ?: "Search") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { template.onSearchSubmitted?.invoke(text) }),
            modifier = Modifier.fillMaxWidth(),
        )
        TemplateListBody(
            null,
            listOf(HostUiSection(rows = template.rows)),
            template.loading,
            template.actions,
            Modifier.weight(1f)
        )
    }
}

@Composable
private fun MessageRenderer(template: HostTemplate.Message, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(CAR_GAP)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(CAR_GAP), verticalArrangement = Arrangement.spacedBy(CAR_GAP)) {
                template.title?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
                template.message?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                if (template.loading) LoadingRow()
            }
        }
        ActionRow(template.actions)
    }
}

@Composable
private fun TabsRenderer(
    template: HostTemplate.Tabs,
    mapContent: @Composable (Modifier) -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(CAR_GAP)) {
        TabBar(template)
        RenderInner(template.content, mapContent, template.loading, Modifier.weight(1f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TabBar(template: HostTemplate.Tabs) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CAR_GAP)) {
        for (tab in template.tabs) {
            val selected = tab.contentId == template.activeContentId
            val label = tab.title ?: tab.contentId
            if (selected) {
                Button(onClick = {}) { Text(label) }
            } else {
                TextButton(onClick = { template.onTabSelected?.invoke(tab.contentId) }) { Text(label) }
            }
        }
    }
}

@Composable
private fun MediaPlaybackRenderer(template: HostTemplate.MediaPlayback, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CAR_GAP, Alignment.CenterVertically),
    ) {
        ArtworkTile(template.image, Modifier.size(MEDIA_ARTWORK_DP.dp), CAR_SHAPE)
        Text(
            template.title ?: "Now playing",
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(horizontalArrangement = Arrangement.spacedBy(CAR_GAP), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {}) { Text("⏮") }
            Button(onClick = {}) { Text("▶  Play") }
            TextButton(onClick = {}) { Text("⏭") }
        }
        if (template.loading) LoadingRow()
    }
}

@Composable
private fun SignInRenderer(template: HostTemplate.SignIn, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(CAR_GAP)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(CAR_GAP), verticalArrangement = Arrangement.spacedBy(CAR_GAP)) {
                template.title?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
                template.message?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                if (template.loading) LoadingRow()
            }
        }
        ActionRow(template.actions)
    }
}

@Composable
private fun InCallRenderer(template: HostTemplate.InCall, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CAR_GAP, Alignment.CenterVertically),
    ) {
        ArtworkTile(null, Modifier.size(120.dp), CircleShapeLike)
        template.title?.let { Text(it, style = MaterialTheme.typography.headlineMedium, maxLines = 1) }
        for (line in template.texts) Text(line, style = MaterialTheme.typography.bodyLarge)
        if (template.loading) LoadingRow()
        ActionRow(template.actions)
    }
}

@Composable
private fun KeypadRenderer(template: HostTemplate.Keypad, modifier: Modifier = Modifier) {
    var text by remember(template.phoneNumber) { mutableStateOf(template.phoneNumber) }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(CAR_GAP)) {
        template.title?.let { ScreenTitle(it) }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; template.onNumberChanged?.invoke(it) },
            label = { Text("Number") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        template.onPrimaryAction?.let { primary ->
            Button(onClick = primary, modifier = Modifier.fillMaxWidth()) { Text(template.primaryTitle ?: "Call") }
        }
    }
}

@Composable
private fun MapWithContentRenderer(
    template: HostTemplate.MapWithContent,
    mapContent: @Composable (Modifier) -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(CAR_GAP)) {
        mapContent(Modifier.fillMaxWidth().weight(1f).clip(CAR_SHAPE))
        RenderInner(template.content, mapContent, loading = false, Modifier.weight(1f))
        ActionRow(template.actions)
    }
}

@Composable
private fun LegacyNavRenderer(
    template: HostTemplate.LegacyNav,
    mapContent: @Composable (Modifier) -> Unit,
) {
    val s = template.state
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(CAR_GAP)) {
        ManeuverCard(
            HostTemplate.Navigation(
                cue = s.cue, road = s.road, distanceText = s.distanceText,
                etaText = s.etaText, lanesText = s.lanesText,
            )
        )
        mapContent(Modifier.fillMaxWidth().weight(1f).clip(CAR_SHAPE))
        Row(horizontalArrangement = Arrangement.spacedBy(CAR_GAP)) {
            for (a in s.actions) TextButton(onClick = a.onClick) { Text(a.title) }
        }
    }
}

@Composable
private fun LoadingRenderer() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

// ── Shared building blocks ───────────────────────────────────────────────────

@Composable
private fun RenderInner(
    content: HostTemplate?,
    mapContent: @Composable (Modifier) -> Unit,
    loading: Boolean,
    modifier: Modifier,
) {
    when (content) {
        is HostTemplate.TemplateList -> ListRenderer(content, modifier)
        is HostTemplate.Grid -> GridRenderer(content, modifier)
        is HostTemplate.Pane -> PaneRenderer(content)
        is HostTemplate.Message -> MessageRenderer(content, modifier)
        is HostTemplate.Search -> SearchRenderer(content, modifier)
        is HostTemplate.Navigation -> NavigationRenderer(content, mapContent)
        null -> if (loading) {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        else -> Unit
    }
}

@Composable
private fun TemplateListBody(
    title: String?,
    sections: List<HostUiSection>,
    loading: Boolean,
    actions: List<HostUiAction>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(CAR_ROW_GAP)) {
        title?.let { item { ScreenTitle(it) } }
        for (section in sections) {
            section.header?.let { header -> item { SectionHeader(header) } }
            when {
                section.chips -> item { ChipStrip(section.rows) }
                section.grid -> gridRows(
                    section.rows.map {
                        Tile(it.image, it.title, it.texts.firstOrNull(), false, it.onClick)
                    }
                )
                else -> items(section.rows, key = { it.title }) { TemplateRow(it) }
            }
        }
        if (loading) item { LoadingRow() }
        if (actions.isNotEmpty()) item { ActionRow(actions) }
    }
}

/** Emits chunked rows of grid tiles as plain [LazyColumn] items (no nested scroll). */
private fun androidx.compose.foundation.lazy.LazyListScope.gridRows(tiles: List<Tile>) {
    tiles.chunked(GRID_COLUMNS).forEach { chunk ->
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CAR_GAP)) {
                for (tile in chunk) TileCell(tile, Modifier.weight(1f))
                repeat(GRID_COLUMNS - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A grid tile: square artwork over a title + optional subtitle. */
@Composable
private fun TileCell(tile: Tile, modifier: Modifier = Modifier) {
    val click = tile.onClick
    Column(
        modifier
            .let { if (click != null) it.clickable(onClick = click) else it }
            .padding(bottom = CAR_GAP),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            ArtworkTile(tile.image, Modifier.fillMaxSize(), CAR_SHAPE)
            if (tile.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        Text(tile.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        tile.subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipStrip(rows: List<HostUiRow>) {
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(CAR_GAP),
        verticalArrangement = Arrangement.spacedBy(CAR_GAP),
    ) {
        for (row in rows) {
            val click = row.onClick
            Surface(
                onClick = { click?.invoke() },
                enabled = click != null,
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TemplateRow(row: HostUiRow) {
    val click = row.onClick
    ListItem(
        headlineContent = { Text(row.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            if (row.texts.isNotEmpty()) {
                Text(row.texts.joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        leadingContent = row.image?.let { { ArtworkTile(it, Modifier.size(56.dp), CAR_SHAPE, crop = false) } },
        trailingContent = {
            if (click != null) TextButton(onClick = click) { Text(if (row.browse) "›" else "Open") }
        },
    )
}

/** Resolves an [IconCompat] to a painter, or a themed placeholder box when absent. */
@Composable
private fun ArtworkTile(
    icon: IconCompat?,
    modifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape,
    crop: Boolean = true,
) {
    val painter: Painter? = when {
        icon == null -> null
        // Resource icons render natively (vectors included) even under Layoutlib.
        icon.type == IconCompat.TYPE_RESOURCE ->
            runCatching { icon.resId }.getOrNull()?.takeIf { it != 0 }
                ?.let { androidx.compose.ui.res.painterResource(it) }
        // Content-URI / bitmap art (device) resolves via loadDrawable.
        else -> rememberIconPainter(icon)
    }
    if (painter != null) {
        // Album art fills its tile (Crop); row glyphs/avatars show whole (Fit).
        val scale = if (crop) ContentScale.Crop else ContentScale.Fit
        Image(painter, contentDescription = null, contentScale = scale, modifier = modifier.clip(shape))
    } else {
        Box(
            modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text("♪", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
private fun rememberIconPainter(icon: IconCompat?): Painter? {
    val context = LocalContext.current
    // LocalResources is configuration-aware; LocalContext.current.resources can go stale.
    val resources = LocalResources.current
    return remember(icon, resources) {
        if (icon == null) return@remember null
        runCatching {
            // loadDrawable resolves content-URI/bitmap art on-device; under Layoutlib
            // it returns null for resources, so fall back to the resource directly.
            val d = icon.loadDrawable(context)
                ?: if (icon.type == IconCompat.TYPE_RESOURCE) {
                    androidx.core.content.res.ResourcesCompat.getDrawable(
                        resources, icon.resId, context.theme,
                    )
                } else {
                    null
                }
                ?: return@runCatching null
            val bmp = if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) d.toBitmap() else d.toBitmap(256, 256)
            BitmapPainter(bmp.asImageBitmap())
        }.getOrNull()
    }
}

@Composable
private fun ScreenTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = CAR_GAP, bottom = 2.dp),
    )
}

@Composable
private fun InfoChip(text: String) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.tertiaryContainer) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun LoadingRow() {
    Row(Modifier.fillMaxWidth().padding(CAR_GAP), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ActionRow(actions: List<HostUiAction>) {
    if (actions.isEmpty()) return
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(CAR_GAP)) {
        for (action in actions) Button(onClick = action.onClick) { Text(action.title) }
    }
}

/** Flattened tile descriptor shared by the Grid template and grid sections. */
private data class Tile(
    val image: IconCompat?,
    val title: String,
    val subtitle: String?,
    val loading: Boolean,
    val onClick: (() -> Unit)?,
)

private val CAR_PADDING = 16.dp
private val CAR_GAP = 12.dp
private val CAR_ROW_GAP = 4.dp
private val CAR_SHAPE = RoundedCornerShape(12.dp)
private val CircleShapeLike = RoundedCornerShape(percent = 50)
private const val GRID_COLUMNS = 3
private const val MEDIA_ARTWORK_DP = 220
