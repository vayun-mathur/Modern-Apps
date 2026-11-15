package com.vayunmathur.music.data

import android.content.Context
import androidx.room3.Room

/**
 * Gives the [MusicRepository] singleton an in-memory Room database for tests.
 *
 * The production database is SQLCipher-backed (native library), which cannot
 * open on the Robolectric JVM — that is why playlist/matching code used to be
 * untestable here. The only seam available without touching main sources is
 * the lazy `db` delegate in `RoomRepository`: we reflectively replace that
 * delegate with one returning an in-memory database. Every repository /
 * ViewModel / screen method then runs its genuine code (DAO SQL, flow
 * combination, playlist CRUD) against real Room DAOs. Only storage is faked,
 * exactly as todo's `TestTodoRepository` does for its repository.
 */
object TestMusicRepository {

    /** Builds a fresh in-memory DB, injects it into the singleton, returns both. */
    fun install(context: Context): Pair<MusicRepository, MusicDatabase> {
        val app = context.applicationContext
        val database = Room.inMemoryDatabaseBuilder(app, MusicDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = MusicRepository.get(app)
        injectDatabase(repository, database)
        return repository to database
    }

    private fun injectDatabase(repository: MusicRepository, database: MusicDatabase) {
        var type: Class<*>? = repository.javaClass
        while (type != null) {
            for (field in type.declaredFields) {
                val looksLikeDbDelegate = field.type == Lazy::class.java ||
                    field.name.contains("db", ignoreCase = true)
                if (!looksLikeDbDelegate) continue
                runCatching {
                    field.isAccessible = true
                    field.set(repository, lazyOf(database))
                    return
                }
            }
            type = type.superclass
        }
        error("Could not find RoomRepository db delegate on MusicRepository")
    }
}
