package com.vayunmathur.camera.util

/**
 * Assembles a Google Motion Photo: the primary still JPEG (with a GCamera XMP packet describing the
 * embedded clip) followed by the encoded MP4 appended as a trailer.
 *
 * Uses the MicroVideo (v0) layout — `GCamera:MicroVideo` + `GCamera:MicroVideoOffset` — which is the
 * simplest container Google Photos recognises: the offset is the number of bytes from the end of the
 * file back to the start of the video, i.e. the MP4 length (the MP4 is the final segment).
 */
object MotionPhotoWriter {

    /** Builds the GCamera XMP packet declaring an embedded MicroVideo of [mp4Size] bytes. */
    fun buildMotionPhotoXmp(mp4Size: Int): String = buildString {
        append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">")
        append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
        append("<rdf:Description rdf:about=\"\" ")
        append("xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" ")
        append("GCamera:MotionPhoto=\"1\" ")
        append("GCamera:MotionPhotoVersion=\"1\" ")
        append("GCamera:MotionPhotoPresentationTimestampUs=\"-1\" ")
        append("GCamera:MicroVideo=\"1\" ")
        append("GCamera:MicroVideoVersion=\"1\" ")
        append("GCamera:MicroVideoOffset=\"$mp4Size\" ")
        append("GCamera:MicroVideoPresentationTimestampUs=\"-1\"/>")
        append("</rdf:RDF>")
        append("</x:xmpmeta>")
        append("<?xpacket end=\"w\"?>")
    }

    /**
     * Returns the full Motion Photo bytes: [jpeg] with the GCamera XMP injected, followed by [mp4].
     * The [jpeg] may itself be an Ultra HDR JPEG (its gain map is preserved); the appended clip is SDR.
     */
    fun assemble(jpeg: ByteArray, mp4: ByteArray): ByteArray {
        val jpegWithXmp = PanoXmp.injectXmp(jpeg, buildMotionPhotoXmp(mp4.size))
        return jpegWithXmp + mp4
    }
}
