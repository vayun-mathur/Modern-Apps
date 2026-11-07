package com.vayunmathur.appstore.data.play

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Play session restore with nothing persisted: no anonymous-auth network
 * call may happen at restore time, and reads that need an API answer
 * with no session rather than throwing.
 */
@RunWith(RobolectricTestRunner::class)
class PlayRepositoryRestoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `restore with no stored auth stays idle`() = runTest {
        val repo = PlayRepository(context)
        assertEquals(PlayAuthState.Idle, repo.authState.value)
        repo.restore()
        assertEquals(PlayAuthState.Idle, repo.authState.value)
    }

    @Test
    fun `detailsOrNull of no packages is an answered empty lookup`() = runTest {
        val repo = PlayRepository(context)
        // Distinct from a failed lookup (null): asking about nothing succeeds.
        assertEquals(emptyList(), repo.detailsOrNull(emptyList()))
    }

    @Test
    fun `search with no session answers empty`() = runTest {
        val repo = PlayRepository(context)
        assertTrue(repo.search("anything").isEmpty())
        assertTrue(repo.search("two words").isEmpty())
    }

    @Test
    fun `single details with an empty package name is null`() = runTest {
        val repo = PlayRepository(context)
        assertNull(repo.details(""))
    }
}
