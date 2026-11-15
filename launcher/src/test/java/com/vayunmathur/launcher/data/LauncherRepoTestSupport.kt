package com.vayunmathur.launcher.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * Builds a [LauncherRepository] backed by an in-memory Room database.
 *
 * The production repository opens its SQLCipher database through
 * `RoomRepository`, which needs the AndroidKeyStore — unavailable under
 * Robolectric (see the spike failure: `KeyStoreException: AndroidKeyStore
 * not found`). The DAO tests already run Room over the bundled SQLite
 * driver, so tests swap the repository's lazy `db` delegate for the same
 * in-memory database and exercise the real repository logic (transactions,
 * folder collapse, reconcile, regrid) end to end.
 */
class InMemoryLauncherRepository(
    val repository: LauncherRepository,
    val database: LauncherDatabase,
) : AutoCloseable {
    override fun close() = database.close()
}

fun inMemoryLauncherRepository(context: Context): InMemoryLauncherRepository {
    val database = Room.inMemoryDatabaseBuilder(context, LauncherDatabase::class.java)
        .setDriver(BundledSQLiteDriver())
        .allowMainThreadQueries()
        .build()
    val constructor = LauncherRepository::class.java
        .getDeclaredConstructor(Context::class.java)
        .apply { isAccessible = true }
    val repository = constructor.newInstance(context)
    swapLauncherRepositoryDb(repository, database)
    return InMemoryLauncherRepository(repository, database)
}

/** Replaces [repository]'s lazy Room `db` delegate with [database]; call before first db access. */
fun swapLauncherRepositoryDb(repository: LauncherRepository, database: LauncherDatabase) {
    var type: Class<*>? = repository.javaClass
    var replaced = false
    while (type != null && !replaced) {
        for (field in type.declaredFields) {
            if (field.name.contains("db") && field.type == Lazy::class.java) {
                field.isAccessible = true
                field.set(repository, lazyOf(database))
                replaced = true
                break
            }
        }
        type = type.superclass
    }
    check(replaced) { "could not swap LauncherRepository db delegate" }
}
