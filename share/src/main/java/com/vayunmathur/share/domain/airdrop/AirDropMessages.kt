package com.vayunmathur.share.domain.airdrop

/**
 * Typed views of the AirDrop `/Discover` and `/Ask` plists.
 *
 * Field names and value types are the ones Mosey writes (`cbz`, `cdv`, `cbx`, `cdw`), and the
 * parsers are lenient in the way Apple senders need: iOS adds `SenderRecordData`,
 * `FileIcon` and others we ignore, and omits the `TransferID` Mosey adds.
 */
object AirDropMessages {

    const val USER_AGENT = "AirDrop/1.0"
    const val CONTENT_TYPE_PLIST = "application/octet-stream"
    const val CONTENT_TYPE_CPIO = "application/x-cpio"
    const val CONTENT_TYPE_DVZIP = "application/x-dvzip"
    const val CONTENT_TYPE_ZIP = "application/zip"

    /** Mosey's sender drops any receiver whose `Manufacturer` is this, case-insensitively. */
    const val MANUFACTURER_GOOGLE = "Google"

    /** What Mosey and OpenDrop both advertise; Apple peers only check that it parses. */
    val DEFAULT_MEDIA_CAPABILITIES: ByteArray = """{"Version":1}""".toByteArray(Charsets.UTF_8)

    /** Immutable value describing discover response. */
    data class DiscoverResponse(
        val receiverComputerName: String,
        val manufacturer: String? = null,
        val mediaCapabilities: ByteArray? = null,
    ) {
        fun toPlist(): Map<String, Any?> = buildMap {
            put("ReceiverComputerName", receiverComputerName)
            mediaCapabilities?.let { put("ReceiverMediaCapabilities", it) }
            manufacturer?.let { put("Manufacturer", it) }
        }

        /** Shared constants and helpers for DiscoverResponse. */
        companion object {
            fun fromPlist(map: Map<String, Any?>): DiscoverResponse? {
                val name = (map["ReceiverComputerName"] as? String)?.takeIf { it.isNotBlank() } ?: return null
                return DiscoverResponse(
                    receiverComputerName = name,
                    manufacturer = map["Manufacturer"] as? String,
                    mediaCapabilities = map["ReceiverMediaCapabilities"] as? ByteArray,
                )
            }
        }
    }

    /** Immutable value describing ask file. */
    data class AskFile(
        val fileName: String,
        val fileType: String,
        val fileSize: Long,
        val fileBomPath: String,
        val isDirectory: Boolean = false,
        val fileSubType: String? = null,
    ) {
        fun toPlist(): Map<String, Any?> = buildMap {
            put("ConvertMediaFormats", 0L)
            put("FileIsDirectory", isDirectory)
            put("FileName", fileName)
            put("FileType", fileType)
            fileSubType?.let { put("FileSubType", it) }
            put("FileSize", fileSize)
            put("FileBomPath", fileBomPath)
            put("ShouldConvertMediaFormats", false)
        }

        /** Shared constants and helpers for AskFile. */
        companion object {
            fun fromPlist(map: Map<*, *>): AskFile? {
                val name = (map["FileName"] as? String)?.takeIf { it.isNotBlank() } ?: return null
                return AskFile(
                    fileName = name,
                    fileType = map["FileType"] as? String ?: "public.data",
                    fileSize = (map["FileSize"] as? Long) ?: -1L,
                    fileBomPath = map["FileBomPath"] as? String ?: "./$name",
                    isDirectory = map["FileIsDirectory"] as? Boolean ?: false,
                    fileSubType = map["FileSubType"] as? String,
                )
            }

            /** A regular file entry, typed from its extension. */
            fun forFile(name: String, size: Long): AskFile = AskFile(
                fileName = name,
                fileType = Uti.forFileName(name).uti,
                fileSize = size,
                fileBomPath = "./$name",
            )
        }
    }

    /** Immutable value describing ask request. */
    data class AskRequest(
        val senderComputerName: String,
        val senderModelName: String,
        val senderId: String,
        val files: List<AskFile>,
        val bundleId: String = Uti.DEFAULT_BUNDLE_ID,
        val items: List<String> = emptyList(),
        val transferId: String? = null,
    ) {
        fun toPlist(): Map<String, Any?> = buildMap {
            put("ConvertMediaFormats", false)
            transferId?.let { put("TransferID", mapOf("id" to it)) }
            put("SenderID", senderId)
            put("SenderComputerName", senderComputerName)
            put("SenderModelName", senderModelName)
            put("BundleID", bundleId)
            if (items.isNotEmpty()) put("Items", items)
            put("TransferType", mapOf((if (items.isEmpty()) "files" else "links") to emptyMap<String, Any?>()))
            put("Files", files.map { it.toPlist() })
        }

        /** Sum of announced sizes, treating Apple's "unknown" (negative) as zero. */
        val totalBytes: Long get() = files.sumOf { it.fileSize.coerceAtLeast(0) }

        /** Shared constants and helpers for AskRequest. */
        companion object {
            fun fromPlist(map: Map<String, Any?>): AskRequest? {
                val files = (map["Files"] as? List<*>).orEmpty()
                    .mapNotNull { (it as? Map<*, *>)?.let(AskFile::fromPlist) }
                val items = (map["Items"] as? List<*>).orEmpty().filterIsInstance<String>()
                if (files.isEmpty() && items.isEmpty()) return null
                val transferId = when (val t = map["TransferID"]) {
                    is String -> t
                    is Map<*, *> -> t["id"] as? String
                    else -> null
                }
                return AskRequest(
                    senderComputerName = (map["SenderComputerName"] as? String)
                        ?.takeIf { it.isNotBlank() } ?: "AirDrop",
                    senderModelName = map["SenderModelName"] as? String ?: "",
                    senderId = map["SenderID"] as? String ?: "",
                    files = files,
                    bundleId = map["BundleID"] as? String ?: Uti.DEFAULT_BUNDLE_ID,
                    items = items,
                    transferId = transferId,
                )
            }
        }
    }

    /**
     * The local name to store a received entry under, or null to skip it.
     *
     * Archive names are `./<bom path>`, sometimes under an `NSIRD_AirDrop_<id>` wrapper
     * directory. Anything that would escape the destination is refused, not sanitised.
     */
    fun safeRelativePath(archiveName: String): String? {
        val parts = archiveName.replace('\\', '/').split('/')
            .filter { it.isNotEmpty() && it != "." }
            .dropWhile { it.startsWith("NSIRD_AirDrop") }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /** 6 random bytes as 12 lowercase hex chars, Mosey's shape for `SenderID` and its mDNS name. */
    fun randomShortId(random: java.security.SecureRandom = java.security.SecureRandom()): String =
        ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}
