package com.vayunmathur.communicate.rcs

import android.telephony.TelephonyManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowTelephonyManager

/**
 * TelephonyManager stand-in whose ISIM AKA challenge succeeds with a fixed
 * base64 RES||CK||IK blob, so the direct-SIP digest registration flow can
 * run end to end on the JVM.
 */
@Implements(TelephonyManager::class)
class RcsSlice3Pass2TelephonyShadow : ShadowTelephonyManager() {
    @Implementation
    override fun getIccAuthentication(appType: Int, authType: Int, data: String?): String =
        // "resresre" || "ckckckck" || "ikikikik" as one base64 blob.
        "cmVzcmVzcmVja2NrY2tjY2tpbmlraWtpaw=="
}
