package com.vayunmathur.photos.domain

import com.vayunmathur.photos.data.PanoData

/**
 * Parses GPano XMP (as exposed by androidx ExifInterface's TAG_XMP) into
 * [PanoData]. Returns null unless the packet declares a panorama meant for a
 * panorama viewer. Both "equirectangular" (360 sphere) and "cylindrical" (flat
 * panorama) projections are recognised; the projection type is preserved so the
 * UI can pick the right viewer.
 */
object PanoXmpParser {

    fun parse(xmp: String?): PanoData? {
        if (xmp.isNullOrEmpty()) return null
        if (!isPanoramaViewerPacket(xmp)) return null
        val projection = validProjection(xmp) ?: return null
        return panoDimensions(xmp, projection)
    }

    private fun isPanoramaViewerPacket(xmp: String): Boolean =
        attr(xmp, "UsePanoramaViewer").equals("true", ignoreCase = true)

    private fun validProjection(xmp: String): String? {
        val projection = attr(xmp, "ProjectionType")?.trim()?.lowercase() ?: return null
        if (projection != "equirectangular" && projection != "cylindrical") return null
        return projection
    }

    private fun panoDimensions(xmp: String, projection: String): PanoData? {
        val fullWidth = intAttr(xmp, "FullPanoWidthPixels") ?: return null
        val fullHeight = intAttr(xmp, "FullPanoHeightPixels") ?: return null
        val croppedWidth = intAttr(xmp, "CroppedAreaImageWidthPixels") ?: return null
        val croppedHeight = intAttr(xmp, "CroppedAreaImageHeightPixels") ?: return null
        val croppedLeft = intAttr(xmp, "CroppedAreaLeftPixels") ?: 0
        val croppedTop = intAttr(xmp, "CroppedAreaTopPixels") ?: 0
        return PanoData(fullWidth, fullHeight, croppedWidth, croppedHeight, croppedLeft, croppedTop, projection)
    }

    // GPano fields appear either as attributes (GPano:Foo="123") or as child
    // elements (<GPano:Foo>123</GPano:Foo>); handle both, namespace-agnostic.
    private fun attr(xmp: String, name: String): String? {
        Regex("""[:\s]$name\s*=\s*["']([^"']*)["']""").find(xmp)?.let { return it.groupValues[1] }
        Regex("""<[^>]*:$name>\s*([^<]*)\s*</""").find(xmp)?.let { return it.groupValues[1].trim() }
        return null
    }

    private fun intAttr(xmp: String, name: String): Int? = attr(xmp, name)?.trim()?.toIntOrNull()
}
