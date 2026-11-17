package com.vayunmathur.camera.domain

import java.io.ByteArrayOutputStream

/**
 * Builds and injects a standard GPano XMP packet so stitched panoramas/spheres
 * are recognised as 360/panoramic by compliant viewers (Google Photos, VR
 * viewers, etc.). Uses only the JPEG APP1 segment — no extra dependency.
 */
object PanoXmp {

    private const val XMP_NAMESPACE = "http://ns.adobe.com/xap/1.0/\u0000"

    // JPEG segment markers/fields (APP1 XMP injection).
    private const val JPEG_MARKER_PREFIX = 0xFF
    private const val JPEG_SOI_SECOND = 0xD8
    private const val JPEG_APP1 = 0xE1
    private const val JPEG_SEGMENT_MAX = 0xFFFF
    private const val JPEG_BYTE_MASK = 0xFF
    private const val LENGTH_FIELD_BYTES = 2
    private const val HIGH_BYTE_SHIFT = 8

    /** Emit a standard XMP packet carrying the GPano fields for [info]. */
    fun buildGPanoXmp(info: PanoInfo): String = buildString {
        append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">")
        append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
        append("<rdf:Description rdf:about=\"\" ")
        append("xmlns:GPano=\"http://ns.google.com/photos/1.0/panorama/\" ")
        append("GPano:UsePanoramaViewer=\"True\" ")
        append("GPano:ProjectionType=\"${info.projectionType}\" ")
        append("GPano:FullPanoWidthPixels=\"${info.fullWidth}\" ")
        append("GPano:FullPanoHeightPixels=\"${info.fullHeight}\" ")
        append("GPano:CroppedAreaImageWidthPixels=\"${info.croppedWidth}\" ")
        append("GPano:CroppedAreaImageHeightPixels=\"${info.croppedHeight}\" ")
        append("GPano:CroppedAreaLeftPixels=\"${info.croppedLeft}\" ")
        append("GPano:CroppedAreaTopPixels=\"${info.croppedTop}\"/>")
        append("</rdf:RDF>")
        append("</x:xmpmeta>")
        append("<?xpacket end=\"w\"?>")
    }

    /**
     * Insert an APP1 (0xFFE1) XMP segment right after the SOI marker (0xFFD8).
     * Payload = namespace signature + XMP bytes; length is a 2-byte big-endian
     * value covering the length field + payload (must fit in 65533 bytes, which
     * these few GPano fields always do).
     */
    fun injectXmp(jpeg: ByteArray, xmp: String): ByteArray {
        // Not a JPEG (no SOI) — return unchanged rather than corrupting it.
        if (jpeg.size < 2 || jpeg[0] != JPEG_MARKER_PREFIX.toByte() || jpeg[1] != JPEG_SOI_SECOND.toByte()) return jpeg

        val nsBytes = XMP_NAMESPACE.toByteArray(Charsets.UTF_8)
        val xmpBytes = xmp.toByteArray(Charsets.UTF_8)
        val payloadLen = nsBytes.size + xmpBytes.size
        val segmentLen = payloadLen + 2 // includes the 2-byte length field itself
        if (segmentLen > JPEG_SEGMENT_MAX) return jpeg

        val out = ByteArrayOutputStream(jpeg.size + segmentLen + LENGTH_FIELD_BYTES)
        // SOI
        out.write(JPEG_MARKER_PREFIX)
        out.write(JPEG_SOI_SECOND)
        // APP1 marker + length + payload
        out.write(JPEG_MARKER_PREFIX)
        out.write(JPEG_APP1)
        out.write((segmentLen shr HIGH_BYTE_SHIFT) and JPEG_BYTE_MASK)
        out.write(segmentLen and JPEG_BYTE_MASK)
        out.write(nsBytes)
        out.write(xmpBytes)
        // Rest of the original JPEG after the SOI.
        out.write(jpeg, 2, jpeg.size - 2)
        return out.toByteArray()
    }
}
