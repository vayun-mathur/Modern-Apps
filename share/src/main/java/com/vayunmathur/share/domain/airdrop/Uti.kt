package com.vayunmathur.share.domain.airdrop

/**
 * Uniform Type Identifiers for AirDrop `Ask` file entries.
 *
 * The extension table and the per-type receiving bundle id are Mosey's own (`cfh`), so an
 * Apple receiver routes each file to the same app it does for a Pixel sender.
 */
object Uti {

    data class Type(val uti: String, val bundleId: String)

    const val FOLDER = "public.folder"
    const val PLAIN_TEXT = "public.plain-text"
    const val LIVE_PHOTO_BUNDLE = "com.apple.private.live-photo-bundle"
    const val NOTES_DOCUMENT = "com.apple.notes.airdrop.document"
    const val DEFAULT_BUNDLE_ID = "com.apple.finder"
    private val DEFAULT = Type("public.data", "com.apple.DocumentsApp")

    private val byExtension: Map<String, Type> = mapOf(
        "pdf" to Type("com.adobe.pdf", "com.apple.Preview"),
        "txt" to Type(PLAIN_TEXT, "com.apple.Notes"),
        "rtf" to Type("public.rtf", "com.apple.TextEdit"),
        "rtfd" to Type("com.apple.rtfd", "com.apple.TextEdit"),
        "html" to Type("public.html", "com.apple.mobilesafari"),
        "htm" to Type("public.html", "com.apple.mobilesafari"),
        "xml" to Type("public.xml", "com.apple.TextEdit"),
        "notesairdropdocument" to Type(NOTES_DOCUMENT, "com.apple.Notes"),
        "epub" to Type("com.apple.epub.document", "com.apple.iBooks"),
        "png" to Type("public.png", "com.apple.mobileslideshow"),
        "jpg" to Type("public.jpeg", "com.apple.mobileslideshow"),
        "jpeg" to Type("public.jpeg", "com.apple.mobileslideshow"),
        "heic" to Type("public.heic", "com.apple.mobileslideshow"),
        "heif" to Type("public.heif", "com.apple.mobileslideshow"),
        "gif" to Type("com.compuserve.gif", "com.apple.mobileslideshow"),
        "tif" to Type("public.tiff", "com.apple.mobileslideshow"),
        "tiff" to Type("public.tiff", "com.apple.mobileslideshow"),
        "dng" to Type("com.adobe.raw-image", "com.apple.mobileslideshow"),
        "raw" to Type("public.camera-raw-image", "com.apple.mobileslideshow"),
        "webp" to Type("org.webmproject.webp", "com.apple.mobileslideshow"),
        "bmp" to Type("com.microsoft.bmp", "com.apple.mobileslideshow"),
        "mp3" to Type("public.mp3", "com.apple.Music"),
        "aiff" to Type("public.aiff-audio", "com.apple.Music"),
        "aif" to Type("public.aiff-audio", "com.apple.Music"),
        "wav" to Type("com.microsoft.waveform-audio", "com.apple.Music"),
        "mid" to Type("public.midi-audio", "com.apple.Music"),
        "midi" to Type("public.midi-audio", "com.apple.Music"),
        "m4a" to Type("public.mpeg-4-audio", "com.apple.Music"),
        "mov" to Type("com.apple.quicktime-movie", "com.apple.TV"),
        "mp4" to Type("public.mpeg-4", "com.apple.TV"),
        "m4v" to Type("public.mpeg-4", "com.apple.TV"),
        "avi" to Type("public.avi", "com.apple.TV"),
        "mpeg" to Type("public.mpeg", "com.apple.TV"),
        "mpg" to Type("public.mpeg", "com.apple.TV"),
        "mkv" to Type("public.matroska-video", "com.apple.DocumentsApp"),
        "pages" to Type("com.apple.iwork.pages.sffpages", "com.apple.iWork.Pages"),
        "numbers" to Type("com.apple.iwork.numbers.sffnumbers", "com.apple.iWork.Numbers"),
        "key" to Type("com.apple.iwork.keynote.sffkey", "com.apple.iWork.Keynote"),
        "usd" to Type("com.pixar.usd", "com.apple.RealityKit.Viewer"),
        "usdz" to Type("com.pixar.usdz", "com.apple.RealityKit.Viewer"),
        "zip" to Type("com.pkware.zip-archive", "com.apple.DocumentsApp"),
        "tar" to Type("public.tar-archive", "com.apple.DocumentsApp"),
        "gz" to Type("org.gnu.gzip-archive", "com.apple.DocumentsApp"),
        "dmg" to Type("com.apple.diskimage", "com.apple.DiskUtility"),
        "ipa" to Type("com.apple.ipa", "com.apple.AppStore"),
        "vcf" to Type("public.vcard", "com.apple.MobileAddressBook"),
        "ics" to Type("public.calendar-event", "com.apple.mobilecal"),
        "url" to Type("public.url", "com.apple.mobilesafari"),
        "webloc" to Type("com.apple.web-location", "com.apple.Safari"),
        "pkpass" to Type("com.apple.pkpass", "com.apple.Passbook"),
        "plist" to Type("com.apple.property-list", "com.apple.TextEdit"),
        "md" to Type("net.daringfireball.markdown", "com.apple.Notes"),
        "csv" to Type("public.comma-separated-values-text", "com.apple.Numbers"),
    )

