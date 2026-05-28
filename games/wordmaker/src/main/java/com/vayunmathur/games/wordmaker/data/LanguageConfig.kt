package com.vayunmathur.games.wordmaker.data

data class LanguageConfig(
    val id: String,
    val displayName: String,
    val dictionaryFile: String,
    val levelsPath: String,
    val definitionsFile: String? = null
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
        dictionaryFile = "wordlist_de.txt",
        levelsPath = "levels/de",
        definitionsFile = "translations_de.csv"
    ),
)
