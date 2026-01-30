package com.vayunmathur.games.wordmaker

import android.content.Context

class Dictionary() {
    data class Word(val word: String, val position: String, val definition: String)
    private val words = mutableListOf<Word>()
    private val definitions: MutableMap<String, String> = mutableMapOf()

    fun init(context: Context) {
        context.assets.open("dictionary.csv").bufferedReader().lines().forEach {
            val parts = it.split(",", limit = 3)
            if(parts.size < 3) return@forEach
            words.add(Word(parts[0].lowercase(), parts[1], parts[2]))
            definitions[parts[0].lowercase()] = parts[2]
        }
    }

    operator fun contains(word: String): Boolean {
        return words.any { it.word == word }
    }

    fun getDefinition(word: String): String? {
        return definitions[word.lowercase()]
    }
}