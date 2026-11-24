package com.vayunmathur.weather.wear.domain
import kotlin.math.roundToInt
fun wearConditionLabel(code:Int):String=when(code){0->"Clear";1->"Mostly clear";2->"Partly cloudy";3->"Cloudy";45,48->"Fog";51,53,55,56,57->"Drizzle";61,63,65,66,67,80,81,82->"Rain";71,73,75,77,85,86->"Snow";95,96,99->"Thunderstorm";else->"Cloudy"}
fun wearTemp(c:Double):String="${c.roundToInt()}°"
fun wearHourLabel(iso:String):String=iso.substringAfter("T","").take(5).ifBlank{"--:--"}
fun wearDayLabel(iso:String):String=iso.take(10)
