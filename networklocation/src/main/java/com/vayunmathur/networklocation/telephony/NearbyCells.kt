package com.vayunmathur.networklocation.telephony

import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellIdentityWcdma
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.networklocation.domain.BeaconId
import com.vayunmathur.networklocation.domain.RadioType

/**
 * Reads nearby cell towers from [TelephonyManager]. Only towers with a full,
 * non-sentinel identity (MCC/MNC/cell id/area code) are usable as beacons.
 */
class NearbyCells(context: Context) {
    private val telephonyManager =
        context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    fun scan(): List<BeaconId.Cell> {
        // allCellInfo needs ACCESS_FINE_LOCATION; without it the platform throws
        // SecurityException, which here simply means "no cell beacons this scan".
        val infos = try {
            telephonyManager?.allCellInfo
        } catch (e: SecurityException) {
            Log.status(TAG, "Cell info unavailable without location grant", e)
            null
        } ?: return emptyList()
        return infos.mapNotNull { it.toCell() }.distinct()
    }

    private fun CellInfo.toCell(): BeaconId.Cell? = when (this) {
        is CellInfoLte -> cellIdentity.toCell()
        is CellInfoGsm -> cellIdentity.toCell()
        is CellInfoWcdma -> cellIdentity.toCell()
        is CellInfoNr -> (cellIdentity as? CellIdentityNr)?.toCell()
        else -> null
    }

    private fun CellIdentityLte.toCell(): BeaconId.Cell? =
        build(mccString?.toIntOrNull(), mncString?.toIntOrNull(), RadioType.LTE, ci.toLong(), tac)

    private fun CellIdentityGsm.toCell(): BeaconId.Cell? =
        build(mccString?.toIntOrNull(), mncString?.toIntOrNull(), RadioType.GSM, cid.toLong(), lac)

    private fun CellIdentityWcdma.toCell(): BeaconId.Cell? =
        build(mccString?.toIntOrNull(), mncString?.toIntOrNull(), RadioType.UMTS, cid.toLong(), lac)

    // nci is a 36-bit NCI and stays a Long; narrowing it to Int silently drops the top bits
    // and makes distinct gNB cells collide.
    private fun CellIdentityNr.toCell(): BeaconId.Cell? =
        build(mccString?.toIntOrNull(), mncString?.toIntOrNull(), RadioType.NR, nci, tac)

    private fun build(
        mcc: Int?,
        mnc: Int?,
        radio: RadioType,
        cellId: Long,
        areaCode: Int,
    ): BeaconId.Cell? {
        if (mcc == null || mnc == null) return null
        if (cellId == CellInfo.UNAVAILABLE.toLong() || cellId == CellInfo.UNAVAILABLE_LONG) {
            return null
        }
        if (areaCode == CellInfo.UNAVAILABLE) return null
        if (cellId <= 0) return null
        return BeaconId.Cell(
            mcc = mcc,
            mnc = mnc,
            radio = radio,
            cellId = cellId,
            tacOrLac = areaCode,
        )
    }
    private companion object {
        const val TAG = "NearbyCells"
    }
}
