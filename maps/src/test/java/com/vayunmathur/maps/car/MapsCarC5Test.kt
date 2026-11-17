package com.vayunmathur.maps.car
import com.vayunmathur.maps.platform.CarSearchScreen
import com.vayunmathur.maps.data.string

import android.app.Application
import androidx.car.app.model.ListTemplate
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.maps.data.PoiCategories
import com.vayunmathur.maps.data.PoiIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * C5 car-search branches: a populated result list (titles, category
 * subtitles, place metadata), the loading template shape, and a row tap
 * with no location fix, which must surface the route-failed message
 * instead of routing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapsCarC5Test {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun setField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun record(name: String, type: Int) = PoiIndex.PoiRecord(
        latE7 = 377_749_000, lonE7 = -1_224_194_000, type = type, name = name,
    )

    private fun listOf(screen: CarSearchScreen): ListTemplate =
        (screen.onGetTemplate() as MapWithContentTemplate).contentTemplate as ListTemplate

    @Test
    fun `populated results render titles subtitles and places`() {
        val carContext = TestCarContext.createCarContext(app)
        val screen = CarSearchScreen(carContext)
        setField(
            screen, "results",
            listOf(record("Blue Bottle Coffee", 1), record("Mystery Spot", 999)),
        )
        setField(screen, "message", null)
        val items = listOf(screen).singleList
        assertNotNull(items)
        assertEquals(2, items.items.size)
        val first = items.items[0] as androidx.car.app.model.Row
        assertEquals("Blue Bottle Coffee", first.title.toString())
        // A known category contributes its label as the row's second line.
        assertTrue(first.texts.isNotEmpty(), "category subtitle missing")
        assertEquals(PoiCategories.label(1), first.texts[0].toString())
        assertNotNull(first.metadata)
        // An unknown type falls back to the generic "Place" label.
        assertEquals("Place", (items.items[1] as androidx.car.app.model.Row).texts[0].toString())
    }

    @Test
    fun `loading template carries no list`() {
        val carContext = TestCarContext.createCarContext(app)
        val screen = CarSearchScreen(carContext)
        setField(screen, "loading", true)
        val list = listOf(screen)
        assertTrue(list.isLoading)
        assertEquals(null, list.singleList)
    }

    @Test
    fun `tapping a result without a location fix reports route failure`() {
        val carContext = TestCarContext.createCarContext(app)
        val screen = CarSearchScreen(carContext)
        setField(screen, "results", listOf(record("Blue Bottle Coffee", 1)))
        setField(screen, "message", null)
        // No ACCESS_FINE_LOCATION: lastKnownPosition is null by contract.
        val row = listOf(screen).singleList!!.items.single() as androidx.car.app.model.Row
        row.onClickDelegate!!.sendClick(
            object : androidx.car.app.OnDoneCallback {
                override fun onSuccess(response: androidx.car.app.serialization.Bundleable?) {}
                override fun onFailure(response: androidx.car.app.serialization.Bundleable) {}
            },
        )
        // The failure message is latched on the screen (it becomes the
        // list's empty message once results clear); the row stays listed.
        val messageField = screen.javaClass.getDeclaredField("message")
        messageField.isAccessible = true
        assertEquals(
            carContext.getString(com.vayunmathur.maps.R.string.car_route_failed),
            messageField.get(screen)?.toString(),
        )
        assertNotNull(listOf(screen).singleList)
    }
}
