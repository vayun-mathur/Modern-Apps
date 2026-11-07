package com.vayunmathur.appstore.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The DB-owning repository's passthroughs against its real database:
 * repo rows and cached-app rows written through it are read back through
 * it, and deletes through it remove them. Uses unique URLs/packages and
 * cleans up so the shared singleton stays as it was found.
 */
@RunWith(RobolectricTestRunner::class)
class AppStoreDatabaseRepositoryTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `repo and cached app passthroughs round trip`() = runTest {
        val repo = AppStoreDatabaseRepository.get(ctx)
        val url = "https://example.org/repository-passthrough/"
        val pkg = "com.example.passthrough"
        try {
            repo.upsertRepo(RepoEntity(url = url, name = "Passthrough", fingerprint = "AB12"))
            assertTrue(repo.allRepos().any { it.url == url && it.name == "Passthrough" })
            assertTrue(repo.repoFlow().first().any { it.url == url })

            repo.database.cachedAppDao().upsert(
                UnifiedApp(
                    packageName = pkg,
                    source = AppSource.FDROID,
                    name = "Passthrough App",
                    repoUrl = url,
                ).toEntity(),
            )
            assertEquals("Passthrough App", repo.cachedAppByPackage(pkg)?.name)
            assertTrue(repo.cachedAppsFlow().first().any { it.packageName == pkg })

            repo.deleteByUrl(url)
            assertTrue(repo.allRepos().none { it.url == url })
        } finally {
            runCatching { repo.deleteByUrl(url) }
            runCatching { repo.database.cachedAppDao().deleteByRepo(url) }
        }
        assertNull(repo.cachedAppByPackage(pkg))
    }

    @Test
    fun `get returns the shared instance`() {
        assertTrue(
            AppStoreDatabaseRepository.get(ctx) === AppStoreDatabaseRepository.get(ctx),
            "the database owner must be a process-wide singleton",
        )
    }
}
