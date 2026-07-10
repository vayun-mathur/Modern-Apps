package com.vayunmathur.photos.util

import com.vayunmathur.photos.data.PanoData

/**
 * Parses GPano XMP (as exposed by androidx ExifInterface's TAG_XMP) into
 * [PanoData]. Returns null unless the packet declares an equirectangular
 * panorama meant for a panorama viewer.
 */
object PanoXmpParser {

    fun parse(xmp: String?): PanoData? {
        if (xmp.isNullOrEmpty()) return null
        if (!attr(xmp, "UsePanoramaViewer").equals("true", ignoreCase = true)) return null
        if (!attr(xmp, "ProjectionType").equals("equirectangular", ignoreCase = true)) return null

        val fullWidth = intAttr(xmp, "FullPanoWidthPixels") ?: return null
        val fullHeight = intAttr(xmp, "FullPanoHeightPixels") ?: return null
        val croppedWidth = intAttr(xmp, "CroppedAreaImageWidthPixels") ?: return null
        val croppedHeight = intAttr(xmp, "CroppedAreaImageHeightPixels") ?: return null
        val croppedLeft = intAttr(xmp, "CroppedAreaLeftPixels") ?: 0
        val croppedTop = intAttr(xmp, "CroppedAreaTopPixels") ?: 0

        return PanoData(fullWidth, fullHeight, croppedWidth, croppedHeight, croppedLeft, croppedTop)
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
