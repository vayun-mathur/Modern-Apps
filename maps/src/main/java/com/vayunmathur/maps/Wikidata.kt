package com.vayunmathur.maps

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

object Wikidata {
    suspend fun get(wikidata: String): Wikidata {
        val client = HttpClient() {
            install(ContentNegotiation) {
                json(Json{
                    ignoreUnknownKeys = true
                })
            }
        }
        val wikidata = client.get("https://www.wikidata.org/w/rest.php/wikibase/v1/entities/items/${wikidata}").body<Wikidata>()
        client.close()
        return wikidata
    }

    @Serializable
    data class Wikidata(
        val id: String,
        val statements: Map<String, List<Statement>>,
        val sitelinks: Map<String, Sitelink>
    ) {
        fun getProperty(property: String) = statements[property]?.first()?.value?.content?.jsonPrimitive?.content
        fun getWikipedia() = sitelinks["enwiki"]?.url

        @Serializable
        data class Statement(
            val id: String,
            val value: Value,
        ) {
            @Serializable
            data class Value(val content: JsonElement? = null)
        }
        @Serializable
        data class Sitelink(
            val url: String,
        )
    }
}