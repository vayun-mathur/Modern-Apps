package com.vayunmathur.maps

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object OSM {
    suspend fun getTags(id: ULong): Map<String, String> {
        val client = HttpClient() {
            install(ContentNegotiation) {
                json(Json{
                    ignoreUnknownKeys = true
                })
            }
        }
        val response = client.get("https://overpass-api.de/api/interpreter?data=[out:json];node($id);out;").body<JsonObject>()
        val tags = response["elements"]!!.jsonArray[0].jsonObject["tags"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        client.close()
        return tags
    }
}