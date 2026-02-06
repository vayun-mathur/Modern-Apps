package com.vayunmathur.maps

import android.content.Context
import com.vayunmathur.maps.data.Feature1
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.spatialk.geojson.Position
import org.wololo.flatgeobuf.generated.Header
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.maplibre.spatialk.geojson.Geometry as SKGeometry
import org.maplibre.spatialk.geojson.MultiPolygon as SKMultiPolygon
import org.maplibre.spatialk.geojson.Polygon as SKPolygon
import org.wololo.flatgeobuf.generated.Feature as FgbFeature
import org.wololo.flatgeobuf.generated.Geometry as FgbGeometry

fun fgbToSpatialK(fgbGeom: FgbGeometry, actualType: Int): SKGeometry {
    return when (actualType) {
        3 -> { // Polygon
            SKPolygon(extractRings(fgbGeom))
        }
        6 -> { // MultiPolygon
            val partsCount = fgbGeom.partsLength()
            val polygons = mutableListOf<List<List<Position>>>()
            for (i in 0 until partsCount) {
                val part = fgbGeom.parts(i)!!
                polygons.add(extractRings(part))
            }
            SKMultiPolygon(polygons)
        }
        // Sometimes "Unknown" files contain points/lines you don't need for the mask
        else -> throw IllegalArgumentException("Feature Geometry Type $actualType not supported for masking")
    }
}

private fun extractRings(fgbGeom: FgbGeometry): List<List<Position>> {
    val xy = fgbGeom.xyVector() ?: return emptyList()
    val ends = fgbGeom.endsVector()
    val rings = mutableListOf<List<Position>>()
    var start = 0

    if (ends == null || ends.length() == 0) {
        val posList = mutableListOf<Position>()
        for (i in 0 until xy.length() step 2) {
            posList.add(Position(xy.get(i), xy.get(i + 1)))
        }
        rings.add(posList)
    } else {
        for (i in 0 until ends.length()) {
            // ends.get(i) is the number of vertices, but xy is [x, y]
            // so we multiply the vertex index by 2 to get the buffer index
            val end = (ends.get(i) * 2).toInt()
            val posList = mutableListOf<Position>()
            for (j in start until end step 2) {
                posList.add(Position(xy.get(j), xy.get(j + 1)))
            }
            rings.add(posList)
            start = end
        }
    }
    return rings
}

