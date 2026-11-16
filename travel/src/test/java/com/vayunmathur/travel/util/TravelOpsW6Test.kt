package com.vayunmathur.travel.util

import android.app.Application
import android.database.MatrixCursor
import android.provider.ContactsContract
import androidx.compose.runtime.saveable.SaverScope
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.data.TravelRepository
import com.vayunmathur.travel.network.MissingDetailsException
import com.vayunmathur.travel.network.OfferDto
import com.vayunmathur.travel.network.travelHttpError
import com.vayunmathur.travel.ui.Cabin
import com.vayunmathur.travel.ui.FlightFormSaver
import com.vayunmathur.travel.ui.FlightFormState
import com.vayunmathur.travel.ui.MaxStops
import com.vayunmathur.travel.ui.PassengerMix
import com.vayunmathur.travel.ui.StayFormSaver
import com.vayunmathur.travel.ui.StayFormState
import com.vayunmathur.travel.ui.TripType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W6: route serialization, search-form savers, HTTP error decoding and the
 * offer-selection state transitions — none of which need a screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TravelOpsW6Test {

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private val scope = SaverScope { true }

    @Test
    fun everyRoute_serializesAndRoundTrips() {
        val routes: List<Route> = listOf(
            Route.Home, Route.Trips, Route.Settings, Route.StayGuests,
            Route.OfferReview("off_1"), Route.Ancillaries("off_1"), Route.SeatMap("off_1", "seg_1"),
            Route.Passengers("off_1"), Route.Payment("off_1"), Route.Confirmation("ord_1"),
            Route.OrderDetail("ord_1"), Route.Cancel("ord_1"), Route.Change("ord_1"),
            Route.StayDetail("res_1", "Hotel One"), Route.StayConfirmation("book_1"),
            Route.FareSelect("out-sig", "in-sig"),
        )
        for (route in routes) {
            val text = Json.encodeToString(Route.serializer(), route)
            assertTrue(text.isNotBlank())
            assertEquals(route, Json.decodeFromString(Route.serializer(), text))
        }
    }

    @Test
    fun formSavers_roundTripEveryField() {
        val flight = FlightFormState(
            tripType = TripType.ONE_WAY, origin = "SFO", originText = "San Francisco",
            destination = "JFK", destinationText = "New York", depart = "2027-06-24",
            returnDate = "2027-06-28", mix = PassengerMix(adults = 2, childAges = listOf(7, 9), infantsInSeat = 1, infantsOnLap = 1),
            cabin = Cabin.BUSINESS, maxStops = MaxStops.NONSTOP,
        )
        assertEquals(flight, FlightFormSaver.restore(with(FlightFormSaver) { scope.save(flight) }!!))
        val stay = StayFormState(
            place = "place_1", placeText = "Paris", latitude = 48.85, longitude = 2.35,
            checkIn = "2027-07-01", checkOut = "2027-07-05", rooms = 2, guests = 3,
        )
        assertEquals(stay, StayFormSaver.restore(with(StayFormSaver) { scope.save(stay) }!!))
        // Null coordinates survive as null, not 0.0.
        val noCoords = StayFormState(placeText = "Nowhere")
        val restored = StayFormSaver.restore(with(StayFormSaver) { scope.save(noCoords) }!!)!!
        assertEquals(null, restored.latitude); assertEquals(null, restored.longitude)
        // Swap exchanges origin and destination including their labels.
        val swapped = flight.swapped()
        assertEquals("JFK", swapped.origin); assertEquals("SFO", swapped.destination)
        assertEquals("New York", swapped.originText)
    }

    @Test
    fun httpErrors_decodeMissingFieldsAndRetryAfter() {
        val missing = travelHttpError(422, """{"error":"bad","missingFields":["passport","dob"]}""", "fallback")
        assertTrue(missing is MissingDetailsException)
        assertEquals(listOf("passport", "dob"), (missing as MissingDetailsException).missingFields)
        // 422 with a prefix before the JSON and with malformed JSON both still classify.
        assertTrue(travelHttpError(422, "oops {not json", "fb") is MissingDetailsException)
        val unavailable = travelHttpError(503, """{"retryAfterSeconds":42}""", "fallback")
        assertTrue(unavailable is com.vayunmathur.travel.network.StillPricingException)
        assertEquals(42, (unavailable as com.vayunmathur.travel.network.StillPricingException).retryAfterSeconds)
        val generic = travelHttpError(500, """{"message":"server boom"}""", "fallback")
        assertTrue(generic.message!!.contains("server boom"))
        assertEquals("Something went wrong. Please try again.", travelHttpError(500, "", "fallback").message)
    }

    @Test
    fun selectOffers_seedsReviewAndClearsBookingState() {
        val repo = object : TravelRepository(ctx) {
            override fun observeRecent(limit: Int): kotlinx.coroutines.flow.Flow<List<com.vayunmathur.travel.data.RecentSearch>> =
                kotlinx.coroutines.flow.MutableStateFlow(emptyList())
            override fun observeBookedTrips(): kotlinx.coroutines.flow.Flow<List<com.vayunmathur.travel.data.BookedTrip>> =
                kotlinx.coroutines.flow.MutableStateFlow(emptyList())
            override fun observeFrequentFlyers(): kotlinx.coroutines.flow.Flow<List<com.vayunmathur.travel.data.FrequentFlyer>> =
                kotlinx.coroutines.flow.MutableStateFlow(emptyList())
            override fun observeCustomers(): kotlinx.coroutines.flow.Flow<List<com.vayunmathur.travel.data.Customer>> =
                kotlinx.coroutines.flow.MutableStateFlow(emptyList())
        }
        val vm = TravelViewModel(ctx, repo)
        val offers = listOf(
            OfferDto(offerId = "o1", totalAmount = "100.00", currency = "USD"),
            OfferDto(offerId = "o2", totalAmount = "120.00", currency = "USD"),
        )
        vm.selectOffers(offers)
        assertEquals("o1", vm.review.value.offer!!.offerId)
        assertEquals(listOf("o1", "o2"), vm.selectedOfferIds.value)
        assertEquals(BookingState.Idle, vm.booking.value)
        vm.selectOffer(offers[1])
        assertEquals(listOf("o2"), vm.selectedOfferIds.value)
        // Filter helpers mutate only the flights state.
        vm.setFareBrandFilter("Main")
        assertEquals("Main", vm.flights.value.filters.fareBrand)
        vm.clearFilters()
        assertEquals(null, vm.flights.value.filters.fareBrand)
        // partialOfferById misses cleanly when the partial flow is empty.
        assertEquals(null, vm.partialOfferById("nope"))
    }

    @Test
    fun contactCursor_displayNameFallbackSplitsName() {
        val uri = android.net.Uri.parse("content://w6/contact/1")
        val cursor = org.robolectric.fakes.RoboCursor()
        cursor.setColumnNames(listOf(
            ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA2, ContactsContract.Data.DATA3,
            ContactsContract.Data.DISPLAY_NAME,
        ))
        // Structured name row with blank given/family: display name must be split.
        cursor.setResults(arrayOf(
            arrayOf<Any?>(ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, "", "", "", "Mary Jane Watson"),
            arrayOf<Any?>(ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, "mary@example.com", "", "", "Mary Jane Watson"),
            arrayOf<Any?>(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, "+1 (555) 010-2030", "", "", "Mary Jane Watson"),
            arrayOf<Any?>(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, "1990-04-12", ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY, "", "Mary Jane Watson"),
        ))
        org.robolectric.Shadows.shadowOf(ctx.contentResolver).setCursor(uri, cursor)
        val info = readSessionContact(ctx, uri)!!
        assertEquals("Mary", info.givenName)
        assertEquals("Jane Watson", info.familyName)
        assertEquals("mary@example.com", info.email)
        assertEquals("+15550102030", info.phone)
        assertEquals("1990-04-12", info.bornOn)
    }
}
