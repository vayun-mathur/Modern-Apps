package com.vayunmathur.health.data

import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the bundled CVX vaccine catalogue.
 *
 * The picker ranks today's vaccines first and prefix matches before buried
 * ones; these pin that ordering against the real shipped asset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaccineCatalogTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `the bundled catalogue loads`() = runTest {
        val all = VaccineCatalog.all(context)
        assertTrue(all.size > 100, "expected hundreds of CVX rows, got ${all.size}")
    }

    @Test
    fun `empty query returns everything`() = runTest {
        assertEquals(VaccineCatalog.all(context).size, VaccineCatalog.search(context, "").size)
        assertEquals(VaccineCatalog.all(context).size, VaccineCatalog.search(context, "?!").size)
    }

    @Test
    fun `every token must match somewhere`() = runTest {
        val results = VaccineCatalog.search(context, "covid mrna")
        assertTrue(results.isNotEmpty(), "expected COVID mRNA hits")
        for (vaccine in results) {
            val haystack = "${vaccine.name} ${vaccine.fullName} ${vaccine.code}".lowercase()
            assertTrue("covid" in haystack && "mrna" in haystack, "${vaccine.code} matches loosely")
        }
    }

    @Test
    fun `active codes rank before retired ones`() = runTest {
        val results = VaccineCatalog.search(context, "influenza")
        assertTrue(results.isNotEmpty())
        val firstInactive = results.indexOfFirst { !it.active }
        val lastActive = results.indexOfLast { it.active }
        assertTrue(
            firstInactive == -1 || lastActive < firstInactive,
            "a retired code outranks an active one",
        )
    }

    @Test
    fun `a code search finds its vaccine`() = runTest {
        val all = VaccineCatalog.all(context)
        val target = all.first { it.active }
        val results = VaccineCatalog.search(context, target.code)
        assertTrue(results.any { it.code == target.code }, "code ${target.code} not found")
    }

    @Test
    fun `no match returns empty`() = runTest {
        assertTrue(VaccineCatalog.search(context, "zzzqqqx").isEmpty())
    }
}
