package com.vayunmathur.games.wordmaker.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.wordmaker.R
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.data.LevelDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the WordMaker game.
 *
 * Owns:
 *  - DataStore-backed progress (current level, found words, bonus words)
 *  - Crossword data loading from assets (per-level)
 *  - Dictionary loading from assets (one-time)
 *
 * Composables continue to own UI/animation state — letter offsets, drag offsets,
 * shake/scale [androidx.compose.animation.core.Animatable]s — because those are
 * inherently tied to composition lifecycle.
 */
class WordMakerViewModel(application: Application) : AndroidViewModel(application) {

    /** Exposed for shared [com.vayunmathur.library.util.AchievementsManager] construction. */
    val levelDataStore: LevelDataStore = LevelDataStore(application)

    private val dictionary = Dictionary()

    val currentLevel: StateFlow<Int> = levelDataStore.currentLevel
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1)

    val foundWords: StateFlow<Set<String>> = levelDataStore.foundWords
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val bonusWords: StateFlow<Set<String>> = levelDataStore.bonusWords
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _crosswordData = MutableStateFlow<CrosswordData?>(null)
    val crosswordData: StateFlow<CrosswordData?> = _crosswordData.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            dictionary.init(getApplication())
        }
        viewModelScope.launch {
            currentLevel.collectLatest { level -> loadLevel(level) }
        }
    }

    private suspend fun loadLevel(level: Int) {
        val ctx = getApplication<Application>()
        try {
            val data = withContext(Dispatchers.IO) {
                CrosswordData.fromAsset(ctx, "levels/$level.txt")
            }
            if (data == null) {
                _crosswordData.value = null
                _error.value = ctx.getString(R.string.error_parse_level)
            } else {
                _crosswordData.value = data
                _error.value = null
            }
        } catch (e: Exception) {
            _crosswordData.value = null
            _error.value = ctx.getString(R.string.error_load_level, e.message)
        }
    }

    fun isInDictionary(word: String): Boolean = word.lowercase() in dictionary

    fun getDefinition(word: String): List<String> = dictionary.getDefinition(word)

    fun saveLevel(level: Int) {
        viewModelScope.launch { levelDataStore.saveLevel(level) }
    }

    fun addFoundWord(word: String) {
        viewModelScope.launch { levelDataStore.addFoundWord(word) }
    }

    /** Suspending so callers (e.g. compose animation coroutines) can sequence achievement updates. */
    suspend fun addBonusWord(word: String): Int = levelDataStore.addBonusWord(word)
}
