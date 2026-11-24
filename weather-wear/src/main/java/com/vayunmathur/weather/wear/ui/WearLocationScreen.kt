package com.vayunmathur.weather.wear.ui
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.weather.wear.R
import com.vayunmathur.weather.wear.data.WearWeatherStore
import com.vayunmathur.weather.wear.domain.wearConditionLabel
import com.vayunmathur.weather.wear.domain.wearDayLabel
import com.vayunmathur.weather.wear.domain.wearHourLabel
import com.vayunmathur.weather.wear.domain.wearTemp
import com.vayunmathur.weather.wear.sync.requestWearSync
import kotlinx.coroutines.launch
@Composable fun WearLocationScreen(store:WearWeatherStore, locationId:Long, onBack:()->Unit){
 val s by store.snapshot.collectAsState(); val loc=s.locations.firstOrNull{it.locationId==locationId}?:s.locations.firstOrNull()
 val ctx=LocalContext.current; val scope=rememberCoroutineScope(); var note by remember{mutableStateOf<String?>(null)}
 val req=stringResource(R.string.wear_sync_requested); val no=stringResource(R.string.wear_no_phone); val st=rememberScalingLazyListState()
 ScalingLazyColumn(state=st){
  if(loc==null){ item{ Text(stringResource(R.string.wear_empty),textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp)) } }
  else{
   item{ Text(loc.name.ifBlank{"Location"},style=MaterialTheme.typography.title3,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp)) }
   loc.current?.let{c-> item{ Text("${wearTemp(c.temperatureC)} ${wearConditionLabel(c.weatherCode)}",style=MaterialTheme.typography.title2,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp)) }
    item{ Text("Feels ${wearTemp(c.apparentTemperatureC)} · ${c.humidityPercent}% · ${c.windSpeedKmh.toInt()} km/h",style=MaterialTheme.typography.body2,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp)) } }
   if(loc.hourly.isNotEmpty()){ item{ Text(stringResource(R.string.wear_hourly),style=MaterialTheme.typography.caption1,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp)) }
    items(loc.hourly){h-> Chip(onClick={},label={Text("${wearHourLabel(h.timeIso)} ${wearTemp(h.temperatureC)}",maxLines=1)},secondaryLabel={Text("${wearConditionLabel(h.weatherCode)} ${h.precipitationProbabilityPercent}%",maxLines=1)},colors=ChipDefaults.secondaryChipColors(),modifier=Modifier.fillMaxWidth().padding(horizontal=8.dp))} }
   if(loc.daily.isNotEmpty()){ item{ Text(stringResource(R.string.wear_daily),style=MaterialTheme.typography.caption1,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp)) }
    items(loc.daily){d-> Chip(onClick={},label={Text("${wearDayLabel(d.dateIso)} ${wearTemp(d.temperatureMinC)}–${wearTemp(d.temperatureMaxC)}",maxLines=1)},secondaryLabel={Text("${wearConditionLabel(d.weatherCode)} ${d.precipitationProbabilityMaxPercent}%",maxLines=1)},colors=ChipDefaults.secondaryChipColors(),modifier=Modifier.fillMaxWidth().padding(horizontal=8.dp))} }
  }
  note?.let{n-> item{ Text(n,style=MaterialTheme.typography.body2,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp)) } }
  item{ Chip(onClick={scope.launch{note=if(requestWearSync(ctx))req else no}},label={Text(stringResource(R.string.wear_request_sync),maxLines=1)},colors=ChipDefaults.secondaryChipColors(),modifier=Modifier.fillMaxWidth().padding(horizontal=8.dp)) }
  item{ Chip(onClick=onBack,label={Text(stringResource(R.string.wear_back),maxLines=1)},colors=ChipDefaults.secondaryChipColors(),modifier=Modifier.fillMaxWidth().padding(horizontal=8.dp)) }
 }
}
