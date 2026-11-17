package com.vayunmathur.launcher.domain

/**
 * Pure row-order math for the TV home screen: no Android, no Compose.
 *
 * Mirrors the tv-detox ordering rules: a saved list wins verbatim (minus packages that
 * disappeared); otherwise the built-in defaults lead and installed leanback apps top the row
 * up to one full row of [PER_ROW].
 */
object TvRowOrder {
    const val PER_ROW = 6
    const val MAX_INPUTS = 4

    /** The default top row, in order (tv-detox's `apps.txt`). Missing apps are skipped. */
    val DEFAULT_ROW: List<String> = listOf(
        "com.google.android.youtube.tv",
        "com.netflix.ninja",
        "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus",
        "com.hulu.livingroomplus",
        "com.wbd.stream",
    )

    /** Packages that are never tiles, even when launchable (tv-detox's `NOT_APPS`). */
    val NEVER_TILES: Set<String> = setOf(
        "com.android.vending",
        "com.android.tv.settings",
        "com.android.tv",
        "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx",
        "com.google.android.leanbacklauncher",
    )

    const val PLAY_STORE = "com.android.vending"

    /**
     * The top row as shown, in order.
     *
     * @param saved the persisted order, or null when the row still follows the defaults. A
     *   saved list is honoured verbatim (minus vanished packages) — even the Play Store, if
     *   the user pinned it there.
     * @param installed every installed launchable package (leanback first, then phone
     *   launcher), already minus this app itself and minus [NEVER_TILES] except the store.
     */
    fun mergeOrder(
        saved: List<String>?,
        installed: List<String>,
        defaults: List<String> = DEFAULT_ROW,
    ): List<String> {
        val present = installed.toSet()
        val wanted = saved ?: defaults
        val row = wanted.filter { it in present }.toMutableList()
        if (saved == null) {
            // Top up with other TV apps on this TV, stopping at one full row. The Play Store
            // never enters this way: it owns a bottom-row slot of its own (tv-detox's NOT_APPS
            // skip in the top-up path).
            for (pkg in installed) {
                if (row.size >= PER_ROW) break
                if (pkg == PLAY_STORE || pkg !in present || pkg in row) continue
                if (pkg in NEVER_TILES) continue
                row.add(pkg)
            }
        }
        return row
    }

    /** Slides the entry at [from] to [to], clamping into range. */
    fun move(order: List<String>, from: Int, to: Int): List<String> {
        if (from !in order.indices) return order
        val clamped = to.coerceIn(0, order.size - 1)
        if (clamped == from) return order
        return order.toMutableList().apply { add(clamped, removeAt(from)) }
    }

    fun removeAt(order: List<String>, index: Int): List<String> =
        if (index in order.indices) order.toMutableList().apply { removeAt(index) } else order

    fun replaceAt(order: List<String>, index: Int, pkg: String): List<String> {
        if (pkg.isBlank()) return order
        val without = order.filter { it != pkg }
        return if (index in without.indices) {
            without.toMutableList().apply { set(index, pkg) }
        } else {
            without + pkg
        }
    }

    /**
     * How many HDMI tiles fit on the bottom row: the row holds [PER_ROW] with room always
     * kept for Settings and Add, plus the Play Store when it is shown (tv-detox's `room`).
     */
    fun maxHdmiSlots(hasStore: Boolean): Int = (PER_ROW - 2 - if (hasStore) 1 else 0)
        .coerceIn(0, MAX_INPUTS)
}

