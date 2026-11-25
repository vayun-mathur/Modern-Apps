package com.vayunmathur.weather.wear.ui
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import com.vayunmathur.weather.wear.domain.wearTemp
@Composable fun WearLocationsScreen(store:WearWeatherStore, onOpen:(Long)->Unit){
 val s by store.snapshot.collectAsState()
 WearLocationsContent(snapshot=s, onOpen=onOpen)
}

/**
 * Stateless locations body behind [WearLocationsScreen]: the snapshot is a
 * parameter, so Compose previews (src/screenshotTest) render a literal fixture
 * with no store or phone.
 */
@Composable internal fun WearLocationsContent(snapshot:com.vayunmathur.weather.wear.domain.WearWeatherSnapshot, onOpen:(Long)->Unit){
 val s=snapshot; val st=rememberScalingLazyListState()
 ScalingLazyColumn(state=st){
  item{ Text(stringResource(R.string.app_name),style=MaterialTheme.typography.title3,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp)) }
  if(s.locations.isEmpty()){ item{ Text(stringResource(R.string.wear_empty),style=MaterialTheme.typography.body2,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp)) } }
  else { items(s.locations,key={it.locationId}){ loc ->
    Chip(onClick={onOpen(loc.locationId)}, label={Text(loc.name.ifBlank{"Location"},maxLines=1)}, secondaryLabel={Text(loc.current?.let{"${wearTemp(it.temperatureC)} ${wearConditionLabel(it.weatherCode)}"}?:stringResource(R.string.wear_locations),maxLines=1)}, colors=ChipDefaults.secondaryChipColors(), modifier=Modifier.fillMaxWidth().padding(horizontal=8.dp))
  }
   if(s.truncated) item{ Text(stringResource(R.string.wear_truncated),style=MaterialTheme.typography.body2,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp)) }
  }
 }
}
