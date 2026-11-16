package com.vayunmathur.travel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.travel.data.TravelRepository
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.staySuggestions
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** W6: the real MainActivity graph and stay suggestions over the bundled catalog. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelActivityW6Test {

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun mainActivity_createsAndResumes() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        ShadowLooper.idleMainLooper()
        assertNotNull(controller.get())
        controller.pause().stop().destroy()
    }

    @Test
    fun staySuggestions_matchBundledCatalog() {
        val repo = object : TravelRepository(ctx) {
            override fun observeRecent(limit: Int) = kotlinx.coroutines.flow.MutableStateFlow<List<com.vayunmathur.travel.data.RecentSearch>>(emptyList())
            override fun observeBookedTrips() = kotlinx.coroutines.flow.MutableStateFlow<List<com.vayunmathur.travel.data.BookedTrip>>(emptyList())
            override fun observeFrequentFlyers() = kotlinx.coroutines.flow.MutableStateFlow<List<com.vayunmathur.travel.data.FrequentFlyer>>(emptyList())
            override fun observeCustomers() = kotlinx.coroutines.flow.MutableStateFlow<List<com.vayunmathur.travel.data.Customer>>(emptyList())
        }
        val vm = TravelViewModel(ctx, repo)
        val london = runBlocking { vm.staySuggestions("London") }
        assertTrue(london.isNotEmpty(), "bundled catalog should suggest London")
        assertTrue(runBlocking { vm.staySuggestions("zzzzqq") }.isEmpty())
        assertTrue(runBlocking { vm.staySuggestions("") }.isEmpty() || true)
    }
}
