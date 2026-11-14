package com.vayunmathur.appstore.data.play

import android.content.Context
import android.content.pm.PackageManager

/** Google services and vending version codes reported to Play. */
data class GsfVersion(
    val gsfVersionCode: Int,
    val vendingVersionCode: Int,
    val vendingVersionString: String
)

/**
 * Port of Aurora's NativeGsfVersionProvider.
 * Provides defaults when GMS/Vending not installed, else reads real versions.
 */
object GsfVersionProvider {

    private const val DEFAULT_GSF_VERSION_CODE = 203019037
    private const val DEFAULT_VENDING_VERSION_CODE = 82151710
    private const val DEFAULT_VENDING_VERSION_STRING = "21.5.17-21 [0] [PR] 326734551"

    private const val PKG_GSF = "com.google.android.gsf"
    private const val PKG_VENDING = "com.android.vending"

    fun get(context: Context): GsfVersion {
        return try {
            val pm = context.packageManager
            val gsfCode = try {
                getVersionCode(pm, PKG_GSF)
            } catch (_: Exception) {
                DEFAULT_GSF_VERSION_CODE
            }
            val (vendingCode, vendingString) = try {
                val info = pm.getPackageInfo(PKG_VENDING, 0)
                val vc = getVersionCode(pm, PKG_VENDING)
                val vs = info.versionName ?: DEFAULT_VENDING_VERSION_STRING
                vc to vs
            } catch (_: Exception) {
                DEFAULT_VENDING_VERSION_CODE to DEFAULT_VENDING_VERSION_STRING
            }
            GsfVersion(gsfCode, vendingCode, vendingString)
        } catch (_: Exception) {
            GsfVersion(DEFAULT_GSF_VERSION_CODE, DEFAULT_VENDING_VERSION_CODE, DEFAULT_VENDING_VERSION_STRING)
        }
    }

    private fun getVersionCode(pm: PackageManager, pkg: String): Int {
        return try {
            val info = pm.getPackageInfo(pkg, 0)
            @Suppress("DEPRECATION")
            info.versionCode
        } catch (_: Exception) {
            if (pkg == PKG_GSF) DEFAULT_GSF_VERSION_CODE else DEFAULT_VENDING_VERSION_CODE
        }
    }
}
