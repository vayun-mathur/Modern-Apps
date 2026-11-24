package com.vayunmathur.library.wearlink

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** PackageManager signing-certificate facts for the handshake trust check. */
object SigningTrust {

    /**
     * SHA-256 hex of this app's first APK signing certificate, or null when
     * it cannot be read (the node then refuses to connect rather than
     * connecting unauthenticated).
     */
    fun localCertHashHex(context: Context): String? = runCatching {
        val info = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val signers = info.signingInfo?.apkContentsSigners
        val cert = signers?.firstOrNull() ?: return null
        WearLinkHandshake.certHashHex(cert.toByteArray())
    }.getOrNull()

    /**
     * True when [otherPackage] is installed and signed with the same
     * certificate as this app — the local half of the trust check (the
     * remote half is the nonce-bound handshake token). Kept for consumers
     * that also talk to a same-package sibling locally.
     */
    fun sameSignature(context: Context, otherPackage: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.checkSignatures(context.packageName, otherPackage) ==
                PackageManager.SIGNATURE_MATCH
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.checkSignatures(context.packageName, otherPackage) ==
                PackageManager.SIGNATURE_MATCH
        }
    }.getOrDefault(false)
}
