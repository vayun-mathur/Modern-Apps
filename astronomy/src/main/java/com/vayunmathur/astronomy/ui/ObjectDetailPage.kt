package com.vayunmathur.astronomy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.astronomy.R
import com.vayunmathur.astronomy.Route
import com.vayunmathur.astronomy.domain.engine.TimeEngine
import com.vayunmathur.astronomy.domain.engine.toDeg
import com.vayunmathur.astronomy.platform.AstronomyViewModel
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBarOverlay
import com.vayunmathur.library.util.NavBackStack
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalTime::class)
@Composable
fun ObjectDetailPage(backStack: NavBackStack<Route>, viewModel: AstronomyViewModel, objectId: String) {
    val visibleSky by viewModel.visibleSky.collectAsState()
    val riseSet by viewModel.riseSet.collectAsState()
    val trajectory by viewModel.trajectory.collectAsState()

    val detail = remember(objectId, visibleSky) { resolveDetail(objectId, visibleSky, viewModel) }

    // Dialog detail: already correct metadata, just letterbox the column on
    // expanded windows so it keeps a readable measure on desktop.
    DesktopMaxWidthContainer {
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (detail == null) {
                Text(stringResource(R.string.object_not_found, objectId))
                return@Column
            }
            Text(detail.title, style = MaterialTheme.typography.headlineSmall)
            Text(detail.subtitle, style = MaterialTheme.typography.bodyMedium)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.ra_dec, detail.raDeg.format(2), detail.decDeg.format(2)))
                    Text(stringResource(R.string.ra_h_h, (detail.raDeg/15.0).format(2)))
                    detail.altAz?.let { Text(stringResource(R.string.alt_az, it.first.format(1), it.second.format(1))) }
                    detail.mag?.let { Text(stringResource(R.string.mag, it.format(1))) }
                    detail.extra?.let { Text(it) }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.rise_transit_set), style = MaterialTheme.typography.titleSmall)
                    val rs = riseSet
                    if (rs == null) Text(stringResource(R.string.calculating))
                    else if (rs.isNeverUp) Text(stringResource(R.string.never_rises_at_this_location))
                    else if (rs.isCircumpolar) Text(stringResource(R.string.circumpolar_never_sets))
                    else {
                        Text(stringResource(R.string.rise_1, rs.riseJd?.let { jdToLocal(it) } ?: "—"))
                        Text(stringResource(R.string.transit_1, rs.transitJd?.let { jdToLocal(it) } ?: "—"))
                        Text(stringResource(R.string.set_1, rs.setJd?.let { jdToLocal(it) } ?: "—"))
                    }
                }
            }

            // No center button per request – detail only, view always follows phone
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { backStack.pop() }) { Text(stringResource(UiR.string.close)) }
            }

            if (trajectory.isNotEmpty()) {
                Text(stringResource(R.string.trajectory_24h_15m_steps_yellow_line_on), style = MaterialTheme.typography.labelSmall)
            }
        }

        TopAppBarOverlay(
            modifier = Modifier.align(Alignment.TopCenter),
            onNavigateBack = { backStack.pop() },
        )
    }
}

private data class DetailInfo(
    val title: String,
    val subtitle: String,
    val raDeg: Double,
    val decDeg: Double,
    val altAz: Pair<Double, Double>?,
    val mag: Double?,
    val raDec: Pair<Double, Double>?,
    val extra: String?
)

