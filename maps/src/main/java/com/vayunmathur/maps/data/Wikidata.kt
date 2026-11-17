package com.vayunmathur.maps.data
import com.vayunmathur.library.network.NetworkClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

/** Singleton holding wikidata state and operations. */
object Wikidata {
    // Proxied via self-hosted to avoid direct third-party (Wikidata rate-limits, privacy).
    // Server forwards to https://www.wikidata.org/w/rest.php/wikibase/v1/entities/items/{id} with 24h cache.
    private const val BASE_URL = "https://api.maproj.org/api/wikidata/items"

    suspend fun get(wikidata: String): Wikidata {
        return NetworkClient.getJson("$BASE_URL/$wikidata")
    }

    /** Immutable value describing wikidata. */
    @Serializable
    data class Wikidata(
        val id: String,
        val statements: Map<String, List<Statement>>,
        val sitelinks: Map<String, Sitelink>
    ) {
        fun getProperty(property: String) = statements[property]?.first()?.value?.content?.jsonPrimitive?.content
        fun getWikipedia() = sitelinks["enwiki"]?.url

        /** Immutable value describing statement. */
        @Serializable
        data class Statement(
            val id: String,
            val value: Value,
        ) {
            /** Immutable value describing value; carries content. */
            @Serializable
            data class Value(val content: JsonElement? = null)
        }
        /** Immutable value describing sitelink. */
        @Serializable
        data class Sitelink(
            val url: String,
        )
    }
}
