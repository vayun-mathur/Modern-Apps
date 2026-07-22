package com.vayunmathur.astronomy.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.astronomy.Route
import com.vayunmathur.astronomy.domain.engine.TimeEngine
import com.vayunmathur.astronomy.domain.engine.toDeg
import com.vayunmathur.astronomy.ui.AstronomyViewModel
import com.vayunmathur.library.ui.*
import com.vayunmathur.library.util.NavBackStack
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
@Composable
fun ObjectDetailPage(backStack: NavBackStack<Route>, viewModel: AstronomyViewModel, objectId: String) {
    val visibleSky by viewModel.visibleSky.collectAsState()
    val riseSet by viewModel.riseSet.collectAsState()
    val trajectory by viewModel.trajectory.collectAsState()

    val detail = remember(objectId, visibleSky) { resolveDetail(objectId, visibleSky, viewModel) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(detail?.title ?: objectId) }, navigationIcon = { IconNavigation(backStack) })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (detail == null) {
                Text("Object not found: $objectId")
                return@Column
            }
            Text(detail.title, style = MaterialTheme.typography.headlineSmall)
            Text(detail.subtitle, style = MaterialTheme.typography.bodyMedium)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("RA: ${detail.raDeg.format(2)}° Dec: ${detail.decDeg.format(2)}°")
                    Text("RA h: ${(detail.raDeg/15.0).format(2)}h")
                    detail.altAz?.let { Text("Alt: ${it.first.format(1)}° Az: ${it.second.format(1)}°") }
                    detail.mag?.let { Text("Mag: ${it.format(1)}") }
                    detail.extra?.let { Text(it) }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Rise / Transit / Set", style = MaterialTheme.typography.titleSmall)
                    val rs = riseSet
                    if (rs == null) Text("Calculating...")
                    else if (rs.isNeverUp) Text("Never rises at this location")
                    else if (rs.isCircumpolar) Text("Circumpolar - never sets")
                    else {
                        Text("Rise: ${rs.riseJd?.let { jdToLocal(it) } ?: "--"}")
                        Text("Transit: ${rs.transitJd?.let { jdToLocal(it) } ?: "--"}")
                        Text("Set: ${rs.setJd?.let { jdToLocal(it) } ?: "--"}")
                    }
                }
            }

            // No center button per request – detail only, view always follows phone
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { backStack.pop() }) { Text("Close") }
            }

            if (trajectory.isNotEmpty()) {
                Text("Trajectory 24h (15m steps) - yellow line on map", style = MaterialTheme.typography.labelSmall)
            }
        }
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
private fun resolveDetail(id: String, sky: com.vayunmathur.astronomy.ui.VisibleSky, vm: AstronomyViewModel): DetailInfo? {
    return when {
        id == "SUN" -> {
            val s = sky.sun ?: return null
            DetailInfo("Sun", "Star", s.raDec.raDeg, s.raDec.decDeg, s.altAz.altDeg to s.altAz.azDeg, -26.7, s.raDec.raRad to s.raDec.decRad, "Distance ${s.distanceAu.format(3)} AU")
        }
        id == "MOON" -> {
            val m = sky.moon ?: return null
            DetailInfo("Moon", com.vayunmathur.astronomy.domain.engine.LunarCalculator.phaseName(m.phase), m.raDec.raDeg, m.raDec.decDeg, m.altAz.altDeg to m.altAz.azDeg, null, m.raDec.raRad to m.raDec.decRad, "Illum ${(m.illumination*100).format(0)}% Age ${m.ageDays.format(1)}d")
        }
        id.startsWith("PLANET_") -> {
            val pid = id.removePrefix("PLANET_")
            val p = sky.planets.firstOrNull { it.id == pid } ?: return null
            DetailInfo(p.name, "Planet", p.raDec.raDeg, p.raDec.decDeg, p.altAz.altDeg to p.altAz.azDeg, p.mag, p.raDec.raRad to p.raDec.decRad, "Dist ${p.distanceAu.format(3)} AU")
        }
        id.startsWith("STAR_") -> {
            val sid = id.removePrefix("STAR_").toIntOrNull() ?: return null
            val s = sky.stars.firstOrNull { it.star.id == sid } ?: vm.getCatalog().stars.firstOrNull { it.id == sid }?.let {
                val obs = sky.observer ?: return@let null
                val jd = sky.jd
                val lst = com.vayunmathur.astronomy.domain.engine.TimeEngine.lstRad(jd, obs.lonRad)
                val aa = com.vayunmathur.astronomy.domain.engine.CoordinateTransforms.raDecToAltAz(com.vayunmathur.astronomy.domain.engine.RaDec(it.ra, it.dec), lst, obs.latRad)
                com.vayunmathur.astronomy.ui.VisibleStar(it, aa, com.vayunmathur.astronomy.domain.engine.RaDec(it.ra, it.dec))
            } ?: return null
            DetailInfo(s.star.properName ?: s.star.name ?: "Star ${s.star.id}", s.star.constellation ?: s.star.spectralClass ?: "", s.star.ra.toDeg(), s.star.dec.toDeg(), s.altAz.altDeg to s.altAz.azDeg, s.star.mag, s.star.ra to s.star.dec, "BV ${s.star.bv.format(2)}")
        }
        else -> {
            val obj = vm.getCatalog().deepSky.firstOrNull { it.id == id } ?: return null
            val v = sky.deepSky.firstOrNull { it.obj.id == id }
            DetailInfo("${obj.id} ${obj.name}", obj.type, obj.ra.toDeg(), obj.dec.toDeg(), v?.let { it.altAz.altDeg to it.altAz.azDeg }, obj.mag, obj.ra to obj.dec, "Size ${obj.sizeArcmin?.format(0) ?: "?"} arcmin")
        }
    }
}

@OptIn(ExperimentalTime::class)
private fun jdToLocal(jd: Double): String = TimeEngine.julianDateToInstant(jd).toString()

private fun Double.format(d: Int): String = "%.${d}f".format(this)
