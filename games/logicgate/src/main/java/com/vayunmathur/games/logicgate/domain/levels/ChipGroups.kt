package com.vayunmathur.games.logicgate.domain.levels

import com.vayunmathur.games.logicgate.domain.model.ChipCatalog
import com.vayunmathur.games.logicgate.domain.model.ChipSpec
import com.vayunmathur.games.logicgate.domain.model.Chips
import com.vayunmathur.games.logicgate.domain.model.PinChips

/** A tab of the chip tray. */
sealed interface TrayGroup {
    /** Board inputs and outputs (sandbox only). */
    data object Pins : TrayGroup

    /** Built-in chips first earned or gifted in [chapter]. */
    data class Builtin(val chapter: Chapter) : TrayGroup

    /** Player-built chips the player filed under [name]. */
    data class Custom(val name: String) : TrayGroup
}

/** One tray tab and its chips, in tray order. */
data class TraySection(val group: TrayGroup, val chips: List<ChipSpec>)

/** Sorts chips into tray tabs: pins, then built-ins by chapter, then the player's groups A-Z. */
object ChipGroups {
    private val chapterOf: Map<String, Chapter> = buildMap {
        for (id in Chips.base) put(id, Chapter.BASICS)
        for (level in LevelRegistry.levels) {
            for (id in level.gifts + level.unlocks) putIfAbsent(id, level.chapter)
        }
    }

    fun chapterOf(chipId: String): Chapter = chapterOf[chipId] ?: Chapter.BASICS

    fun groupOf(chipId: String, catalog: ChipCatalog): TrayGroup = when {
        PinChips.isPin(chipId) -> TrayGroup.Pins
        else -> catalog.custom(chipId)?.let { TrayGroup.Custom(it.group) } ?: TrayGroup.Builtin(chapterOf(chipId))
    }

    fun sections(chips: List<ChipSpec>, catalog: ChipCatalog): List<TraySection> {
        val grouped = chips.groupBy { groupOf(it.id, catalog) }
        return grouped.keys.sortedWith(ORDER).map { TraySection(it, grouped.getValue(it)) }
    }

    private val ORDER = compareBy<TrayGroup>(
        {
            when (it) {
                TrayGroup.Pins -> 0
                is TrayGroup.Builtin -> 1
                is TrayGroup.Custom -> 2
            }
        },
        { (it as? TrayGroup.Builtin)?.chapter?.ordinal ?: 0 },
        { (it as? TrayGroup.Custom)?.name?.lowercase().orEmpty() },
    )
}
