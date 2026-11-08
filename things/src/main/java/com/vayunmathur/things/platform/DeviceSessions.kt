package com.vayunmathur.things.platform

import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Bottle/scale session logic for [DeviceController], kept in this file so the controller object
 * itself stays under detekt's TooManyFunctions cap.
 *
 * These are extension functions, so every call site keeps reading `DeviceController.foo(...)`
 * and no caller — including other agents' files — needs to change.
 */

// --- Callbacks invoked by the BLE managers ---

fun DeviceController.onDrinkLog(reading: HydrationReading) {
    // Health data is owned by the Health app; this app only writes it to Health Connect and
    // never displays it. Body of the record stays; the on-screen total/list is gone.
    writeHydrationToHealthConnect(reading)
}

fun DeviceController.onBottleStatus(status: BottleStatus) {
    // Merge rather than replace: the bottle sends single-field RT updates, and its cache is
    // cleared on every reconnect, so assigning all five would blank whatever this particular
    // packet happened not to carry.
    status.tempC?.let { waterTempC.value = it }
    status.tds?.let { tds.value = it }
    status.batteryPct?.let { batteryPct.value = it }
    status.volumePct?.let { bottleVolumePct.value = it }
    charging.value = status.charging
    bottleLastUpdated.value = System.currentTimeMillis()
    persistBottleTelemetry()
}

fun DeviceController.onScaleRealtimeWeight(weight: Double) {
    scaleRealtimeWeight.value = weight
    scaleConnectionState.value = "Weighing... %.1f kg".format(weight)
}

fun DeviceController.onScaleMeasurement(
    weightKg: Double,
    r50: Int,
    r500: Int,
    segmental: SegmentalImpedance? = null,
    measuredAtMillis: Long = System.currentTimeMillis(),
) {
    scaleRealtimeWeight.value = null
    scaleWeight.value = weightKg
    scaleR50.value = if (r50 == 0) null else r50
    scaleR500.value = if (r500 == 0) null else r500
    scaleConnectionState.value = "Scale: %.1f kg".format(weightKg)
    // Recompute metrics with current profile.
    val profile = readScaleProfile(
        fallbackAge = scaleProfile.value.age,
        fallbackHeightCm = scaleProfile.value.heightCm,
    )
    scaleProfile.value = profile
    val metrics = BodyComposition.calculate(profile, ScaleMeasurement(weightKg, r50, r500, segmental))
    scaleMetrics.value = metrics
    try {
        devicePrefs.edit {
            putString("scale_sex", profile.sex.name)
            putInt("scale_age", profile.age)
            putString("scale_height", profile.heightCm.toString())
            putString("scale_athlete", profile.athlete.toString())
        }
    } catch (_: Exception) {}
    writeBodyCompositionToHealthConnect(weightKg, metrics, measuredAtMillis, clientRecordId = null)
}

/**
 * A measurement the scale buffered while the phone was away. It is archived to Health Connect
 * under its own timestamp but must not touch the live state, which describes right now. The
 * scale replays its whole buffer on every connect, so the record ID lets Health Connect
 * upsert instead of accumulating duplicates.
 */
fun DeviceController.onScaleHistory(weightKg: Double, r50: Int, r500: Int, measuredAtMillis: Long) {
    val metrics = BodyComposition.calculate(
        scaleProfile.value,
        ScaleMeasurement(weightKg, r50, r500, null),
    )
    writeBodyCompositionToHealthConnect(
        weightKg = weightKg,
        metrics = metrics,
        measuredAtMillis = measuredAtMillis,
        clientRecordId = "scale-$measuredAtMillis",
    )
}

// --- Scale advertisement traits ---

/**
 * The scale's category and impedance-encryption flag only exist in its advertisement, so they
 * are remembered for the launch-time reconnect, which connects straight to a saved address.
 */
fun DeviceController.saveScaleAdvertisedTraits(category: Int, encryptsResistance: Boolean) {
    devicePrefs.edit {
        putInt(SCALE_CATEGORY_KEY, category)
        putBoolean(SCALE_ENCRYPT_RES_KEY, encryptsResistance)
    }
}

