package com.vayunmathur.auto.protocol

import com.google.protobuf.ByteString
import com.vayunmathur.auto.protocol.gal.AccelerometerReading
import com.vayunmathur.auto.protocol.gal.CompassReading
import com.vayunmathur.auto.protocol.gal.DeadReckoningReading
import com.vayunmathur.auto.protocol.gal.DoorReading
import com.vayunmathur.auto.protocol.gal.DrivingStatusReading
import com.vayunmathur.auto.protocol.gal.EnvironmentReading
import com.vayunmathur.auto.protocol.gal.FuelReading
import com.vayunmathur.auto.protocol.gal.GearReading
import com.vayunmathur.auto.protocol.gal.GpsSatelliteEntry
import com.vayunmathur.auto.protocol.gal.GpsSatelliteReading
import com.vayunmathur.auto.protocol.gal.GyroscopeReading
import com.vayunmathur.auto.protocol.gal.HvacReading
import com.vayunmathur.auto.protocol.gal.LightReading
import com.vayunmathur.auto.protocol.gal.LocationReading
import com.vayunmathur.auto.protocol.gal.NightReading
import com.vayunmathur.auto.protocol.gal.ObdiiReading
import com.vayunmathur.auto.protocol.gal.OdometerReading
import com.vayunmathur.auto.protocol.gal.ParkingBrakeReading
import com.vayunmathur.auto.protocol.gal.PassengerReading
import com.vayunmathur.auto.protocol.gal.RawEvTripReading
import com.vayunmathur.auto.protocol.gal.RawVehicleEnergyReading
import com.vayunmathur.auto.protocol.gal.RpmReading
import com.vayunmathur.auto.protocol.gal.SensorEvent
import com.vayunmathur.auto.protocol.gal.SpeedReading
import com.vayunmathur.auto.protocol.gal.SensorType
import com.vayunmathur.auto.protocol.gal.TirePressureReading
import com.vayunmathur.auto.protocol.gal.TollCardReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Folds one payload-carrying event of every sensor type through
 * [SensorSnapshot.withEvents], and pins that payload-less or payload-free
 * (MISSING) events never clobber last-known values.
 */
class SensorSnapshotAllTypesTest {

    private fun fullEvent(): SensorEvent = SensorEvent.newBuilder()
        .setSensorType(SensorType.LOCATION)
        .setLocation(LocationReading.newBuilder().setLatitudeE7(377_000_000L).setLongitudeE7(-1_220_000_000L).setAccuracyM(5).setTimestampMs(123_456L))
        .setCompass(CompassReading.newBuilder().setHeadingDeg(270))
        .setSpeed(SpeedReading.newBuilder().setSpeedMps(13.5))
        .setNight(NightReading.newBuilder().setIsNight(true))
        .setDrivingStatus(DrivingStatusReading.newBuilder().setParked(false))
        .setRpm(RpmReading.newBuilder().setField1(3_200))
        .setOdometer(OdometerReading.newBuilder().setField1(88_000).setField2(120))
        .setFuel(FuelReading.newBuilder().setField1(55).setField2(10).setField3(700))
        .setParkingBrake(ParkingBrakeReading.newBuilder().setField1(true))
        .setGear(GearReading.newBuilder().setField1(4))
        .setObdii(ObdiiReading.newBuilder().setField1(ByteString.copyFrom(byteArrayOf(1, 2, 3))))
        .setEnvironment(EnvironmentReading.newBuilder().setField1(21).setField2(40).setField3(101))
        .setHvac(HvacReading.newBuilder().setField1(22).setField2(3))
        .setDeadReckoning(DeadReckoningReading.newBuilder().setField1(7).addAllField2(listOf(1, 2)))
        .setPassenger(PassengerReading.newBuilder().setField1(true))
        .setDoor(DoorReading.newBuilder().setField1(true).setField2(false).addAllField3(listOf(true, false)))
        .setLight(LightReading.newBuilder().setField1(2).setField2(1).setField3(true))
        .setTirePressure(TirePressureReading.newBuilder().addAllField1(listOf(32, 33, 31, 34)))
        .setAccelerometer(AccelerometerReading.newBuilder().setField1(1).setField2(2).setField3(98))
        .setGyroscope(GyroscopeReading.newBuilder().setField1(4).setField2(5).setField3(6))
        .setGpsSatellite(
            GpsSatelliteReading.newBuilder().setField1(9).setField2(3)
                .addField3(GpsSatelliteEntry.newBuilder().setField1(1).setField2(2).setField3(true).setField4(4).setField5(5)),
        )
        .setTollCard(TollCardReading.newBuilder().setField1(true))
        .setRawVehicleEnergy(RawVehicleEnergyReading.newBuilder().setField1(ByteString.copyFrom(byteArrayOf(9, 8))))
        .setRawEvTrip(RawEvTripReading.newBuilder().setField1(ByteString.copyFrom(byteArrayOf(7))))
        .build()

