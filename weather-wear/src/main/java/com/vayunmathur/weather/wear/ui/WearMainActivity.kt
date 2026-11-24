package com.vayunmathur.weather.wear.ui
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.material.MaterialTheme
import com.vayunmathur.weather.wear.data.WearWeatherStore
import com.vayunmathur.weather.wear.sync.WearLinkBridge
class WearMainActivity:ComponentActivity(){ override fun onCreate(b:Bundle?){ super.onCreate(b)
 com.vayunmathur.library.wearlink.WearLinkPermissions.requestIfNeeded(this)
 val store=WearWeatherStore.get(this); WearLinkBridge.get(this); store.publishWatchState()
 setContent{ MaterialTheme{ var route by remember{mutableStateOf<WearRoute>(WearRoute.Locations)}
  when(val cur=route){ WearRoute.Locations->WearLocationsScreen(store){route=WearRoute.Location(it)}
   is WearRoute.Location->WearLocationScreen(store,cur.locationId){route=WearRoute.Locations} } } }
} }
