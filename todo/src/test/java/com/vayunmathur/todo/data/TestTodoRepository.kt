package com.vayunmathur.todo.data

import android.content.Context
import androidx.room3.Room
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.Closeable

/**
 * Creates a real [TodoRepository] backed by an in-memory Room database.
 *
 * [TodoRepository]'s production database is SQLCipher-backed (native library, file
 * passphrase from the keystore), which cannot open on the Robolectric JVM. The only
 * seam available without touching main sources is the lazy `db` delegate in
 * `RoomRepository`: we reflectively replace that delegate with one returning an
 * in-memory database built exactly as [TodoDaoTest] builds it. Every repository /
 * ViewModel / editor / relay method then runs its genuine code (transactions,
 * updatedAt bumping, recurrence handling, DAO SQL) against real Room DAOs.
 *
 * Any API faked here: none at the repository level. The underlying database is
 * in-memory Room (BundledSQLite) instead of SQLCipher-on-file, which only affects
 * storage, not the logic under test.
 */
class TestTodoRepository(
    val repository: TodoRepository,
    val database: TodoDatabase,
) : Closeable {

    override fun close() {
        clearSingleton(repository)
        // Deliberately do NOT close the in-memory database: ViewModel /
        // editor flow collectors outlive individual tests, and a DAO call
        // landing after close throws "Connection pool is closed" into the
        // next test's scope. The in-memory DB dies with the Robolectric
        // sandbox instead.
    }

    companion object {
        fun create(
            context: Context,
            io: CoroutineDispatcher = Dispatchers.Unconfined,
        ): TestTodoRepository {
            val database = Room.inMemoryDatabaseBuilder(context, TodoDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val constructor = TodoRepository::class.java
                .getDeclaredConstructor(Context::class.java, CoroutineDispatcher::class.java)
                .apply { isAccessible = true }
            val repository = constructor.newInstance(context.applicationContext, io)
            injectDatabase(repository, database)
            installSingleton(repository)
            return TestTodoRepository(repository, database)
        }

        private fun injectDatabase(repository: TodoRepository, database: TodoDatabase) {
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
            error("Could not find RoomRepository db delegate on TodoRepository")
        }

        /** Makes [TodoRepository.get] (used by providers/receivers/widgets) return [repository]. */
        fun installSingleton(repository: TodoRepository) = setSingleton(repository)

        @Suppress("UnusedParameter") // kept for call-site symmetry with installSingleton
        fun clearSingleton(repository: TodoRepository? = null) = setSingleton(null)

        private fun setSingleton(value: TodoRepository?) {
            // Static backing field on the outer class, if present.
            for (field in TodoRepository::class.java.declaredFields) {
                if (field.name == "instance") {
                    runCatching {
                        field.isAccessible = true
                        field.set(null, value)
                    }
                }
            }
            // Backing field held by the Companion object instance, if present.
            runCatching {
                val companionField = TodoRepository::class.java.getDeclaredField("Companion")
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
}

/** Polls [condition] (Room writes land asynchronously) until it holds or times out. */
suspend fun awaitCondition(timeoutMs: Long = 5_000, condition: suspend () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        kotlinx.coroutines.delay(25)
    }
    return condition()
}
