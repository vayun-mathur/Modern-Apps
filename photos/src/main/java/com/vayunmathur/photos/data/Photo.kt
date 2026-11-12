package com.vayunmathur.photos.data

import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable

@Serializable
data class VideoData(val duration: Long)

/**
 * GPano geometry parsed from a photo's XMP. Present ⇒ the photo is a
 * panorama/360 photo. [projectionType] distinguishes an "equirectangular" full
 * sphere (viewed in the sphere renderer) from a "cylindrical" flat panorama
 * (viewed as a wide, horizontally-scrolling rectangle). Describes how the stored
 * image maps onto the full panorama.
 */
@Serializable
data class PanoData(
    val fullWidth: Int,
    val fullHeight: Int,
    val croppedWidth: Int,
    val croppedHeight: Int,
    val croppedLeft: Int,
    val croppedTop: Int,
    val projectionType: String? = null,
) {
    /**
     * True for a full 360 sphere (equirectangular); false for a flat
     * (cylindrical) panorama. Legacy rows with no recorded projection are
     * treated as spheres, matching the original equirectangular-only behaviour.
     */
    val isSphere: Boolean get() = !projectionType.equals("cylindrical", ignoreCase = true)
}

@Serializable
@Entity(indices = [Index(value = ["date"]), Index(value = ["isTrashed", "duration"])])
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
    // MediaStore MIME_TYPE. Null only on rows written before the column existed,
    // which the next sync backfills.
    val mimeType: String? = null,
    // The same OCR result as [ocrText] but with per-line geometry, serialised as
    // an [OcrLayout] JSON blob, so the viewer can lay an invisible selectable
    // text layer over the image. Null for videos, for photos with no text, and
    // for rows OCR'd before this column existed (the viewer backfills those).
    val ocrBoxes: String? = null,
    // MediaStore bucket (BUCKET_DISPLAY_NAME): the album/folder the item lives
    // in. Null only on rows written before the column existed, which the next
    // sync backfills; the Albums view shows those as Unknown meanwhile.
    val album: String? = null,
    // MediaStore IS_FAVORITE, mirrored like [isTrashed]: MediaStore owns the
    // flag (so favourites survive a reinstall and match other gallery apps),
    // the sync copies it here, and the Favourites album reads it from here.
    val isFavorite: Boolean = false,
) : DatabaseItem {
    /** True for an animated image, which plays rather than showing a single frame. */
    val isGif: Boolean get() = mimeType.equals("image/gif", ignoreCase = true)
}
