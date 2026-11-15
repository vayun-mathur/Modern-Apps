package com.vayunmathur.maps.data

import com.vayunmathur.library.map.GeoPoint
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The slice of the GeoJSON object model the app actually uses, replacing
 * `org.maplibre.spatialk:geojson`.
 *
 * Coordinates are [GeoPoint] from `:library:map` rather than a second point type, which is what
 * lets the `GeoInterop` bridge go away: the renderer, the router and the pin layers now all
 * speak one type instead of converting at every boundary.
 *
 * These are in-memory only — nothing parses or emits GeoJSON documents, so there is no
 * `type` discriminator, `bbox` or `id` here.
 */
sealed interface Geometry

/** Immutable value describing point; carries coordinates. */
data class Point(val coordinates: GeoPoint) : Geometry

/** Immutable value describing line string; carries coordinates. */
data class LineString(val coordinates: List<GeoPoint>) : Geometry

/** Immutable value describing feature; carries geometry, properties. */
data class Feature<G : Geometry, P>(val geometry: G, val properties: P)

/**
 * Encodes a [GeoPoint] as the GeoJSON coordinate array `[longitude, latitude]`, which is the
 * shape the spatialk `Position` this replaced wrote.
 *
 * Longitude first — the reverse of the usual lat/lng convention, and the easiest thing here to
 * get backwards. [GeoPoint]'s own constructor is also longitude-first, so the two agree.
 */
object GeoPointAsCoordinates : KSerializer<GeoPoint> {
    private val delegate = ListSerializer(Double.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: GeoPoint) =
        encoder.encodeSerializableValue(delegate, listOf(value.longitude, value.latitude))

    override fun deserialize(decoder: Decoder): GeoPoint {
        val coordinates = decoder.decodeSerializableValue(delegate)
        return GeoPoint(longitude = coordinates[0], latitude = coordinates[1])
    }
}
