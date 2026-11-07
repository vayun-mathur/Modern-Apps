package com.vayunmathur.appstore.data

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The installed-apps sweep against Robolectric's PackageManager, which
 * registers this test app as an installed non-system package.
 */
@RunWith(RobolectricTestRunner::class)
class InstalledAppsRepositoryTest {

    private lateinit var context: Context

    @BeforeTest
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `flows start empty before the first refresh`() {
        val repo = InstalledAppsRepository(context)
        assertTrue(repo.apps.value.isEmpty())
        assertTrue(repo.updatable.value.isEmpty())
        assertTrue(repo.icons.value.isEmpty())
    }

    @Test
    fun `refresh lists the test app itself with its version`() = runTest {
        val repo = InstalledAppsRepository(context)
        repo.refresh()
        val own = repo.apps.value.find { it.packageName == context.packageName }
        assertTrue(own != null, "own package missing from ${repo.apps.value.map { it.packageName }}")
        assertEquals(context.packageName, own.packageName)
        assertTrue(own.name.isNotBlank())
    }

    @Test
    fun `updatable is a subset of apps and icons key by package`() = runTest {
        val repo = InstalledAppsRepository(context)
        repo.refresh()
        val packages = repo.apps.value.map { it.packageName }.toSet()
        assertTrue(repo.updatable.value.all { it.packageName in packages })
        assertTrue(repo.icons.value.keys.all { it in packages })
    }
}
