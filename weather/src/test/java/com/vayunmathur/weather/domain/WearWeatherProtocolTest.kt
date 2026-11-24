package com.vayunmathur.weather.domain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
class WearWeatherProtocolTest {
 private fun loc(id:Long,primary:Boolean=false)=WearWeatherLocation(locationId=id,name="Loc $id",country="X",isPrimary=primary,timezone="UTC",current=WearWeatherCurrent("2026-11-24T12:00",20.0,19.0,50,1,true,10.0,90,1013.0),hourly=listOf(WearWeatherHour("2026-11-24T13:00",21.0,1,10,true)),daily=listOf(WearWeatherDay("2026-11-24",1,22.0,15.0,10)))
 @Test fun goldenSnapshotJson(){ val s=WearWeatherSnapshot(1000L,listOf(loc(42L,true)),false)
  assertEquals("""{"generatedAtMillis":1000,"locations":[{"locationId":42,"name":"Loc 42","country":"X","isPrimary":true,"current":{"timeIso":"2026-11-24T12:00","temperatureC":20.0,"apparentTemperatureC":19.0,"humidityPercent":50,"weatherCode":1,"windSpeedKmh":10.0,"windDirectionDegrees":90,"pressureHpa":1013.0},"hourly":[{"timeIso":"2026-11-24T13:00","temperatureC":21.0,"weatherCode":1,"precipitationProbabilityPercent":10}],"daily":[{"dateIso":"2026-11-24","weatherCode":1,"temperatureMaxC":22.0,"temperatureMinC":15.0,"precipitationProbabilityMaxPercent":10}]}]}""", WearWeatherProtocol.json.encodeToString(s)) }
 @Test fun roundTrips(){ val s=WearWeatherSnapshot(9L,listOf(loc(1L)),true); assertEquals(s, WearWeatherProtocol.json.decodeFromString<WearWeatherSnapshot>(WearWeatherProtocol.json.encodeToString(s))) }
 @Test fun buildPrimaryFirstAndCapsLocations(){ val s=buildWearWeatherSnapshot((1L..6L).map{loc(it,it==3L)},0L); assertEquals(3L,s.locations.first().locationId); assertEquals(WearWeatherProtocol.MAX_LOCATIONS,s.locations.size); assertTrue(s.truncated) }
 @Test fun buildCapsHourlyDaily(){ val big=loc(1L,true).copy(hourly=(1..40).map{WearWeatherHour("2026-11-24T13:00",1.0,1)},daily=(1..10).map{WearWeatherDay("2026-11-24",1,2.0,1.0)}); val s=buildWearWeatherSnapshot(listOf(big),0L); assertEquals(WearWeatherProtocol.MAX_HOURLY,s.locations[0].hourly.size); assertEquals(WearWeatherProtocol.MAX_DAILY,s.locations[0].daily.size) }
 @Test fun presenceAbsent(){ assertFalse(wearCompanionPresent(emptyList())); assertTrue(wearCompanionPresent(listOf("n"))) }
 @Test fun buildCapsBytes(){ val many=(1L..4L).map{ loc(it).copy(name="x".repeat(200),hourly=(1..24).map{WearWeatherHour("2026-11-24T13:00",1.0,1)}) }; val s=buildWearWeatherSnapshot(many,0L); assertTrue(WearWeatherProtocol.json.encodeToString(s).encodeToByteArray().size<=WearWeatherProtocol.MAX_JSON_BYTES) }
}
