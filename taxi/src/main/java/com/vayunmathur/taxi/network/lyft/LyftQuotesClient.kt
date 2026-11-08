package com.vayunmathur.taxi.network.lyft

import com.vayunmathur.library.log.Log
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.QuoteResult
import kotlin.math.roundToLong

/**
 * Fare quotes from `POST /v2/offerings` (`/pb.api.endpoints.v1.offers.Offers/ReadOffersV2`).
 *
 * Request/response shapes recovered from the production APK (`me.lyft.android` v2026.29.3) with
 * jadx — see `lyft-re/api-notes.md` §3. The request is the proto-JSON form of `OffersRequestDTO`.
 * On success the server negotiates a body: it may reply with proto-JSON **or** binary protobuf
 * depending on `Accept`, so we read raw bytes and parse whichever came back (protobuf via the
 * hand-rolled reader, keyed by the same field tags as the DTOs).
 */
internal class LyftQuotesClient(private val session: LyftApiSession) {
    private val json
        get() = session.json
    private val offersParser: LyftOffersParser by lazy { LyftOffersParser(json) }

    suspend fun quotes(pickup: Place, dropoff: Place): QuoteResult {
        val token = session.accessToken() ?: return QuoteResult.NotSignedIn
        return readOffers(token, "${LyftAuth.BASE}/v2/offerings", offersBody(pickup, dropoff, null, null))
    }

    /**
     * Re-quotes an existing offer set via `/v2/offerings/update` (`ReadOffersV2Update`), carrying
     * [lastOffersId] (the previous `offers_response_id`) and [purchaseSessionId] so the server
     * refreshes the same session's prices/cost-tokens. Used to replace fares whose cost token is
     * about to expire. Response shape matches `/v2/offerings`.
     */
    suspend fun updateQuotes(
        pickup: Place,
        dropoff: Place,
        lastOffersId: String?,
        purchaseSessionId: String?,
    ): QuoteResult {
        val token = session.accessToken() ?: return QuoteResult.NotSignedIn
        return readOffers(
            token,
            "${LyftAuth.BASE}/v2/offerings/update",
            offersBody(pickup, dropoff, lastOffersId, purchaseSessionId),
        )
    }

    /**
     * OffersRequestDTO body shared by `/v2/offerings` and `/v2/offerings/update`. See api-notes §3:
     * origin(1)+destination(2) as E6LatLng, request_source=OFFER_SELECTOR,
     * offer_selector_type=CATEGORIZED_VERTICAL, a session id, and an entry context. The update
     * path additionally carries `last_offers_id` + `purchase_session_id` to refresh a session.
     */
    fun offersBody(
        pickup: Place,
        dropoff: Place,
        lastOffersId: String?,
        purchaseSessionId: String?,
    ): String = buildString {
        append("{")
        append(""""origin":${e6(pickup)},""")
        append(""""destination":${e6(dropoff)},""")
        append(""""request_source":"OFFER_SELECTOR",""")
        append(""""offer_selector_type":"CATEGORIZED_VERTICAL",""")
        append(""""offer_selector_session_id":"${java.util.UUID.randomUUID()}",""")
        lastOffersId?.let { append(""""last_offers_id":"$it",""") }
        purchaseSessionId?.let { append(""""purchase_session_id":"$it",""") }
        append(""""request_entry_context":{"entry_point":{"home":{}}}""")
        append("}")
    }

    private suspend fun readOffers(token: String, url: String, body: String): QuoteResult {
        val resp = NetworkClient.execute(
            url = url,
            method = "POST",
            // Reuse the exact standard header set the app sends (user-agent, user-device,
            // x-design-id, locale, timestamps). Without a valid `user-agent` the server
            // rejects the call outright ("invalid user agent") even with a good token.
            headers = LyftAuth.commonHeaders() + mapOf(
                "Authorization" to "Bearer $token",
                "Content-Type" to "application/json",
                "Accept" to "application/x-protobuf, application/json",
            ),
            body = body,
        )
        val contentType = resp.header("Content-Type")?.lowercase().orEmpty()
        Log.debug(TAG, "POST $url -> ${resp.status} ($contentType, ${resp.bytes.size} bytes)")
        if (!resp.isSuccess) {
            // Error bodies come back as JSON regardless of Accept; surface the reason.
            return QuoteResult.Failed(session.httpError(resp))
        }

        val isProto = contentType.contains("protobuf") || contentType.contains("octet-stream")
        var parsed = runCatching {
            if (isProto) offersParser.parseProto(resp.bytes) else offersParser.parseJson(resp.text)
        }.onFailure { Log.status(TAG, "primary parse failed (proto=$isProto)", it) }.getOrDefault(ParsedOffers.EMPTY)
        // Content-Type can lie or be absent; fall back to the other codec before giving up.
        if (parsed.quotes.isEmpty()) {
            parsed = runCatching {
                if (isProto) offersParser.parseJson(resp.text) else offersParser.parseProto(resp.bytes)
            }.getOrDefault(ParsedOffers.EMPTY)
        }

        return if (parsed.quotes.isEmpty()) {
            QuoteResult.Failed("Lyft 200 ($contentType) but no offers parsed")
        } else {
            QuoteResult.Success(
                quotes = parsed.quotes,
                purchaseSessionId = parsed.purchaseSessionId,
                offersResponseId = parsed.offersResponseId,
            )
        }
    }

    /** A single `E6LatLngDTO`: lat/lng in integer micro-degrees (degrees × 1e6). */
    private fun e6(place: Place): String {
        val latE6 = (place.location.latitude * MICRODEGREES_PER_DEGREE).roundToLong()
        val lngE6 = (place.location.longitude * MICRODEGREES_PER_DEGREE).roundToLong()
        return """{"latitude_e6":$latE6,"longitude_e6":$lngE6}"""
    }

    private companion object {
        private const val TAG = "LyftQuotes"
        private const val MICRODEGREES_PER_DEGREE = 1_000_000.0
    }
}
