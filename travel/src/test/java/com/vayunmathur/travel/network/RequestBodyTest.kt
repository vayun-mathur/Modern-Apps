package com.vayunmathur.travel.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** POST bodies must be real JSON (the network client would otherwise send `toString()`). */
class RequestBodyTest {
    @Test
    fun tripRequestEncodesAsJsonWithDefaults() {
        val body = travelRequestBody(
            TripRequestDto(
                offerIds = listOf("o1"),
                passengers = listOf(PassengerInputDto(id = "pax_1", givenName = "Ann", familyName = "Doe")),
                contact = TripContactDto(email = "a@b.co", phoneNumber = "+14155550123"),
            )
        )!!
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("o1", json["offerIds"]!!.jsonArray[0].jsonPrimitive.content)
        val pax = json["passengers"]!!.jsonArray[0].jsonObject
        assertEquals("Ann", pax["givenName"]!!.jsonPrimitive.content)
        // Empty defaults the server requires are still present…
        assertEquals("", pax["title"]!!.jsonPrimitive.content)
        assertEquals("", pax["bornOn"]!!.jsonPrimitive.content)
        // …and nulls are omitted.
        assertFalse("identityDocument" in pax)
    }

    @Test
    fun emptyStringMeansNoBody() {
        assertNull(travelRequestBody(""))
    }
}