fun DeviceController.savedScaleCategory(): Int? =
    if (devicePrefs.contains(SCALE_CATEGORY_KEY)) devicePrefs.getInt(SCALE_CATEGORY_KEY, 0) else null

fun DeviceController.savedScaleEncryptsResistance(): Boolean =
    devicePrefs.getBoolean(SCALE_ENCRYPT_RES_KEY, false)

// --- Scale user slot ---

/**
 * Our slot on the scale, or null if we have not registered yet.
 *
 * The slot's key cannot be read back off the scale (that needs characteristics this hardware
 * does not expose), so once assigned it has to survive forever — losing it strands the slot,
 * recoverable only by resetting the scale.
 */
fun DeviceController.scaleUserIndex(): Int? =
    if (devicePrefs.contains(SCALE_USER_INDEX_KEY)) devicePrefs.getInt(SCALE_USER_INDEX_KEY, 0) else null

/** Stable per-slot secret. Generated once; the scale expects the same value on every visit. */
fun DeviceController.scaleUserKey(): Int {
    val existing = devicePrefs.getInt(SCALE_USER_KEY_KEY, 0)
    if (existing in MIN_SCALE_USER_KEY..MAX_SCALE_USER_KEY) return existing
    val generated = Random.nextInt(MIN_SCALE_USER_KEY, SCALE_USER_KEY_BOUND)
    devicePrefs.edit { putInt(SCALE_USER_KEY_KEY, generated) }
    return generated
}

fun DeviceController.saveScaleUserIndex(index: Int) {
    devicePrefs.edit { putInt(SCALE_USER_INDEX_KEY, index) }
    scaleUserSlot.value = index
}

/**
 * Wipe every user slot on the scale. It is powered off between weigh-ins, so this is recorded
 * and carried out on the next connection rather than attempted now.
 */
fun DeviceController.requestScaleReset() {
    devicePrefs.edit { putBoolean(SCALE_PENDING_RESET_KEY, true) }
    clearScaleUserSlot()
    AppMessages.show("Scale will be reset next time it connects")
}

fun DeviceController.scaleResetPending(): Boolean = devicePrefs.getBoolean(SCALE_PENDING_RESET_KEY, false)

fun DeviceController.onScaleResetDone() {
    devicePrefs.edit { remove(SCALE_PENDING_RESET_KEY) }
    clearScaleUserSlot()
}

fun DeviceController.recalcScaleMetrics() {
    val w = scaleWeight.value ?: return
    val profile = readScaleProfile(
        fallbackAge = scaleProfile.value.age,
        fallbackHeightCm = scaleProfile.value.heightCm,
    )
    scaleProfile.value = profile
    val r50 = scaleR50.value ?: 0
    val r500 = scaleR500.value ?: 0
    scaleMetrics.value = BodyComposition.calculate(profile, ScaleMeasurement(w, r50, r500, null))
    try {
        devicePrefs.edit {
            putString("scale_sex", profile.sex.name)
            putInt("scale_age", profile.age)
            putString("scale_height", profile.heightCm.toString())
            putString("scale_athlete", profile.athlete.toString())
        }
    } catch (_: Exception) {}
}

/** Profile from the UI text fields, clamped into the scale's operating range. */
internal fun DeviceController.readScaleProfile(fallbackAge: Int, fallbackHeightCm: Double): ScaleProfile =
    ScaleProfile(
        sex = scaleSex.value,
        age = scaleAge.value.toIntOrNull()?.coerceIn(MIN_SCALE_AGE, MAX_SCALE_AGE) ?: fallbackAge,
        heightCm = scaleHeight.value.toDoubleOrNull()?.coerceIn(MIN_HEIGHT_CM, MAX_HEIGHT_CM) ?: fallbackHeightCm,
        athlete = scaleAthlete.value,
    )

internal fun DeviceController.clearScaleUserSlot() {
    devicePrefs.edit {
        remove(SCALE_USER_INDEX_KEY)
        remove(SCALE_USER_KEY_KEY)
    }
    scaleUserSlot.value = null
}

