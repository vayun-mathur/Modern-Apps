package com.vayunmathur.fooddelivery

import com.vayunmathur.fooddelivery.data.ApiResponse
import com.vayunmathur.fooddelivery.data.Merchant
import com.vayunmathur.fooddelivery.data.MerchantsWrapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BitesApiTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource(name)) { "missing fixture $name" }.readText()

    @Test
    fun testRawResponse() {
        val raw = fixture(STORES_FIXTURE)
        assertTrue(raw.isNotEmpty(), "Response was empty")
        assertTrue(json.parseToJsonElement(raw).jsonObject["data"]!!.jsonObject["merchants"]!!.jsonArray.size == 3)
    }

    @Test
    fun testParseSingleMerchant() {
        val singleMerchantJson = """
        {
            "id": 2615,
            "name": "Test Restaurant",
            "addressStreet": "123 Main St",
            "addressCity": "San Mateo",
            "addressState": "CA",
            "addressZip": "94401",
            "latitude": 37.5,
            "longitude": -122.3,
            "logoUrl": "https://example.com/logo.jpg",
            "imageUrl": "https://example.com/image.jpg",
            "isOpen": true,
            "isDeliveryEnabled": true,
            "isPickupEnabled": true,
            "closingTime": "Closes at 8:00PM",
            "nextOpenWindow": "ASAP",
            "averageRating": null,
            "totalRatings": null,
            "rewardsPercentage": null,
            "brandColor": null,
            "brand": null,
            "merchantTags": ["Pizza"],
            "items": [],
            "sortOrder": null,
            "distance": null,
            "freeDeliveryThreshold": 1000,
            "doordashMarkup": null,
            "doordashMarkupComparison": null,
            "storefrontAlias": "test",
            "extraFieldThatShouldBeIgnored": "hello"
        }
        """.trimIndent()

        val merchant = json.decodeFromString<Merchant>(singleMerchantJson)
        println("Parsed merchant: ${merchant.name}, id=${merchant.id}")
        assertTrue(merchant.name == "Test Restaurant")
        assertTrue(merchant.averageRating == null)
        assertTrue(merchant.brandColor == null)
    }

    @Test
    fun testParseWrappedResponse() {
        val wrappedJson = """
        {
            "message": "success",
            "data": {
                "merchants": [
                    {
                        "id": 1,
                        "name": "Restaurant A",
                        "addressStreet": "",
                        "addressCity": "",
                        "addressState": "",
                        "addressZip": "",
                        "latitude": 0.0,
                        "longitude": 0.0,
                        "logoUrl": "",
                        "imageUrl": "",
                        "isOpen": true,
                        "closingTime": "",
                        "nextOpenWindow": "",
                        "merchantTags": [],
                        "items": [],
                        "freeDeliveryThreshold": 0,
                        "averageRating": 4.5,
                        "totalRatings": 100,
                        "rewardsPercentage": 5.0,
                        "brandColor": "#ff0000"
                    }
                ],
                "total": 1,
                "tags": ["Pizza"]
            }
        }
        """.trimIndent()

        val resp = json.decodeFromString<ApiResponse<MerchantsWrapper>>(wrappedJson)
        println("Parsed wrapper: message=${resp.message}, merchants=${resp.data?.merchants?.size}")
        assertTrue(resp.data?.merchants?.size == 1)
        assertTrue(resp.data?.merchants?.first()?.name == "Restaurant A")
    }

    @Test
    fun testParseRealApiResponse() {
        val resp = json.decodeFromString<ApiResponse<MerchantsWrapper>>(fixture(STORES_FIXTURE))
        assertEquals("success", resp.message)
        val merchants = resp.data?.merchants.orEmpty()
        assertEquals(listOf(2615, 3001, 4100), merchants.map { it.id })

        val slice = merchants[0]
        assertEquals(4.7, slice.displayRating)
        assertEquals(312, slice.displayTotalRatings)
        assertEquals(25.0, slice.freeDeliveryThresholdDollars)
        assertEquals("https://cdn.example.com/brand/slice-hero.jpg", slice.displayImage)
        assertEquals("https://cdn.example.com/brand/slice-logo.png", slice.displayLogo)
        assertEquals("123 Main St, San Mateo, CA", slice.address)
        assertEquals(listOf("Margherita", "Garlic Knots"), slice.items.map { it.name })
        assertNull(slice.items[0].imageUrl)

        val taqueria = merchants[1]
        assertEquals(0.0, taqueria.displayRating)
        assertEquals(0, taqueria.displayTotalRatings)
        assertNull(taqueria.brand)
        assertEquals("https://cdn.example.com/m/3001.jpg", taqueria.displayImage)
        assertEquals(", Burlingame, CA", taqueria.address)

        val sparse = merchants[2]
        assertEquals("Noodle Bar", sparse.name)
        assertTrue(sparse.isOpen)
        assertEquals("", sparse.displayImage)
    }

    private companion object {
        const val STORES_FIXTURE = "merchants_all_stores.json"
    }
}
