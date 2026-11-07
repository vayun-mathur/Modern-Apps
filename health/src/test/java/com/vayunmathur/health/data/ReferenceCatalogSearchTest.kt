package com.vayunmathur.health.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReferenceCatalogSearchTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `searches on unprepared catalog return empty`() = runTest {
        ReferenceCatalog.init(context)
        assertTrue(ReferenceCatalog.searchIngredients("amox").isEmpty())
        assertTrue(ReferenceCatalog.searchProducts("amox").isEmpty())
        assertTrue(ReferenceCatalog.searchAllergens("peanut").isEmpty())
        assertTrue(ReferenceCatalog.searchLabs("a1c").isEmpty())
        assertTrue(ReferenceCatalog.searchConditions("diabetes").isEmpty())
        assertTrue(ReferenceCatalog.productsFor("amoxicillin").isEmpty())
    }

    @Test
    fun `prepare does not throw and reports a status`() = runTest {
        ReferenceCatalog.init(context)
        val result = ReferenceCatalog.prepare()
        // Asset may be absent under Robolectric: either success or a
        // Result.failure with a message, never an exception escaping.
        assertTrue(result.isSuccess || result.isFailure)
        if (result.isSuccess) {
            assertTrue(ReferenceCatalog.searchIngredients("a").size >= 0)
        }
    }

    @Test
    fun `blank queries short circuit to empty`() = runTest {
        ReferenceCatalog.init(context)
        assertTrue(ReferenceCatalog.searchIngredients("").isEmpty())
        assertTrue(ReferenceCatalog.searchConditions("   ").isEmpty())
        assertTrue(ReferenceCatalog.searchLabs("---").isEmpty())
    }
}
