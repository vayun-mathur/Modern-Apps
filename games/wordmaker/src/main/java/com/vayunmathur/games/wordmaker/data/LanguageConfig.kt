package com.vayunmathur.games.wordmaker.data

data class LanguageConfig(
    val id: String,
    val displayName: String,
    val dictionaryFile: String,
    val levelsPath: String
)

val AVAILABLE_LANGUAGES = listOf(
    LanguageConfig(
        id = "en",
        displayName = "English",
        dictionaryFile = "dictionary.csv",
        levelsPath = "levels"
    ),
    LanguageConfig(
        id = "de",
        displayName = "Deutsch",
        dictionaryFile = "dictionary_de.csv",
        levelsPath = "levels/de"
    ),
)
