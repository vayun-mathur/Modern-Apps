package com.vayunmathur.launcher.platform

import android.graphics.drawable.Drawable

/** One row-app tile as the screen renders it. */
data class TvTile(
    val packageName: String,
    val label: String,
    val banner: Drawable?,
    val appIcon: Drawable?,
    val userInstalled: Boolean,
)

/** Stateless home-screen state, collected from [TvHomeViewModel]. */
data class TvHomeState(
    val rowApps: List<TvTile> = emptyList(),
    /** True when the MA App Store is installed (bottom-row "App Store" tile). */
    val showAppStore: Boolean = false,
    val hdmiInputs: List<TvHdmiInput> = emptyList(),
    /** The package being moved with the arrows, or null when not holding a tile. */
    val movingKey: String? = null,
    /**
     * True while reorder mode is armed. Armed persists across drops — only the Reorder
     * toggle or Back disarms. While armed, short-OK picks a tile up instead of opening it.
     */
    val reorderArmed: Boolean = false,
    /** Packages offered by the add menu (not on the row, by label). */
    val addCandidates: List<TvAppEntry> = emptyList(),
    /** Index the add menu replaces, or -1 to append. */
    val addReplacesIndex: Int = -1,
    /** The package the edit menu is open for, or null when closed. */
    val editingKey: String? = null,
    /** True while the add/replace picker is open (even when it has no candidates). */
    val showAddMenu: Boolean = false,
    val isDefaultHome: Boolean = false,
    val loading: Boolean = true,
)

