package com.vayunmathur.passwords.data

import androidx.room3.ColumnTypeConverter

/** Singleton holding converters state and operations. */
object Converters {
    private const val DELIM = "|||"

    @ColumnTypeConverter
    @JvmStatic
    fun fromWebsites(list: List<String>?): String = (list ?: emptyList()).joinToString(DELIM)

    @ColumnTypeConverter
    @JvmStatic
    fun toWebsites(value: String?): List<String> = value?.takeIf { it.isNotEmpty() }?.split(DELIM) ?: emptyList()
}