    private val mimeByUti: Map<String, String> = mapOf(
        "com.adobe.pdf" to "application/pdf",
        "com.apple.epub.document" to "application/epub+zip",
        "org.idpf.epub-container" to "application/epub+zip",
        "com.pkware.zip-archive" to "application/zip",
        "public.zip-archive" to "application/zip",
        "org.gnu.gzip-archive" to "application/gzip",
        "public.tar-archive" to "application/x-tar",
        "public.calendar-event" to "text/calendar",
        "net.daringfireball.markdown" to "text/markdown",
        "public.comma-separated-values-text" to "text/csv",
        "com.apple.pkpass" to "application/vnd.apple.pkpass",
        "com.apple.diskimage" to "application/x-apple-diskimage",
        "public.matroska-video" to "video/x-matroska",
        "public.mpeg" to "video/mpeg",
        "com.pixar.usd" to "model/vnd.pixar.usd",
        "com.pixar.usdz" to "model/vnd.usdz+zip",
        NOTES_DOCUMENT to "text/plain",
        "com.apple.rtfd" to "text/rtf",
        "public.html" to "text/html",
        PLAIN_TEXT to "text/plain",
        "public.rtf" to "text/rtf",
        "public.xml" to "text/xml",
        "public.png" to "image/png",
        "public.jpeg" to "image/jpeg",
        "public.heic" to "image/heic",
        "public.heif" to "image/heif",
        "com.compuserve.gif" to "image/gif",
        "public.tiff" to "image/tiff",
        "com.adobe.raw-image" to "image/x-adobe-dng",
        "org.webmproject.webp" to "image/webp",
        "com.microsoft.bmp" to "image/bmp",
        "public.mp3" to "audio/mpeg",
        "public.aiff-audio" to "audio/aiff",
        "com.microsoft.waveform-audio" to "audio/wav",
        "public.midi-audio" to "audio/midi",
        "public.mpeg-4-audio" to "audio/mp4",
        "com.apple.m4a-audio" to "audio/mp4",
        "com.apple.quicktime-movie" to "video/quicktime",
        "public.mpeg-4" to "video/mp4",
        "public.avi" to "video/avi",
        "public.contact" to "text/x-vcard",
        "public.vcard" to "text/x-vcard",
    )

    /** Extension Mosey's receiver substitutes for a live-photo bundle's still image. */
    private val extensionBySubType: Map<String, String> = mapOf(
        "public.heic" to "heic",
        "public.jpeg" to "jpg",
        "com.adobe.raw-image" to "dng",
        "public.png" to "png",
    )

    fun forFileName(name: String): Type =
        byExtension[name.substringAfterLast('.', "").lowercase()] ?: DEFAULT

    /** MIME type for a received file, or null when the UTI says nothing useful. */
    fun mimeTypeOf(uti: String?): String? = uti?.let { mimeByUti[it] }

    fun extensionForSubType(subType: String?): String? = subType?.let { extensionBySubType[it] }
}
