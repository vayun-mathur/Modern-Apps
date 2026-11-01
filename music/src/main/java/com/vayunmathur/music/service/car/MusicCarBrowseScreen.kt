package com.vayunmathur.music.service.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridSection
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.RowSection
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.MediaItem
import com.vayunmathur.music.R
import com.vayunmathur.music.service.MusicLibraryTree

/**
 * One browse level: the tree node [nodeId] rendered read-only.
 *
 * Folders push a deeper [MusicCarBrowseScreen]; playable leaves resolve
 * through [MusicLibraryTree.resolveForPlayback] and play on the shared
 * session, then open [MusicCarPlaybackScreen]. A header play action plays
 * everything listed. Long lists stay parked-safe: rows are plain text,
 * artwork loads host-side from the item URIs.
 *
 * Component choice (car design guide):
 * - Root uses [TabTemplate] (API 6+): the five library tabs are exactly what
 *   tabs are for. Each tab's content is a [SectionedItemTemplate] (API 8+) of
 *   condensed song rows so more fits on screen; older hosts get [ListTemplate].
 * - Deeper levels use [SectionedItemTemplate] with one [RowSection] per
 *   grouping; the list fallback covers API 1–7 hosts.
 * - The Play-all affordance is a single FAB (media apps get max 1 on API 9+).
 */
