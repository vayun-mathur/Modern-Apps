package com.vayunmathur.appstore.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/**
 * Single DB-owning repository for the appstore module. Feature repositories
 * ([CatalogRepository], [AccrescentRepository], [com.vayunmathur.appstore.data.installer.InstallCoordinator])
 * keep taking an [AppDatabase] via their constructor — this class is the only
 * place that builds it, so the two entry points (MainActivity + UpdateCheckWorker)
 * share one instance.
 *
 * Passes [AppDatabase.migrations] explicitly so the DB opens with the same
 * migrations as before (RoomRepository falls back to the companion's list
 * when `migrations` is null, but being explicit keeps the intent obvious).
 */
class AppStoreDatabaseRepository private constructor(context: Context) :
    RoomRepository<AppDatabase>(context, AppDatabase::class, DB_NAME, migrations = AppDatabase.migrations) {

    val database: AppDatabase get() = db

    // Thin Flow/suspend passthroughs (optional — most callers go via feature repos; exposed for completeness)

    fun repoFlow(): Flow<List<RepoEntity>> = db.repoDao().allFlow()
    suspend fun allRepos(): List<RepoEntity> = db.repoDao().all()
    suspend fun upsertRepo(repo: RepoEntity) = db.repoDao().upsert(repo)
    suspend fun deleteByUrl(url: String) = db.repoDao().deleteByUrl(url)

    fun cachedAppsFlow(): Flow<List<CachedAppEntity>> = db.cachedAppDao().allFlow()
    suspend fun cachedAppByPackage(pkg: String): CachedAppEntity? = db.cachedAppDao().byPackage(pkg)

    /** Provides the singleton AppStoreDatabaseRepository instance. */
    companion object {
        @Volatile
        private var instance: AppStoreDatabaseRepository? = null

        fun get(context: Context): AppStoreDatabaseRepository =
            instance ?: synchronized(this) {
                instance ?: AppStoreDatabaseRepository(context).also { instance = it }
            }
    }
}
