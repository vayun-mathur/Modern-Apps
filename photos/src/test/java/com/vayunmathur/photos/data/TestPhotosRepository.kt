package com.vayunmathur.photos.data

import android.content.Context
import androidx.room3.Room

/**
 * Gives the Photos repositories an in-memory Room database for tests,
 * mirroring music's `TestMusicRepository` / todo's `TestTodoRepository`.
 *
 * The production databases are SQLCipher-backed (native library), which
 * cannot open on the Robolectric JVM. The only seam available without
 * touching main sources is the lazy `db` delegate in `RoomRepository`:
 * we reflectively replace that delegate with one returning an in-memory
 * database. Every repository / ViewModel / worker method then runs its
 * genuine code (DAO SQL, transactions, flow combination) against real
 * Room DAOs. Only storage is faked.
 */
object TestPhotosRepository {

    /** The repositories over one shared in-memory [PhotoDatabase], plus the vault over its own. */
    class Installed(
        val photos: PhotosRepository,
        val scan: PhotoScanRepository,
        val faces: FaceRepository,
        val database: PhotoDatabase,
    )

    /** Builds a fresh in-memory DB and injects it into all three PhotoDatabase repositories. */
    fun install(context: Context): Installed {
        val app = context.applicationContext
        val database = Room.inMemoryDatabaseBuilder(app, PhotoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val photos = PhotosRepository.get(app)
        val scan = PhotoScanRepository.get(app)
        val faces = FaceRepository.get(app)
        injectDatabase(photos, database)
        injectDatabase(scan, database)
        injectDatabase(faces, database)
        return Installed(photos, scan, faces, database)
    }

    /** Builds a fresh in-memory vault DB and injects it into the vault repository for [password]. */
    fun installVault(context: Context, password: String = "test-vault-pass"): VaultRepository {
        val app = context.applicationContext
        val database = Room.inMemoryDatabaseBuilder(app, VaultDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val vault = VaultRepository.get(app, password)
        injectDatabase(vault, database)
        return vault
    }

    private fun injectDatabase(repository: Any, database: Any) {
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
        error("Could not find RoomRepository db delegate on ${repository.javaClass.name}")
    }
}

/** Polls [condition] (Room writes land asynchronously) until it holds or times out. */
suspend fun awaitPhotosCondition(timeoutMs: Long = 20_000, condition: suspend () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        kotlinx.coroutines.delay(25)
    }
    return condition()
}
