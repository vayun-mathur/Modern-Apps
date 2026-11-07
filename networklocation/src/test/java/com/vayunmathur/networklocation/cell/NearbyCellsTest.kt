package com.vayunmathur.networklocation.cell

import android.content.Context
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.networklocation.BeaconId
import com.vayunmathur.networklocation.RadioType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.CellIdentityGsmBuilder
import org.robolectric.shadows.CellIdentityLteBuilder
import org.robolectric.shadows.CellIdentityNrBuilder
import org.robolectric.shadows.CellIdentityWcdmaBuilder
import org.robolectric.shadows.CellInfoGsmBuilder
import org.robolectric.shadows.CellInfoLteBuilder
import org.robolectric.shadows.CellInfoNrBuilder
import org.robolectric.shadows.CellInfoWcdmaBuilder

@RunWith(RobolectricTestRunner::class)
class NearbyCellsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun setCells(cells: List<android.telephony.CellInfo>) {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        shadowOf(tm).setAllCellInfo(cells)
    }

    private fun lte(mcc: String? = "310", mnc: String? = "260", ci: Int = 4242, tac: Int = 1234) =
        CellInfoLteBuilder.newBuilder()
            .setCellIdentity(
                CellIdentityLteBuilder.newBuilder()
                    .setMcc(mcc).setMnc(mnc).setCi(ci).setTac(tac).build(),
            ).build()

    @Test
    fun `lte gsm wcdma and nr cells all map to beacon ids`() {
        val gsm = CellInfoGsmBuilder.newBuilder()
            .setCellIdentity(
                CellIdentityGsmBuilder.newBuilder()
                    .setMcc("234").setMnc("15").setCid(777).setLac(99).build(),
            ).build()
        val wcdma = CellInfoWcdmaBuilder.newBuilder()
            .setCellIdentity(
                CellIdentityWcdmaBuilder.newBuilder()
                    .setMcc("234").setMnc("15").setCid(888).setLac(98).build(),
            ).build()
        val nr = CellInfoNrBuilder.newBuilder()
            .setCellIdentity(
                CellIdentityNrBuilder.newBuilder()
                    .setMcc("505").setMnc("01").setNci(0xFFFFFFFFFL).setTac(0xFFFFFF).build(),
            ).build()
        setCells(listOf(lte(), gsm, wcdma, nr))
        assertEquals(
            listOf(
                BeaconId.Cell(310, 260, RadioType.LTE, 4242, 1234),
                BeaconId.Cell(234, 15, RadioType.GSM, 777, 99),
                BeaconId.Cell(234, 15, RadioType.UMTS, 888, 98),
                BeaconId.Cell(505, 1, RadioType.NR, 0xFFFFFFFFFL, 0xFFFFFF),
            ),
            NearbyCells(context).scan(),
        )
    }

    @Test
    fun `duplicate cells are returned once`() {
        setCells(listOf(lte(), lte()))
        assertEquals(
            listOf(BeaconId.Cell(310, 260, RadioType.LTE, 4242, 1234)),
            NearbyCells(context).scan(),
        )
    }

    @Test
    fun `cells with missing or sentinel identity are dropped`() {
        setCells(
            listOf(
                lte(mcc = null),
                lte(mnc = null),
                lte(mcc = "not-a-number"),
                lte(ci = Int.MAX_VALUE), // CellInfo.UNAVAILABLE
                lte(ci = 0),
                lte(tac = Int.MAX_VALUE), // CellInfo.UNAVAILABLE
                lte(),
            ),
        )
        assertEquals(
            listOf(BeaconId.Cell(310, 260, RadioType.LTE, 4242, 1234)),
            NearbyCells(context).scan(),
            "only the fully-identified cell survives",
        )
    }

    @Test
    fun `gsm wcdma and nr cells with sentinel or missing identity are dropped`() {
        val gsmNoMcc = CellInfoGsmBuilder.newBuilder()
            .setCellIdentity(
                CellIdentityGsmBuilder.newBuilder().setCid(777).setLac(99).build(),
            ).build()
        val gsmBadCid = CellInfoGsmBuilder.newBuilder()
            .setCellIdentity(
                CellIdentityGsmBuilder.newBuilder()
                    .setMcc("234").setMnc("15").setCid(Int.MAX_VALUE).setLac(99).build(),
            ).build()
        val wcdmaBadLac = CellInfoWcdmaBuilder.newBuilder()
            .setCellIdentity(
                CellIdentityWcdmaBuilder.newBuilder()
                    .setMcc("234").setMnc("15").setCid(888).setLac(Int.MAX_VALUE).build(),
            ).build()
        val nrUnavailable = CellInfoNrBuilder.newBuilder()
            .setCellIdentity(
                CellIdentityNrBuilder.newBuilder()
                    .setMcc("505").setMnc("01").setNci(Long.MAX_VALUE).setTac(55).build(),
            ).build()
        val nrZero = CellInfoNrBuilder.newBuilder()
            .setCellIdentity(
                CellIdentityNrBuilder.newBuilder()
                    .setMcc("505").setMnc("01").setNci(0L).setTac(55).build(),
            ).build()
        setCells(listOf(gsmNoMcc, gsmBadCid, wcdmaBadLac, nrUnavailable, nrZero, lte()))
        assertEquals(
            listOf(BeaconId.Cell(310, 260, RadioType.LTE, 4242, 1234)),
            NearbyCells(context).scan(),
        )
    }

    @Test
    fun `no cell info yields an empty list`() {
        setCells(emptyList())
        assertTrue(NearbyCells(context).scan().isEmpty())
    }
}
