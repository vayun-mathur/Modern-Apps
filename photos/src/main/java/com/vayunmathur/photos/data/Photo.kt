package com.vayunmathur.photos.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable

@Serializable
data class VideoData(val duration: Long)

/**
 * GPano geometry parsed from a photo's XMP. Present ⇒ the photo is a
 * 360/panorama and should be viewed in the sphere renderer. Describes how the
 * stored image maps onto the full equirectangular sphere.
 */
@Serializable
data class PanoData(
    val fullWidth: Int,
    val fullHeight: Int,
    val croppedWidth: Int,
    val croppedHeight: Int,
    val croppedLeft: Int,
    val croppedTop: Int,
)

@Serializable
@Entity(indices = [Index(value = ["date"])])
data class Photo(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val name: String,
    val uri: String,
    val date: Long,
    val width: Int,
    val height: Int,
    val dateModified: Long,
    val exifSet: Boolean,
    val lat: Double?,
    val long: Double?,
    @Embedded
    val videoData: VideoData?,
    @Embedded
    val panoData: PanoData?,
    val isTrashed: Boolean = false,
    // True once this photo has been scanned for faces (mirrors [exifSet]); keeps
    // the face indexer from re-processing the same photo on every sync.
    val faceScanned: Boolean = false,
    // Text recognised from the image by on-device OCR (null until scanned). The
    // search bar matches against this. Videos and un-scanned photos stay null.
    val ocrText: String? = null,
    // True once this photo has been through OCR (mirrors [faceScanned]); keeps
    // the OCR worker from re-processing the same photo on every sync.
    val ocrScanned: Boolean = false,
    // L2-normalised semantic image embedding (float[] packed little-endian as
    // bytes), produced by OpenAssistant (SigLIP2); null until scanned. Semantic
    // search cosine-compares the query's text embedding against these. Videos and
    // un-scanned photos stay null.
    val clipEmbedding: ByteArray? = null,
    // True once this photo has been sent to the embedder (mirrors [ocrScanned]);
    // keeps the worker from re-embedding the same photo on every sync.
    val clipScanned: Boolean = false,
) : DatabaseItem
