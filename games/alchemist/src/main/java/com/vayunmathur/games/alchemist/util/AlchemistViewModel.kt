package com.vayunmathur.games.alchemist.util

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.alchemist.data.Alchemist
import com.vayunmathur.games.alchemist.data.AlchemyItem
import com.vayunmathur.games.alchemist.data.AlchemyRecipe
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A committed element placed on the play area. The currently-being-dragged offset
 * lives in Compose; this is the last-known committed position.
 */
data class PlacedItem(val id: Long, val offset: Offset, val key: Long = System.nanoTime())

/**
 * ViewModel for the Alchemist game.
 *
 * Owns:
 *  - JSON asset load (items / recipes) via the singleton [Alchemist].
 *  - Available-items (inventory) derivation from DataStore.
 *  - Committed placed-elements list and combination logic.
 *  - Initial seeding of the four base elements.
 *  - Achievement triggers when the inventory changes (after [bindAchievements]).
 *
 * Compose still owns: per-element in-flight drag offsets, dialog visibility,
 * and any UI-only animation state.
 */
class AlchemistViewModel(application: Application) : AndroidViewModel(application) {

    private val ds = DataStoreUtils.getInstance(application)

    // --- Catalog (constants after JSON load) ---
    private val _allItems = MutableStateFlow<List<AlchemyItem>>(emptyList())
    val allItems: StateFlow<List<AlchemyItem>> = _allItems.asStateFlow()

    private val _recipes = MutableStateFlow<List<AlchemyRecipe>>(emptyList())
    val recipes: StateFlow<List<AlchemyRecipe>> = _recipes.asStateFlow()

    // --- Unlocked inventory (persisted) ---
    val itemIds: StateFlow<Set<Long>> = ds.stringSetFlow("available_items")
        .map { set -> set.map { it.toLong() }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val availableItems: StateFlow<List<AlchemyItem>> =
        combine(_allItems, itemIds) { items, ids ->
            items.filter { it.id in ids }.sortedBy { it.name }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Placed elements on the play area (committed positions) ---
    private val _placedElements = MutableStateFlow<List<PlacedItem>>(emptyList())
    val placedElements: StateFlow<List<PlacedItem>> = _placedElements.asStateFlow()

    // --- One-shot events for newly-discovered items (drives the unlock toast) ---
    private val _newUnlocksEvent = MutableSharedFlow<List<AlchemyItem>>(extraBufferCapacity = 5)
    val newUnlocksEvent: SharedFlow<List<AlchemyItem>> = _newUnlocksEvent.asSharedFlow()

    init {
        // Load JSON + derive recipes on Dispatchers.IO to keep first frame
        // responsive. Other consumers (e.g. AlchemistAchievementsManager) read
        // Alchemist.items directly; the singleton's defaults (empty lists) and
        // the `Alchemist.items.isNotEmpty()` guards keep them safe during boot.
        viewModelScope.launch(Dispatchers.IO) {
            Alchemist.init(application)
            _allItems.value = Alchemist.items
            _recipes.value = Alchemist.recipes
            seedInitialItemsIfEmpty()
        }
    }

    private suspend fun seedInitialItemsIfEmpty() {
        val initial = ds.stringSetFlow("available_items").first()
        if (initial.isEmpty()) {
            (1L..4L).forEach { ds.addStringToSet("available_items", it.toString()) }
        }
    }

    /** Persist a newly-discovered element id to the inventory. */
    fun unlockItem(id: Long) {
        if (id !in itemIds.value) {
            ds.addStringToSet("available_items", id.toString())
        }
    }

    /** Add a new placed element (e.g. dragged out of the inventory panel). */
    fun placeElement(id: Long, offset: Offset): PlacedItem {
        val newItem = PlacedItem(id, offset)
        _placedElements.update { it + newItem }
        tryCombine(newItem.key, newItem.offset)
        return newItem
    }

    /** Update the committed position of an already-placed element after a drag ends. */
    fun updateElementPosition(key: Long, offset: Offset) {
        _placedElements.update { list ->
            list.map { if (it.key == key) it.copy(offset = offset) else it }
        }
    }

    /** Remove a placed element (e.g. dragged onto the deletion sidebar). */
    fun removeElement(key: Long) {
        _placedElements.update { list -> list.filterNot { it.key == key } }
    }

    /** Duplicate a placed element at a slightly offset position. */
    fun duplicateElement(key: Long) {
        val current = _placedElements.value
        val elementToDuplicate = current.find { it.key == key } ?: return
        val duplicatedItem = PlacedItem(
            id = elementToDuplicate.id,
            offset = elementToDuplicate.offset + Offset(25f, 25f)
        )
        _placedElements.update { it + duplicatedItem }
    }

    /**
     * Test the moved element against the other placed elements for a recipe match.
     * If found, removes the two inputs, adds the outputs at the target's position,
     * and persists newly-discovered ids.
     */
    fun tryCombine(movedKey: Long, movedOffset: Offset) {
        val current = _placedElements.value
        val movedItem = current.find { it.key == movedKey } ?: return
        val target = current
            .filter { it.key != movedKey }
            .find { (it.offset - movedOffset).getDistance() < 100f }
            ?: return

        val combined = listOf(movedItem.id, target.id).sorted()
        val recipe = _recipes.value.find { it.inputs.sorted() == combined } ?: return

        val toRemoveKeys = setOf(movedItem.key, target.key)
        val toAdd = recipe.outputs.map { PlacedItem(it, target.offset) }
        _placedElements.update { list ->
            list.filterNot { it.key in toRemoveKeys } + toAdd
        }

        // Emit the new-discovery toast for any outputs not already in the
        // inventory, then persist all outputs.
        val knownIds = itemIds.value
        val discovered = recipe.outputs
            .filter { it !in knownIds }
            .distinct()
            .mapNotNull { id -> _allItems.value.find { it.id == id } }
        if (discovered.isNotEmpty()) {
            _newUnlocksEvent.tryEmit(discovered)
        }
        toAdd.forEach { unlockItem(it.id) }
    }

    // --- Achievements ---
    private var achievementsBound = false

    /**
     * Wires achievement triggers to inventory changes. Safe to call multiple
     * times; only the first call subscribes.
     */
    fun bindAchievements(achievementsManager: AchievementsManager) {
        if (achievementsBound) return
        achievementsBound = true
        viewModelScope.launch {
            availableItems.collect { items ->
                if (items.isEmpty()) return@collect
                if (items.size > 4) achievementsManager.onAchievementUnlocked("first_creation")
                achievementsManager.onProgressUpdated("collector_50", items.size)
                achievementsManager.onProgressUpdated("collector_100", items.size)

                val all = _allItems.value
                if (items.size >= all.size && all.isNotEmpty()) {
                    achievementsManager.onAchievementUnlocked("all_discovered")
                }
                if (items.any { it.final }) {
                    achievementsManager.onAchievementUnlocked("final_item")
                }
            }
        }
    }
}