object CountryMap {
    fun getAdmin0(context: Context, iso3166_1: String): Feature1? {
        context.assets.open("admin0.fgb").use { inputStream ->
            val bytes = inputStream.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            // 1. Skip Magic Bytes
            buffer.position(8)

            // 2. Read Header
            val headerLength = buffer.int
            val headerBuffer = buffer.slice().limit(headerLength) as ByteBuffer
            val header = Header.getRootAsHeader(headerBuffer)
            buffer.position(buffer.position() + headerLength)

            // 3. Skip Spatial Index
            // FlatGeobuf index size depends on the number of features and the branching factor
            if (header.indexNodeSize() > 0) {
                val featureCount = header.featuresCount()
                val nodeSize = header.indexNodeSize().toInt()

                // Calculate tree size manually
                var nodes = featureCount
                var currNodes = featureCount
                while (currNodes > 1) {
                    currNodes = (currNodes + nodeSize - 1) / nodeSize
                    nodes += currNodes
                }
                val treeSize = nodes * 40

                buffer.position(buffer.position() + treeSize.toInt())
            }

            // 4. Iterate Features
            while (buffer.hasRemaining()) {
                // Each feature is prefixed by its size (4 bytes)
                val featureSize = buffer.int
                val featureBuffer = buffer.slice().limit(featureSize) as ByteBuffer
                val fgbFeature = FgbFeature.getRootAsFeature(featureBuffer)

                // 5. Check properties for "name_en"
                // Properties are stored as a FlatBuffer ByteVector
                // We can use the wololo helper to parse JUST the properties
                val properties = JsonObject(getProperties(fgbFeature, header))

                if (properties["ISO_A2"]?.jsonPrimitive?.content.equals(iso3166_1, ignoreCase = true)) {
                    // Found it! Convert only this ONE feature to wololo GeoJSON
                    val skGeometry = fgbToSpatialK(fgbFeature.geometry(),
                        fgbFeature.geometry().type()
                    )
                    return Feature1(skGeometry, properties)
                }

                // Move position to the next feature
                buffer.position(buffer.position() + featureSize)
            }

            return null
        }
    }
    @OptIn(ExperimentalSerializationApi::class)
    fun getAdmin1(context: Context, iso3166_2: String): Feature1? {
        context.assets.open("admin1.fgb").use { inputStream ->
            val bytes = inputStream.readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            // 1. Skip Magic Bytes
            buffer.position(8)

            // 2. Read Header
            val headerLength = buffer.int
            val headerBuffer = buffer.slice().limit(headerLength) as ByteBuffer
            val header = Header.getRootAsHeader(headerBuffer)
            buffer.position(buffer.position() + headerLength)

            // 3. Skip Spatial Index
            // FlatGeobuf index size depends on the number of features and the branching factor
            if (header.indexNodeSize() > 0) {
                val featureCount = header.featuresCount()
                val nodeSize = header.indexNodeSize().toInt()

                // Calculate tree size manually
                var nodes = featureCount
                var currNodes = featureCount
                while (currNodes > 1) {
                    currNodes = (currNodes + nodeSize - 1) / nodeSize
                    nodes += currNodes
                }
                val treeSize = nodes * 40

                buffer.position(buffer.position() + treeSize.toInt())
            }

            // 4. Iterate Features
            while (buffer.hasRemaining()) {
                // Each feature is prefixed by its size (4 bytes)
                val featureSize = buffer.int
                val featureBuffer = buffer.slice().limit(featureSize) as ByteBuffer
                val fgbFeature = FgbFeature.getRootAsFeature(featureBuffer)

                // 5. Check properties for "name_en"
                // Properties are stored as a FlatBuffer ByteVector
                // We can use the wololo helper to parse JUST the properties
                val properties = JsonObject(getProperties(fgbFeature, header))

                if (properties["iso_3166_2"]?.jsonPrimitive?.content.equals(iso3166_2, ignoreCase = true)) {
                    // Found it! Convert only this ONE feature to wololo GeoJSON
                    val skGeometry = fgbToSpatialK(fgbFeature.geometry(), fgbFeature.geometry().type().toInt())
                    return Feature1(skGeometry, properties)
                }

                // Move position to the next feature
                buffer.position(buffer.position() + featureSize)
            }

            return null
        }
    }
}

fun getProperties(fgbFeature: FgbFeature, header: Header): Map<String, JsonElement> {
    val properties = mutableMapOf<String, JsonElement>()
    val bb = fgbFeature.propertiesAsByteBuffer() ?: return properties
    bb.order(ByteOrder.LITTLE_ENDIAN)

    val columnCount = header.columnsLength()

    while (bb.hasRemaining()) {
        val keyIndex = bb.short.toInt() and 0xFFFF

        val column = header.columns(keyIndex)
        val key = column?.name() ?: "attr_$keyIndex"
        val valueType = column.type()

        val value: JsonElement? = when (valueType) {
            0 -> JsonPrimitive(bb.get().toInt())             // Byte
            1 -> JsonPrimitive(bb.get().toInt() and 0xFF)    // UByte
            2 -> JsonPrimitive(bb.get().toInt() != 0)        // Bool
            3 -> JsonPrimitive(bb.short.toInt())             // Short
            4 -> JsonPrimitive(bb.short.toInt() and 0xFFFF) // UShort
            5 -> JsonPrimitive(bb.int)                       // Int
            6 -> JsonPrimitive(bb.int.toLong() and 0xFFFFFFFFL) // UInt
            7 -> JsonPrimitive(bb.long)                      // Long
            8 -> JsonPrimitive(bb.long)                      // ULong
            9 -> JsonPrimitive(bb.float)                     // Float
            10 -> JsonPrimitive(bb.double)                   // Double
            11, 12, 13 -> { // String, Json, DateTime
                val len = bb.int
                val bytes = ByteArray(len)
                bb.get(bytes)
                JsonPrimitive(String(bytes, Charsets.UTF_8))
            }
            14 -> { // Binary
                val len = bb.int
                bb.position(bb.position() + len)
                null
            }
            else -> null
        }
        if (value != null) properties[key] = value
    }
    return properties
}