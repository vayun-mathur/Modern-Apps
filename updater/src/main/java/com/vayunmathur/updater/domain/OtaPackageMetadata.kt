package com.vayunmathur.updater.domain

/**
 * The contents of `META-INF/com/android/metadata` inside an OTA zip.
 *
 * This is the package's own account of what it is. It is NOT trustworthy on its own — anyone
 * can write a zip — which is why [OtaPackageValidation] only runs after
 * `RecoverySystem.verifyPackage` has checked the signature against the device's `otacerts.zip`.
 * What these fields then buy us is protection against a *correctly signed* package being
 * applied to the wrong device or from the wrong starting point, which the signature alone does
 * not cover.
 */
data class OtaPackageMetadata(
    /** `post-timestamp`. Must equal the build date the server advertised. */
    val postTimestamp: Long?,
    /** `post-build-incremental`. The build this package produces. */
    val postBuildIncremental: String?,
    /** `pre-device`. The device this package is for. */
    val preDevice: String?,
    /** `serialno`. A per-device pin; see [OtaPackageValidation]. */
    val serialNo: String?,
    /** `ota-type`. Must be `AB`. */
    val otaType: String?,
    /** `pre-build-incremental`. Set on an incremental; the source it patches FROM. */
    val preBuildIncremental: String?,
    /** `pre-build`. Set on an incremental; the source fingerprint it patches FROM. */
    val preBuildFingerprint: String?,
    /** `ota-streaming-property-files`, already split. Carries the `payload.bin` offset. */
    val streamingPropertyFiles: List<String>,
) {
    /** Parser for `META-INF/com/android/metadata` lines into [OtaPackageMetadata]. */
    companion object {

        fun parse(lines: Sequence<String>): OtaPackageMetadata {
            var postTimestamp: Long? = null
            var postBuildIncremental: String? = null
            var preDevice: String? = null
            var serialNo: String? = null
            var otaType: String? = null
            var preBuildIncremental: String? = null
            var preBuildFingerprint: String? = null
            var streaming: List<String> = emptyList()

            for (line in lines) {
                // limit = 2: a fingerprint value contains '=' and must not be truncated.
                val pair = line.split("=", limit = 2)
                if (pair.size != 2) continue
                val value = pair[1]
                when (pair[0]) {
                    "post-timestamp" -> postTimestamp = value.trim().toLongOrNull()
                    "post-build-incremental" -> postBuildIncremental = value
                    "pre-device" -> preDevice = value
                    "serialno" -> serialNo = value
                    "ota-type" -> otaType = value
                    "pre-build-incremental" -> preBuildIncremental = value
                    "pre-build" -> preBuildFingerprint = value
                    "ota-streaming-property-files" ->
                        streaming = value.trim().split(",").map { it.trim() }
                }
            }
            return OtaPackageMetadata(
                postTimestamp = postTimestamp,
                postBuildIncremental = postBuildIncremental,
                preDevice = preDevice,
                serialNo = serialNo,
                otaType = otaType,
                preBuildIncremental = preBuildIncremental,
                preBuildFingerprint = preBuildFingerprint,
                streamingPropertyFiles = streaming,
            )
        }
    }

    /**
     * Byte offset of `payload.bin` within the zip, which `update_engine` needs in order to read
     * the payload without unpacking the archive. Null when the package does not declare one.
     */
    fun payloadOffset(): Long? = streamingPropertyFiles
        .firstNotNullOfOrNull { entry ->
            val parts = entry.split(":")
            if (parts.size >= 2 && parts[0] == "payload.bin") parts[1].toLongOrNull() else null
        }
}
