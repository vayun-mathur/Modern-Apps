package com.vayunmathur.appstore.data.security

import java.io.File
import java.security.cert.X509Certificate
import java.util.jar.JarFile

/**
 * Verifies the signed `entry.jar` that authenticates an F-Droid repository's index-v2.
 *
 * This is the check that makes everything downstream mean anything: the per-APK SHA-256
 * and `signer` fingerprints only bind the APK to the index, so an index we haven't
 * authenticated could simply state a hostile APK's own hash and key. Pinning the index
 * signing certificate is what stops a malicious mirror from rewriting both sides.
 *
 * [JarFile] with verification enabled checks the PKCS#7 signature block against the
 * per-entry digests in `META-INF/MANIFEST.MF` as entries are read — but only for entries
 * that are actually consumed, and an unsigned entry simply reports no certificates. Hence
 * [readVerified] reads the entry to completion first and treats a missing certificate as
 * a failure rather than as "unsigned but fine".
 */
object SignedJarIndex {

    /** Signals that signed-jar index verification failed. */
    class VerificationException(message: String) : Exception(message)

    /** Verified signed content together with its signer certificate hash. */
    data class Verified(
        val content: ByteArray,
        /** Lowercase-hex SHA-256 of the signing certificate. */
        val signerSha256: String,
    )

    /**
     * Read [entryName] out of the signed [jar], enforcing that it is covered by the JAR
     * signature. The signing certificate must match [pinnedFingerprint].
     */
    fun readVerified(jar: File, entryName: String, pinnedFingerprint: String): Verified {
        var content = ByteArray(0)
        val fingerprint = consumeVerified(jar, entryName, pinnedFingerprint) { input ->
            content = input.readBytes()
        }
        return Verified(content, fingerprint)
    }

    private fun consumeVerified(
        jar: File,
        entryName: String,
        pinnedFingerprint: String,
        consume: (java.io.InputStream) -> Unit,
    ): String {
        JarFile(jar, true).use { jarFile ->
            if (jarFile.manifest == null) {
                throw VerificationException("Index JAR has no manifest — it is not signed")
            }
            val entry = jarFile.getJarEntry(entryName)
                ?: throw VerificationException("Index JAR does not contain $entryName")

            // Certificates are only attached once the entry has been read to completion
            // and its digest checked, so this read *is* the verification step, not just IO.
            jarFile.getInputStream(entry).use(consume)

            val signer = signerOf(entry, entryName)
            val fingerprint = ApkCertificates.sha256(signer.encoded)
            checkPinned(fingerprint, pinnedFingerprint)
            return fingerprint
        }
    }

    private fun signerOf(
        entry: java.util.jar.JarEntry,
        entryName: String,
    ): X509Certificate {
        val certificates = entry.certificates
            ?: throw VerificationException("$entryName is not covered by the JAR signature")
        return certificates.filterIsInstance<X509Certificate>().firstOrNull()
            ?: throw VerificationException("$entryName has no X.509 signer")
    }

    private fun checkPinned(fingerprint: String, pinnedFingerprint: String) {
        if (!fingerprint.equals(pinnedFingerprint, true)) {
            throw VerificationException(
                "Repository signing key changed: pinned " +
                    "${ApkCertificates.abbreviate(pinnedFingerprint)}, " +
                    "got ${ApkCertificates.abbreviate(fingerprint)}"
            )
        }
    }
}