@OptIn(ExperimentalTime::class)
private fun resolveDetail(
    id: String,
    sky: com.vayunmathur.astronomy.platform.VisibleSky,
    vm: AstronomyViewModel,
): DetailInfo? {
    return when {
        id == "SUN" -> {
            val s = sky.sun ?: return null
            DetailInfo(
                "Sun", "Star", s.raDec.raDeg, s.raDec.decDeg, s.altAz.altDeg to s.altAz.azDeg,
                -26.7, s.raDec.raRad to s.raDec.decRad, "Distance ${s.distanceAu.format(3)} AU",
            )
        }
        id == "MOON" -> {
            val m = sky.moon ?: return null
            DetailInfo(
                "Moon", com.vayunmathur.astronomy.domain.engine.LunarCalculator.phaseName(m.phase),
                m.raDec.raDeg, m.raDec.decDeg, m.altAz.altDeg to m.altAz.azDeg, null,
                m.raDec.raRad to m.raDec.decRad,
                "Illum ${(m.illumination * 100).format(0)}% Age ${m.ageDays.format(1)}d",
            )
        }
        id.startsWith("PLANET_") -> {
            val pid = id.removePrefix("PLANET_")
            val p = sky.planets.firstOrNull { it.id == pid } ?: return null
            DetailInfo(
                p.name, "Planet", p.raDec.raDeg, p.raDec.decDeg, p.altAz.altDeg to p.altAz.azDeg,
                p.mag, p.raDec.raRad to p.raDec.decRad, "Dist ${p.distanceAu.format(3)} AU",
            )
        }
        id.startsWith("STAR_") -> resolveStarDetail(id, sky, vm)
        else -> {
            val obj = vm.catalog.deepSky.firstOrNull { it.id == id } ?: return null
            val v = sky.deepSky.firstOrNull { it.obj.id == id }
            DetailInfo(
                "${obj.id} ${obj.name}", obj.type, obj.ra.toDeg(), obj.dec.toDeg(),
                v?.let { it.altAz.altDeg to it.altAz.azDeg }, obj.mag, obj.ra to obj.dec,
                "Size ${obj.sizeArcmin?.format(0) ?: "?"} arcmin",
            )
        }
    }
}

@OptIn(ExperimentalTime::class)
private fun resolveStarDetail(
    id: String,
    sky: com.vayunmathur.astronomy.platform.VisibleSky,
    vm: AstronomyViewModel,
): DetailInfo? {
    val sid = id.removePrefix("STAR_").toIntOrNull() ?: return null
    val catalogStar = vm.catalog.stars.firstOrNull { it.id == sid }
    val s = sky.stars.firstOrNull { it.star.id == sid }
        ?: catalogStar?.let { projectCatalogStar(it, sky) }
        ?: return null
    return DetailInfo(
        s.star.properName ?: s.star.name ?: "Star ${s.star.id}",
        s.star.constellation ?: s.star.spectralClass ?: "",
        s.star.ra.toDeg(), s.star.dec.toDeg(), s.altAz.altDeg to s.altAz.azDeg, s.star.mag,
        s.star.ra to s.star.dec, "BV ${s.star.bv.format(2)}",
    )
}

@OptIn(ExperimentalTime::class)
private fun projectCatalogStar(
    star: com.vayunmathur.astronomy.data.model.Star,
    sky: com.vayunmathur.astronomy.platform.VisibleSky,
): com.vayunmathur.astronomy.platform.VisibleStar? {
    val obs = sky.observer ?: return null
    val lst = com.vayunmathur.astronomy.domain.engine.TimeEngine.lstRad(sky.jd, obs.lonRad)
    val raDec = com.vayunmathur.astronomy.domain.engine.RaDec(star.ra, star.dec)
    val aa = com.vayunmathur.astronomy.domain.engine.CoordinateTransforms.raDecToAltAz(
        raDec,
        lst,
        obs.latRad,
    )
    return com.vayunmathur.astronomy.platform.VisibleStar(star, aa, raDec)
}

@OptIn(ExperimentalTime::class)
private fun jdToLocal(jd: Double): String = formatJdInZone(jd, kotlinx.datetime.TimeZone.currentSystemDefault())

/**
 * Formats a Julian date as a wall-clock date/time in [timeZone]
 * ("2025-06-21 05:30"). Rise/set instants are computed in UTC; showing
 * the raw Instant.toString() presented them as UTC (with a trailing Z)
 * to users in every other zone, hours away from the real local event.
 */
@OptIn(ExperimentalTime::class)
internal fun formatJdInZone(jd: Double, timeZone: kotlinx.datetime.TimeZone): String =
    TimeEngine.julianDateToInstant(jd)
        .toLocalDateTime(timeZone)
        .toString().take(16).replace('T', ' ')

private fun Double.format(d: Int): String = "%.${d}f".format(this)