class MusicCarBrowseScreen(
    carContext: CarContext,
    private val nodeId: String,
    private val state: MusicCarState,
) : Screen(carContext) {

    private companion object {
        const val MIN_API_TABS = 6
        const val MIN_API_SECTIONED = 8
        const val MIN_API_CHIPS = 9
    }

    init {
        state.observe { invalidate() }
    }

    override fun onGetTemplate(): Template {
        // Root: tabs over sectioned content (API 6+). Fall back to the plain
        // list when the host is too old for tabs.
        if (nodeId == MusicLibraryTree.ROOT && carContext.getCarAppApiLevel() >= MIN_API_TABS) {
            runCatching { return tabTemplate() }
        }
        // API 8+: sectioned content with a real section header per grouping.
        if (carContext.getCarAppApiLevel() >= MIN_API_SECTIONED) {
            runCatching { return sectionedTemplate() }
        }
        return legacyListTemplate()
    }

    // ------------------------------------------------------------------
    // TabTemplate (root, API 6+)
    // ------------------------------------------------------------------

    /** Tab ids in display order with their labels. */
    private fun rootTabs(): List<Pair<String, String>> {
        val tree = state.tree
        return listOf(
            MusicLibraryTree.TAB_PLAYLISTS,
            MusicLibraryTree.TAB_ALBUMS,
            MusicLibraryTree.TAB_ARTISTS,
            MusicLibraryTree.TAB_SONGS,
            MusicLibraryTree.TAB_RECENT,
        ).map { id -> id to (tree.item(id)?.mediaMetadata?.title?.toString() ?: id) }
    }

    @Suppress("DEPRECATION")
    private fun tabTemplate(): Template {
        val tabs = rootTabs()
        val active = activeTabId(tabs)
        val content: Template = if (carContext.getCarAppApiLevel() >= MIN_API_SECTIONED) {
            sectionedContent(active, tabs.toMap()[active] ?: active)
        } else {
            listContent(active, tabs.toMap()[active] ?: active)
        }
        val builder = TabTemplate.Builder(
            object : TabTemplate.TabCallback {
                override fun onTabSelected(tabContentId: String) {
                    screenManager.push(MusicCarBrowseScreen(carContext, tabContentId, state))
                }
            },
        )
        for ((id, label) in tabs) {
            builder.addTab(
                Tab.Builder()
                    .setTitle(label)
                    .setContentId(id)
                    .build(),
            )
        }
        return builder
            .setTabContents(TabContents.Builder(content).build())
            .setActiveTabContentId(active)
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    /** The tab the user drilled from, or Songs at root. */
    private fun activeTabId(tabs: List<Pair<String, String>>): String {
        val top = screenManager.screenStack.toList()
        val parent = if (top.size >= 2) top[top.size - 2] else null
        val id = (parent as? MusicCarBrowseScreen)?.nodeId
        return id?.takeIf { tabs.any { (tabId, _) -> tabId == it } } ?: MusicLibraryTree.TAB_SONGS
    }

    // ------------------------------------------------------------------
    // SectionedItemTemplate (API 8+)
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun sectionedTemplate(): Template {
        val tree = state.tree
        val title = tree.item(nodeId)?.mediaMetadata?.title?.toString() ?: "Music"
        val builder = SectionedItemTemplate.Builder()
        // API 9+: ChipSection first — quick actions (shuffle all, play all).
        // Chips are for exactly this: compact filter/trigger actions.
        if (carContext.getCarAppApiLevel() >= MIN_API_CHIPS) {
            runCatching { builder.addSection(quickActionChips()) }
        }
        return builder
            .addSection(contentSection(nodeId, title, tree.children(nodeId)))
            .setHeader(
                Header.Builder()
                    .setStartHeaderAction(Action.BACK)
                    .setTitle(title)
                    .build(),
            )
            .apply { playAllAction()?.let { addAction(it) } }
            .build()
    }

    /** Quick-action chips: shuffle-all and play-all. */
    @Suppress("DEPRECATION")
    private fun quickActionChips(): androidx.car.app.model.ChipSection {
        val items = state.tree.children(nodeId)
        val section = androidx.car.app.model.ChipSection.Builder()
        section.addItem(
            androidx.car.app.model.Chip.Builder()
                .setTitle("Shuffle all")
                .setOnClickListener { shuffleAll(items.map { it.mediaId }) }
                .build(),
        )
        if (items.any { it.mediaMetadata.isPlayable == true && it.mediaMetadata.isBrowsable != true }) {
            section.addItem(
                androidx.car.app.model.Chip.Builder()
                    .setTitle("Play all")
                    .setOnClickListener { playAll(items.map { it.mediaId }) }
                    .build(),
            )
        }
        return section.build()
    }

    private fun shuffleAll(mediaIds: List<String>) {
        val playables = mediaIds.mapNotNull { state.tree.resolveForPlayback(it) }.shuffled()
        if (playables.isEmpty()) return
        val controller = state.controller ?: return
        controller.setMediaItems(playables)
        controller.prepare()
        controller.play()
        state.tree.markPlayed(mediaIds)
        screenManager.push(MusicCarPlaybackScreen(carContext, state))
    }

    private fun sectionedContent(tabId: String, label: String): Template {
        val tree = state.tree
        return SectionedItemTemplate.Builder()
            .addSection(contentSection(tabId, label, tree.children(tabId)))
            .build()
    }

    /**
     * A grouping's section: albums render as an artwork [GridSection] (the AA
     * album-grid look); everything else stays a condensed [RowSection] list.
     */
    @Suppress("DEPRECATION")
    private fun contentSection(
        id: String,
        title: String,
        items: List<MediaItem>,
    ): androidx.car.app.model.Section<*> =
        if (id == MusicLibraryTree.TAB_ALBUMS && items.any { it.mediaMetadata.isBrowsable == true }) {
            GridSection.Builder()
                .setTitle(title)
                .setItems(albumTiles(items))
                .build()
        } else {
            RowSection.Builder()
                .setTitle(title)
                .setItems(songRows(items))
                .build()
        }

    /** Album grid tiles: cover art (host-resolved from the content URI) + name. */
    @Suppress("DEPRECATION")
    private fun albumTiles(items: List<MediaItem>): List<GridItem> =
        items.mapNotNull { item ->
            if (item.mediaMetadata.isBrowsable != true) return@mapNotNull null
            GridItem.Builder()
                .setTitle(item.mediaMetadata.title?.toString() ?: "Unknown")
                .setImage(artIcon(item), GridItem.IMAGE_TYPE_LARGE)
                .setOnClickListener {
                    screenManager.push(MusicCarBrowseScreen(carContext, item.mediaId, state))
                }
                .build()
        }

    /** A [CarIcon] for the item's cover art, falling back to the app icon. */
    private fun artIcon(item: MediaItem): CarIcon {
        val fromArt = item.mediaMetadata.artworkUri?.let { uri ->
            runCatching { IconCompat.createWithContentUri(uri) }.getOrNull()
        }
        return CarIcon.Builder(
            fromArt ?: IconCompat.createWithResource(carContext, R.mipmap.ic_launcher),
        ).build()
    }

    /** Condensed song rows: one line each so more fits on screen. */
    @Suppress("DEPRECATION")
    private fun songRows(items: List<MediaItem>): List<Row> =
        items.mapNotNull { item ->
            val meta = item.mediaMetadata
            val browsable = meta.isBrowsable == true
            val playable = !browsable && meta.isPlayable == true
            if (!browsable && !playable) return@mapNotNull null
            val row = Row.Builder()
                .setTitle(meta.title?.toString() ?: "Unknown")
            val subtitle = listOfNotNull(
                meta.artist?.toString()?.takeIf { it.isNotBlank() },
                meta.albumTitle?.toString()?.takeIf { it.isNotBlank() },
            ).joinToString(" · ").takeIf { it.isNotBlank() }
            subtitle?.let { row.addText(it) }
            if (browsable) {
                val count = state.tree.childCount(item.mediaId)
                if (count > 0) row.addText("$count items")
            }
            row.setBrowsable(browsable)
            if (browsable) {
                row.setOnClickListener {
                    screenManager.push(MusicCarBrowseScreen(carContext, item.mediaId, state))
                }
            } else {
                row.setOnClickListener { play(item.mediaId) }
            }
            row.build()
        }

    /** Single FAB: Play-all. Media FABs must be icon-only (0 custom titles allowed). */
    @Suppress("DEPRECATION")
    private fun playAllAction(): Action? {
        val items = state.tree.children(nodeId)
        if (items.none { it.mediaMetadata.isPlayable == true && it.mediaMetadata.isBrowsable != true }) {
            return null
        }
        return Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_car_play)).build())
            .setOnClickListener { playAll(items.map { it.mediaId }) }
            .build()
    }

    // ------------------------------------------------------------------
    // ListTemplate fallback (API 1–7)
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun listContent(tabId: String, label: String): Template {
        val tree = state.tree
        val list = ItemList.Builder()
        for (item in tree.children(tabId)) {
            songRow(item)?.let { list.addItem(it) }
        }
        return ListTemplate.Builder()
            .setSingleList(list.build())
            .setTitle(label)
            .setHeaderAction(Action.BACK)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun legacyListTemplate(): Template {
        val tree = state.tree
        val title = tree.item(nodeId)?.mediaMetadata?.title?.toString() ?: "Music"
        val items = tree.children(nodeId)
        val list = ItemList.Builder()
        if (nodeId != MusicLibraryTree.ROOT && items.isEmpty()) {
            list.addItem(
                Row.Builder()
                    .setTitle("Loading…")
                    .build(),
            )
        }
        for (item in items) {
            songRow(item)?.let { list.addItem(it) }
        }
        val builder = ListTemplate.Builder()
            .setSingleList(list.build())
            .setTitle(title)
            .setHeaderAction(Action.BACK)
        playAllAction()?.let { builder.addAction(it) }
        return builder.build()
    }

    @Suppress("DEPRECATION")
    private fun songRow(item: MediaItem): Row? {
        val meta = item.mediaMetadata
        val browsable = meta.isBrowsable == true
        val playable = !browsable && meta.isPlayable == true
        if (!browsable && !playable) return null
        val row = Row.Builder()
            .setTitle(meta.title?.toString() ?: "Unknown")
        val subtitle = listOfNotNull(
            meta.artist?.toString()?.takeIf { it.isNotBlank() },
            meta.albumTitle?.toString()?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").takeIf { it.isNotBlank() }
        subtitle?.let { row.addText(it) }
        if (browsable) {
            val count = state.tree.childCount(item.mediaId)
            if (count > 0) row.addText("$count items")
        }
        row.setBrowsable(browsable)
        if (browsable) {
            row.setOnClickListener {
                screenManager.push(MusicCarBrowseScreen(carContext, item.mediaId, state))
            }
        } else {
            row.setOnClickListener { play(item.mediaId) }
        }
        return row.build()
    }

    private fun play(mediaId: String) {
        val playable = state.tree.resolveForPlayback(mediaId) ?: return
        val controller = state.controller ?: return
        controller.setMediaItem(playable)
        controller.prepare()
        controller.play()
        state.tree.markPlayed(listOf(mediaId))
        screenManager.push(MusicCarPlaybackScreen(carContext, state))
    }

    private fun playAll(mediaIds: List<String>) {
        val playables = mediaIds.mapNotNull { state.tree.resolveForPlayback(it) }
        if (playables.isEmpty()) return
        val controller = state.controller ?: return
        controller.setMediaItems(playables)
        controller.prepare()
        controller.play()
        state.tree.markPlayed(mediaIds)
        screenManager.push(MusicCarPlaybackScreen(carContext, state))
    }
}
