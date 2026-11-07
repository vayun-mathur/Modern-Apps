package com.vayunmathur.youpipe.tv

import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.util.YouPipeViewModel
import kotlinx.coroutines.flow.first

/**
 * Shared setup for the TV (slice 3/3) pass-3 tests: an in-memory
 * [SubscriptionDatabase] is seeded into the process-wide database registry
 * (the exact instance [SubscriptionRepository] resolves), so the real
 * [YouPipeViewModel] runs under Robolectric without AndroidKeyStore.
 * One database per test class, wiped between tests by the callers that
 * seed (same pattern as RootSlice3PlaylistDialogsTest).
 */
internal object TvPass3Support {

    @Volatile private var db: SubscriptionDatabase? = null

    fun database(context: Context): SubscriptionDatabase {
        db?.let { return it }
        com.vayunmathur.library.util.databases[SubscriptionDatabase::class]?.let {
            @Suppress("UNCHECKED_CAST")
            return (it as SubscriptionDatabase).also { existing -> db = existing }
        }
        return Room.inMemoryDatabaseBuilder(context, SubscriptionDatabase::class.java)
            .allowMainThreadQueries()
            .build().also {
                com.vayunmathur.library.util.databases[SubscriptionDatabase::class] = it
                db = it
            }
    }

    fun viewModel(context: Context): YouPipeViewModel {
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        database(context)
        val app = context.applicationContext as Application
        return YouPipeViewModel(app, SubscriptionRepository.get(context))
    }

    suspend fun wipe(db: SubscriptionDatabase, vm: YouPipeViewModel) {
        db.subscriptionDao().clearAll()
        db.historyVideoDao().clearAll()
        for (row in db.downloadedVideoDao().getAllFlow().first()) {
            db.downloadedVideoDao().delete(row)
        }
        for (playlist in db.playlistDao().getAll()) {
            for (item in db.playlistItemDao().getForPlaylist(playlist.id)) {
                db.playlistItemDao().delete(item)
            }
            db.playlistDao().delete(playlist)
        }
        for (name in vm.subscriptionCategories.value.map { it.category }.distinct()) {
            db.subscriptionCategoryDao().deleteCategory(name)
        }
    }

    fun backStack(vararg routes: TvRoute): NavBackStack<TvRoute> =
        NavBackStack(arrayOf(*routes))
}
