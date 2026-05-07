package com.vayunmathur.games.alchemist.util

import android.content.Context
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.games.alchemist.data.Alchemist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AlchemistAchievementsManager(
    context: Context,
    json: String
) : AchievementsManager(context, json) {
    override fun checkExistingAchievements() {
        CoroutineScope(Dispatchers.IO).launch {
            val ds = DataStoreUtils.getInstance(context)
            val items = ds.stringSetFlow("available_items").first()
            if (items.size > 4) onAchievementUnlocked("first_creation")
            onProgressUpdated("collector_50", items.size)
            onProgressUpdated("collector_100", items.size)
            onProgressUpdated("all_discovered", items.size)
            
            val itemIds = items.map { it.toLong() }.toSet()
            if (Alchemist.items.isNotEmpty()) {
                if (itemIds.size >= Alchemist.items.size) {
                    onAchievementUnlocked("all_discovered")
                }
                val discoveredFinal = Alchemist.items.any { it.final && it.id in itemIds }
                if (discoveredFinal) {
                    onAchievementUnlocked("final_item")
                }
                if (itemIds.contains(44L)) {
                    onAchievementUnlocked("created_life")
                }
            }
        }
    }
}
