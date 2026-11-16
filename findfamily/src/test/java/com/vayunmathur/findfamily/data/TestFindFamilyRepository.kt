package com.vayunmathur.findfamily.data

import android.content.Context
import androidx.room3.Room

/**
 * Builds a [FindFamilyRepository] over an in-memory [FFDatabase] and installs
 * it as the process singleton, mirroring todo's `TestTodoRepository` and
 * music's `TestMusicRepository`.
 *
 * Production FindFamily already exposes a constructor seam
 * (`FindFamilyRepository(context, databaseOverride)`), so no reflective
 * SQLCipher-delegate swap is needed here: the repository's DAOs read the
 * override while the singleton consumers (`NoShowCheckScheduler.sweepNow`,
 * `FamilyLocationService`, worker delivery) read the same rows through
 * [FindFamilyRepository.get]. Only storage is faked; every DAO query, flow
 * and cascade runs its genuine code.
 */
object TestFindFamilyRepository {

    /** Builds a fresh in-memory DB, installs its repository as the singleton, returns it. */
    fun install(context: Context): FindFamilyRepository {
        val app = context.applicationContext
        val database = Room.inMemoryDatabaseBuilder(app, FFDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = FindFamilyRepository(app, database)
        setSingleton(repository)
        return repository
    }

    private fun setSingleton(value: FindFamilyRepository?) {
        for (field in FindFamilyRepository::class.java.declaredFields) {
            if (field.name == "instance") {
                runCatching {
                    field.isAccessible = true
                    field.set(null, value)
                }
            }
        }
        runCatching {
            val companionField = FindFamilyRepository::class.java.getDeclaredField("Companion")
                .apply { isAccessible = true }
            val companion = companionField.get(null) ?: return@runCatching
            for (field in companion.javaClass.declaredFields) {
                if (field.name == "instance") {
                    field.isAccessible = true
                    field.set(companion, value)
                }
            }
        }
    }
}

/** Polls [condition] (Room writes and WorkManager posts land asynchronously) until it holds. */
suspend fun awaitFamilyCondition(timeoutMs: Long = 8_000, condition: suspend () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        kotlinx.coroutines.delay(25)
    }
    return condition()
}