internal fun DeviceController.loadScaleProfile() {
    try {
        val sexName = devicePrefs.getString("scale_sex", null)
        if (sexName != null) scaleSex.value = Sex.valueOf(sexName)
        val ageInt = devicePrefs.getInt("scale_age", -1)
        if (ageInt != -1) scaleAge.value = ageInt.toString()
        val hStr = devicePrefs.getString("scale_height", null)
        if (hStr != null) scaleHeight.value = hStr
        val ath = devicePrefs.getString("scale_athlete", null)
        if (ath != null) scaleAthlete.value = ath.toBoolean()
        scaleProfile.value = ScaleProfile(
            sex = scaleSex.value,
            age = scaleAge.value.toIntOrNull()?.coerceIn(MIN_SCALE_AGE, MAX_SCALE_AGE)
                ?: DEFAULT_SCALE_AGE,
            heightCm = scaleHeight.value.toDoubleOrNull()?.coerceIn(MIN_HEIGHT_CM, MAX_HEIGHT_CM)
                ?: DEFAULT_HEIGHT_CM,
            athlete = scaleAthlete.value,
        )
    } catch (_: Exception) {}
}

// Health Connect seams: production always talks to the real provider; tests substitute a
// fake client because the provider does not exist under Robolectric or on the emulator.
internal var healthStatusProvider: (android.content.Context) -> Int =
    { HealthConnectHelper.availabilityStatus(it) }
internal var healthClientProvider: (android.content.Context) -> HealthConnectClient =
    { HealthConnectClient.getOrCreate(it) }

internal fun DeviceController.writeHydrationToHealthConnect(reading: HydrationReading) {
    // Check Health Connect availability synchronously; writes are async.
    val status = healthStatusProvider(appContext)
    if (status != HealthConnectClient.SDK_AVAILABLE) return
    deviceScope.launch {
        try {
            val client = healthClientProvider(appContext)
            if (!HealthConnectHelper.hasAllPermissions(client)) return@launch
            // The bottle's clock runs a little ahead of the phone's, and Health Connect
            // rejects any future-dated record outright, so a few seconds of skew would
            // otherwise silently discard every drink log.
            val now = System.currentTimeMillis()
            val stamp = if (reading.epochMillis > now) now else reading.epochMillis
            val instant = java.time.Instant.ofEpochMilli(stamp)
            HealthConnectHelper.writeHydration(client, instant, reading.amountMl / ML_PER_LITER)
        } catch (_: Exception) {}
    }
}

internal fun DeviceController.writeBodyCompositionToHealthConnect(
    weightKg: Double,
    metrics: BodyMetrics,
    measuredAtMillis: Long,
    clientRecordId: String?,
) {
    val status = healthStatusProvider(appContext)
    if (status != HealthConnectClient.SDK_AVAILABLE) return
    deviceScope.launch {
        try {
            val client = healthClientProvider(appContext)
            if (!HealthConnectHelper.hasAllPermissions(client)) {
                // Otherwise a missing grant looks identical to the scale not reporting at all.
                // Only surfaced for live readings, so a history replay can't spam it.
                if (clientRecordId == null) {
                    AppMessages.show("Grant Health Connect permissions to save measurements")
                }
                return@launch
            }
            val instant = java.time.Instant.ofEpochMilli(measuredAtMillis)
            val waterMassKg = if (metrics.waterPercent > 0) weightKg * metrics.waterPercent / 100.0 else null
            HealthConnectHelper.writeBodyComposition(
                client = client,
                instant = instant,
                weightKg = weightKg,
                bodyFatPct = metrics.bodyFatPercent.takeIf { it > 0 },
                leanMassKg = metrics.lbmKg.takeIf { it > 0 },
                boneMassKg = metrics.boneKg.takeIf { it > 0 },
                bodyWaterMassKg = waterMassKg,
                bmrKcal = metrics.bmrKcal.takeIf { it > 0 },
                clientRecordId = clientRecordId,
            )
        } catch (_: Exception) {}
    }
}
