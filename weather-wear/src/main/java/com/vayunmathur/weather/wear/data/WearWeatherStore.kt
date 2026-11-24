package com.vayunmathur.weather.wear.data
import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.weather.wear.domain.WearWeatherProtocol
import com.vayunmathur.weather.wear.domain.WearWeatherSnapshot
import com.vayunmathur.weather.wear.sync.WearLinkBridge
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
class WearWeatherStore private constructor(private val context: Context) {
    companion object { private const val TAG="WearWeatherStore"; @Volatile private var i:WearWeatherStore?=null
        fun get(c:Context):WearWeatherStore=i?:synchronized(this){i?:WearWeatherStore(c.applicationContext).also{i=it}} }
    private val file=File(context.filesDir,"wear-weather-snapshot.json")
    private val _s=MutableStateFlow(load()); val snapshot:StateFlow<WearWeatherSnapshot> = _s.asStateFlow()
    fun setSnapshot(s:WearWeatherSnapshot){ _s.value=s; runCatching{file.writeText(WearWeatherProtocol.json.encodeToString(s))}.onFailure{Log.status(TAG,it.message?:"persist failed")}; publishWatchState() }
    fun publishWatchState(){ WearLinkBridge.publishWatchState(context,_s.value) }
    private fun load():WearWeatherSnapshot=runCatching{WearWeatherProtocol.json.decodeFromString<WearWeatherSnapshot>(file.readText())}.getOrDefault(WearWeatherSnapshot())
}
