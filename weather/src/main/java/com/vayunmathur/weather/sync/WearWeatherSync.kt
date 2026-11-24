package com.vayunmathur.weather.sync
import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import com.vayunmathur.library.wearlink.WearLinkState
import com.vayunmathur.weather.data.WeatherRepository
import com.vayunmathur.weather.data.weatherJson
import com.vayunmathur.weather.domain.WearWeatherCurrent
import com.vayunmathur.weather.domain.WearWeatherDay
import com.vayunmathur.weather.domain.WearWeatherHour
import com.vayunmathur.weather.domain.WearWeatherLocation
import com.vayunmathur.weather.domain.WearWeatherProtocol
import com.vayunmathur.weather.domain.WearWeatherSnapshot
import com.vayunmathur.weather.domain.WearWatchState
import com.vayunmathur.weather.domain.buildWearWeatherSnapshot
import com.vayunmathur.weather.domain.roundCoord
import com.vayunmathur.weather.domain.wearCompanionPresent
import com.vayunmathur.weather.network.ForecastResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

data class WearSyncState(val companionPresent:Boolean=false, val lastPublishedAtMillis:Long?=null, val lastWatchAppliedAtMillis:Long?=null, val lastPublishedLocationCount:Int=0)

/** Phone side over WearLink: publish on forecast refresh + on watch request; presence = handshake. Absence is normal. */
class WearWeatherSync private constructor(context:Context){
 companion object{ private const val TAG="WearWeatherSync"; @Volatile private var i:WearWeatherSync?=null
  fun get(c:Context):WearWeatherSync=i?:synchronized(this){i?:WearWeatherSync(c.applicationContext).also{i=it}} }
 private val appContext=context.applicationContext; private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 private val _state=MutableStateFlow(WearSyncState()); val state:StateFlow<WearSyncState> = _state.asStateFlow()
 private val listener=object:WearLinkListener{
  override fun onData(path:String,body:ByteArray){ if(path!=WearWeatherProtocol.WATCH_STATE_PATH)return
   val ws=runCatching{WearWeatherProtocol.json.decodeFromString<WearWatchState>(body.decodeToString())}.getOrNull()?:return
   _state.update{it.copy(lastWatchAppliedAtMillis=ws.appliedGeneratedAtMillis.takeIf{a->a>0L})} }
  override fun onMessage(path:String,body:ByteArray){ if(path==WearWeatherProtocol.REQUEST_SYNC_PATH) scope.launch{publishSnapshot()} }
 }
 private val node=WearLinkNode(appContext,WearWeatherProtocol.CAPABILITY,listener); private var started=false
 fun start(){ if(started)return; started=true; scope.launch{ node.state.collect{ ls-> val p=ls is WearLinkState.Connected; val was=_state.value.companionPresent; _state.update{it.copy(companionPresent=wearCompanionPresent(if(p)listOf("peer")else emptyList()))}; if(p&&!was) publishSnapshot() } }; node.start() }
 fun onForecastRefreshed(){ scope.launch{publishSnapshot()} }
 fun syncNow(){ scope.launch{publishSnapshot()} }
 suspend fun publishSnapshot(){ val s=buildSnapshot()?:return; val sent=node.putData(WearWeatherProtocol.SNAPSHOT_PATH, WearWeatherProtocol.json.encodeToString(s).encodeToByteArray())
  if(sent) _state.update{it.copy(lastPublishedAtMillis=s.generatedAtMillis,lastPublishedLocationCount=s.locations.size)} else Log.status(TAG,"publish not delivered (no live session)") }
 private suspend fun buildSnapshot():WearWeatherSnapshot?=runCatching{
  val repo=WeatherRepository.get(appContext); val locs=repo.getLocations().sortedWith(compareByDescending<com.vayunmathur.weather.data.SavedLocation>{it.isCurrent}.thenBy{it.displayOrder})
  val candidates=locs.mapNotNull{ loc-> val cache=repo.getCache(roundCoord(loc.latitude),roundCoord(loc.longitude))?:return@mapNotNull null
   val f=runCatching{weatherJson.decodeFromString<ForecastResponse>(cache.forecastJson)}.getOrNull()?:return@mapNotNull null; mapLocation(loc,f) }
  if(candidates.isEmpty()) null else buildWearWeatherSnapshot(candidates, System.currentTimeMillis())
 }.onFailure{Log.status(TAG,"build failed: ${it.message}")}.getOrNull()

 private fun mapLocation(loc:com.vayunmathur.weather.data.SavedLocation, f:ForecastResponse):WearWeatherLocation{
  val cur=f.current; val hourlyDto=f.hourly; val dailyDto=f.daily; val startIdx=if(cur!=null&&hourlyDto!=null) hourlyDto.time.indexOfFirst{it>=cur.time}.let{if(it<0)0 else it} else 0
  val hourly=hourlyDto?.time?.drop(startIdx)?.mapIndexedNotNull{ i, t-> val idx=startIdx+i; WearWeatherHour(t, hourlyDto.temperature.getOrElse(idx){0.0}, hourlyDto.weatherCode.getOrElse(idx){-1}, hourlyDto.precipitationProbability.getOrElse(idx){0}, hourlyDto.isDay.getOrElse(idx){1}==1) }?:emptyList()
  val daily=dailyDto?.time?.mapIndexed{ i,d-> WearWeatherDay(d, dailyDto.weatherCode.getOrElse(i){-1}, dailyDto.temperatureMax.getOrElse(i){0.0}, dailyDto.temperatureMin.getOrElse(i){0.0}, dailyDto.precipitationProbabilityMax.getOrElse(i){0}) }?:emptyList()
  return WearWeatherLocation(loc.id, loc.name, loc.country, loc.isCurrent, f.timezone?:"UTC", cur?.let{WearWeatherCurrent(it.time,it.temperature,it.apparentTemperature,it.relativeHumidity,it.weatherCode,it.isDay==1,it.windSpeed,it.windDirection,it.pressureMsl)}, hourly, daily)
 }
}
