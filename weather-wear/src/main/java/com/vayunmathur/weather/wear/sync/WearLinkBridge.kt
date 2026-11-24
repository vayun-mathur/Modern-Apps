package com.vayunmathur.weather.wear.sync
import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import com.vayunmathur.weather.wear.data.WearWeatherStore
import com.vayunmathur.weather.wear.domain.WearWeatherProtocol
import com.vayunmathur.weather.wear.domain.WearWeatherSnapshot
import com.vayunmathur.weather.wear.domain.WearWatchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
object WearLinkBridge {
    private const val TAG="WearLinkBridge"; private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    @Volatile private var node:WearLinkNode?=null
    fun get(c:Context):WearLinkNode{ val a=c.applicationContext; return node?:synchronized(this){node?:create(a).also{node=it}} }
    private fun create(c:Context):WearLinkNode{
        val l=object:WearLinkListener{ override fun onData(path:String,body:ByteArray){ if(path!=WearWeatherProtocol.SNAPSHOT_PATH)return
            val s=runCatching{WearWeatherProtocol.json.decodeFromString<WearWeatherSnapshot>(body.decodeToString())}.getOrNull()?:run{Log.status(TAG,"malformed snapshot");return}
            WearWeatherStore.get(c).setSnapshot(s) } }
        return WearLinkNode(c,WearWeatherProtocol.CAPABILITY,l).also{it.start()}
    }
    fun publishWatchState(c:Context,s:WearWeatherSnapshot){ scope.launch{ get(c).putData(WearWeatherProtocol.WATCH_STATE_PATH, WearWeatherProtocol.json.encodeToString(WearWatchState(s.generatedAtMillis,s.locations.size)).encodeToByteArray()) } }
    suspend fun requestSync(c:Context):Boolean=get(c).sendMessage(WearWeatherProtocol.REQUEST_SYNC_PATH,ByteArray(0))
}
suspend fun requestWearSync(c:Context):Boolean=WearLinkBridge.requestSync(c)