    /** One event per type, each carrying only its own payload (as a batch would). */
    private fun perTypeEvents(full: SensorEvent): List<SensorEvent> = listOf(
        SensorEvent.newBuilder().setSensorType(SensorType.LOCATION).setLocation(full.location).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.COMPASS).setCompass(full.compass).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.SPEED).setSpeed(full.speed).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.NIGHT_MODE).setNight(full.night).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.DRIVING_STATUS_DATA).setDrivingStatus(full.drivingStatus).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.RPM).setRpm(full.rpm).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.ODOMETER).setOdometer(full.odometer).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.FUEL).setFuel(full.fuel).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.PARKING_BRAKE).setParkingBrake(full.parkingBrake).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.GEAR).setGear(full.gear).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.OBDII_DIAGNOSTIC_CODE).setObdii(full.obdii).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.ENVIRONMENT_DATA).setEnvironment(full.environment).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.HVAC_DATA).setHvac(full.hvac).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.DEAD_RECKONING_DATA).setDeadReckoning(full.deadReckoning).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.PASSENGER_DATA).setPassenger(full.passenger).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.DOOR_DATA).setDoor(full.door).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.LIGHT_DATA).setLight(full.light).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.TIRE_PRESSURE_DATA).setTirePressure(full.tirePressure).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.ACCELEROMETER_DATA).setAccelerometer(full.accelerometer).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.GYROSCOPE_DATA).setGyroscope(full.gyroscope).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.GPS_SATELLITE_DATA).setGpsSatellite(full.gpsSatellite).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.TOLL_CARD).setTollCard(full.tollCard).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.RAW_VEHICLE_ENERGY_MODEL).setRawVehicleEnergy(full.rawVehicleEnergy).build(),
        SensorEvent.newBuilder().setSensorType(SensorType.RAW_EV_TRIP_SETTINGS).setRawEvTrip(full.rawEvTrip).build(),
    )

    @Test
    fun `every payload type folds into the snapshot`() {
        val full = fullEvent()
        val snapshot = SensorSnapshot().withEvents(perTypeEvents(full))
        assertEquals(LocationFix(377_000_000L, -1_220_000_000L, 5, 123_456L), snapshot.location)
        assertEquals(270, snapshot.compassHeadingDeg)
        assertEquals(13.5, snapshot.speedMps)
        assertEquals(true, snapshot.isNight)
        assertFalse(snapshot.parked)
        assertEquals(full.rpm, snapshot.rpm)
        assertEquals(full.odometer, snapshot.odometer)
        assertEquals(full.fuel, snapshot.fuel)
        assertEquals(full.parkingBrake, snapshot.parkingBrake)
        assertEquals(full.gear, snapshot.gear)
        assertEquals(full.obdii, snapshot.obdii)
        assertEquals(full.environment, snapshot.environment)
        assertEquals(full.hvac, snapshot.hvac)
        assertEquals(full.deadReckoning, snapshot.deadReckoning)
        assertEquals(full.passenger, snapshot.passenger)
        assertEquals(full.door, snapshot.door)
        assertEquals(full.light, snapshot.light)
        assertEquals(full.tirePressure, snapshot.tirePressure)
        assertEquals(full.accelerometer, snapshot.accelerometer)
        assertEquals(full.gyroscope, snapshot.gyroscope)
        assertEquals(full.gpsSatellite, snapshot.gpsSatellite)
        assertEquals(full.tollCard, snapshot.tollCard)
        assertEquals(full.rawVehicleEnergy, snapshot.rawVehicleEnergy)
        assertEquals(full.rawEvTrip, snapshot.rawEvTrip)
        // Equality / hash over the populated snapshot is stable.
        assertEquals(snapshot, snapshot.copy())
        assertEquals(snapshot.hashCode(), snapshot.copy().hashCode())
        assertTrue(snapshot.toString().contains("speedMps=13.5"))
    }

    @Test
    fun `payload-less events of every type keep last known`() {
        val full = fullEvent()
        val populated = SensorSnapshot().withEvents(perTypeEvents(full))
        val bare = SensorType.entries
            .map { SensorEvent.newBuilder().setSensorType(it).build() }
        assertEquals(populated, populated.withEvents(bare))
        assertEquals(SensorSnapshot(), SensorSnapshot().withEvents(bare))
        assertEquals(SensorSnapshot(), SensorSnapshot().withEvents(emptyList()))
    }

    @Test
    fun `optional sub-fields fall back to null when absent`() {
        val event = SensorEvent.newBuilder().setSensorType(SensorType.LOCATION)
            .setLocation(LocationReading.newBuilder().setLatitudeE7(1L).setLongitudeE7(2L))
            .build()
        val snapshot = SensorSnapshot().withEvents(listOf(event))
        assertEquals(LocationFix(1L, 2L, null, null), snapshot.location)
        // Speed/night/driving/compass with a payload object but no leaf value change nothing.
        val leaves = listOf(
            SensorEvent.newBuilder().setSensorType(SensorType.SPEED).setSpeed(SpeedReading.getDefaultInstance()).build(),
            SensorEvent.newBuilder().setSensorType(SensorType.NIGHT_MODE).setNight(NightReading.getDefaultInstance()).build(),
            SensorEvent.newBuilder().setSensorType(SensorType.DRIVING_STATUS_DATA).setDrivingStatus(DrivingStatusReading.getDefaultInstance()).build(),
            SensorEvent.newBuilder().setSensorType(SensorType.COMPASS).setCompass(CompassReading.getDefaultInstance()).build(),
        )
        val after = snapshot.withEvents(leaves)
        assertEquals(0.0, after.speedMps)
        assertNull(after.isNight)
        assertTrue(after.parked)
        assertNull(after.compassHeadingDeg)
    }

    @Test
    fun `full sensor event serializes and parses back identically`() {
        val full = fullEvent()
        val parsed = SensorEvent.parseFrom(full.toByteArray())
        assertEquals(full, parsed)
        assertEquals(full.hashCode(), parsed.hashCode())
        assertTrue(parsed.hasLocation() && parsed.hasGpsSatellite() && parsed.hasRawEvTrip())
        assertNotNull(parsed.toString())
        // A fresh builder merged from the parsed message re-serializes identically.
        val rebuilt = SensorEvent.newBuilder().mergeFrom(parsed).build()
        assertTrue(full.toByteArray().contentEquals(rebuilt.toByteArray()))
    }
}
