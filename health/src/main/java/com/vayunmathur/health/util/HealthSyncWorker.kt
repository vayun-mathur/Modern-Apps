package com.vayunmathur.health.util
import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.WheelchairPushesRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.health.CLASSES
import com.vayunmathur.health.data.HealthRepository
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.platform.HealthConnectAvailability
import com.vayunmathur.health.platform.HealthConnectSetup
import com.vayunmathur.library.util.DataStoreUtils
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.vayunmathur.health.data.SleepStage

/**
 * Worker that ensures local Room DB is in sync with Health Connect.
 */
class HealthSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    // Broad catch is deliberate: WorkManager takes a Result, not a throwable — any
    // failure mode (Health Connect missing, Room closed, provider crash) retries the sync.
    // The SDK and Room throw undocumented RuntimeExceptions, not just declared ones.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        return try {
            sync()
            Result.success()
        } catch (e: Exception) {
            Log.error(TAG, "Health sync failed", e)
            Result.retry()
        }
    }

    private suspend fun sync() {
        // No provider, no sync: getOrCreate throws when Health Connect is missing or too old
        // (API 33 and below without the APK), which used to fail every run. Succeed quietly —
        // Room stays the source of truth and the next periodic run retries once it is installed.
        if (HealthConnectSetup.current(applicationContext) != HealthConnectAvailability.AVAILABLE) {
            Log.status(TAG, "Health Connect is not available; skipping sync")
            return
        }
        val healthConnectClient = HealthConnectClient.getOrCreate(applicationContext)
        val repository = HealthRepository.get(applicationContext)
        val ds = DataStoreUtils.getInstance(applicationContext)
        var token = ds.getString("hc_token")
        if (token == null) {
            // 2. If no token exists, initialize one for the data types you care about
            CLASSES.forEach { clazz ->
                var pageToken: String? = null
                do {
                    val records = healthConnectClient.readRecords(
                        ReadRecordsRequest(
                            clazz,
                            TimeRangeFilter.after(Instant.EPOCH),
                            pageSize = INITIAL_PAGE_SIZE,
                            pageToken = pageToken,
                        )
                    )
                    repository.upsert(records.records.flatMap { it.toRecord() })
                    pageToken = records.pageToken
                } while (pageToken != null)
                println("Completed inserting ${clazz.simpleName}")
            }
            token = healthConnectClient.getChangesToken(
                ChangesTokenRequest(
                    recordTypes = CLASSES
                )
            )
            ds.setString("hc_token", token)
        }
        do {
            val response = healthConnectClient.getChanges(token!!)

            // Handle new/updated records
            val upsertedRecords = response.changes.filterIsInstance<UpsertionChange>().map { it.record }

            val newRecords = upsertedRecords.flatMap {
                it.toRecord()
            }
            repository.upsert(newRecords)
            println("Upserted ${newRecords.size} records")

            // Handle deleted records
            val deletedIds = response.changes.filterIsInstance<DeletionChange>().map { it.recordId }
            repository.deleteByIds(deletedIds)

            // Update token for the next iteration/sync
            token = response.nextChangesToken
            ds.setString("hc_token", token)

            println("Deleted ${deletedIds.size} records")

        } while (response.hasMore)
    }

    companion object {
        private const val TAG = "HealthSyncWorker"

        /** First-sync page size: the full backfill walks every record type once. */
        private const val INITIAL_PAGE_SIZE = 5000

        fun enqueue(context: Context) {
            // No constraints: this worker reads Health Connect into Room and
            // never touches the network.
            val constraints = Constraints.Builder().build()

            val syncRequest = PeriodicWorkRequestBuilder<HealthSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            val syncRequestNow = OneTimeWorkRequestBuilder<HealthSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "HealthSyncWork",
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
            WorkManager.getInstance(context).enqueueUniqueWork(
                "HealthSyncWork_now",
                ExistingWorkPolicy.KEEP,
                syncRequestNow,
            )
        }
    }
}

fun androidx.health.connect.client.records.Record.toRecord(): List<Record> =
    when (this) {
        is StepsRecord,
        is WheelchairPushesRecord,
        is DistanceRecord,
        is TotalCaloriesBurnedRecord,
        is ActiveCaloriesBurnedRecord,
        is BasalMetabolicRateRecord,
        is FloorsClimbedRecord,
        is ElevationGainedRecord,
            -> toActivityRecord()
        is HeartRateRecord,
        is RestingHeartRateRecord,
        is HeartRateVariabilityRmssdRecord,
        is RespiratoryRateRecord,
        is OxygenSaturationRecord,
        is BloodPressureRecord,
        is BloodGlucoseRecord,
        is Vo2MaxRecord,
        is SkinTemperatureRecord,
            -> toVitalsRecord()
        is WeightRecord,
        is HeightRecord,
        is BodyFatRecord,
        is LeanBodyMassRecord,
        is BoneMassRecord,
        is BodyWaterMassRecord,
            -> toBodyRecord()
        is SleepSessionRecord -> toSleepRecord()
        is MindfulnessSessionRecord -> toMindfulnessRecord()
        is ExerciseSessionRecord -> toExerciseRecord()
        is HydrationRecord -> toHydrationRecord()
        is NutritionRecord -> toNutritionRecord()
        else -> throw IllegalArgumentException("Unsupported record type")
    }

private fun androidx.health.connect.client.records.Record.toActivityRecord(): List<Record> =
    when (this) {
        is StepsRecord -> simpleRecord(
            RecordType.Steps, this.startTime, this.endTime, this.count.toDouble(), "Steps"
        )
        is WheelchairPushesRecord -> simpleRecord(
            RecordType.Wheelchair,
            this.startTime,
            this.endTime,
            this.count.toDouble(),
            "Wheelchair Pushes",
        )
        is DistanceRecord -> simpleRecord(
            RecordType.Distance,
            this.startTime,
            this.endTime,
            this.distance.inKilometers,
            "Distance",
        )
        is TotalCaloriesBurnedRecord -> simpleRecord(
            RecordType.CaloriesTotal,
            this.startTime,
            this.endTime,
            this.energy.inKilocalories,
            "Total Calories",
        )
        is ActiveCaloriesBurnedRecord -> simpleRecord(
            RecordType.CaloriesActive,
            this.startTime,
            this.endTime,
            this.energy.inKilocalories,
            "Active Calories",
        )
        is BasalMetabolicRateRecord -> simpleRecord(
            RecordType.CaloriesBasal,
            this.time,
            this.time,
            this.basalMetabolicRate.inKilocaloriesPerDay,
            "Basal Metabolic Rate",
        )
        is FloorsClimbedRecord -> simpleRecord(
            RecordType.Floors, this.startTime, this.endTime, this.floors, "Floors Climbed"
        )
        is ElevationGainedRecord -> simpleRecord(
            RecordType.Elevation,
            this.startTime,
            this.endTime,
            this.elevation.inMeters,
            "Elevation Gained",
        )
        else -> throw IllegalArgumentException("Unsupported activity record type")
    }

private fun androidx.health.connect.client.records.Record.toVitalsRecord(): List<Record> =
    when (this) {
        is HeartRateRecord -> toHeartRateRecords()
        is RestingHeartRateRecord -> simpleRecord(
            RecordType.RestingHeartRate,
            this.time,
            this.time,
            this.beatsPerMinute.toDouble(),
            "Resting Heart Rate",
        )
        is HeartRateVariabilityRmssdRecord -> simpleRecord(
            RecordType.HeartRateVariabilityRmssd,
            this.time,
            this.time,
            this.heartRateVariabilityMillis,
            "HRV",
        )
        is RespiratoryRateRecord -> simpleRecord(
            RecordType.RespiratoryRate, this.time, this.time, this.rate, "Respiratory Rate"
        )
        is OxygenSaturationRecord -> simpleRecord(
            RecordType.OxygenSaturation,
            this.time,
            this.time,
            this.percentage.value,
            "Oxygen Saturation",
        )
        is BloodPressureRecord -> listOf(
            Record(
                this.metadata.id,
                0,
                RecordType.BloodPressure,
                this.time,
                this.time,
                this.systolic.inMillimetersOfMercury,
                this.diastolic.inMillimetersOfMercury,
                metadata = "Blood Pressure",
            )
        )
        is BloodGlucoseRecord -> simpleRecord(
            RecordType.BloodGlucose,
            this.time,
            this.time,
            this.level.inMilligramsPerDeciliter,
            "Blood Glucose",
        )
        is Vo2MaxRecord -> simpleRecord(
            RecordType.Vo2Max,
            this.time,
            this.time,
            this.vo2MillilitersPerMinuteKilogram,
            "VO2 Max",
        )
        is SkinTemperatureRecord -> toSkinTemperatureRecords()
        else -> throw IllegalArgumentException("Unsupported vitals record type")
    }

private fun HeartRateRecord.toHeartRateRecords(): List<Record> =
    this.samples.mapIndexed { idx, sample ->
        Record(
            this.metadata.id,
            idx,
            RecordType.HeartRate,
            sample.time,
            sample.time,
            sample.beatsPerMinute.toDouble(),
            metadata = "Heart Rate",
        )
    }

private fun SkinTemperatureRecord.toSkinTemperatureRecords(): List<Record> =
    this.deltas.mapIndexed { idx, delta ->
        Record(
            this.metadata.id,
            idx,
            RecordType.SkinTemperature,
            delta.time,
            delta.time,
            delta.delta.inCelsius,
            metadata = "Skin Temperature",
        )
    }

private fun androidx.health.connect.client.records.Record.toBodyRecord(): List<Record> =
    when (this) {
        is WeightRecord -> simpleRecord(
            RecordType.Weight, this.time, this.time, this.weight.inKilograms, "Weight"
        )
        is HeightRecord -> simpleRecord(
            RecordType.Height, this.time, this.time, this.height.inMeters, "Height"
        )
        is BodyFatRecord -> simpleRecord(
            RecordType.BodyFat, this.time, this.time, this.percentage.value, "Body Fat"
        )
        is LeanBodyMassRecord -> simpleRecord(
            RecordType.LeanBodyMass,
            this.time,
            this.time,
            this.mass.inKilograms,
            "Lean Body Mass",
        )
        is BoneMassRecord -> simpleRecord(
            RecordType.BoneMass, this.time, this.time, this.mass.inKilograms, "Bone Mass"
        )
        is BodyWaterMassRecord -> simpleRecord(
            RecordType.BodyWaterMass,
            this.time,
            this.time,
            this.mass.inKilograms,
            "Body Water Mass",
        )
        else -> throw IllegalArgumentException("Unsupported body record type")
    }

/** One single-sample record row: index 0, no secondary value, no payload. */
private fun androidx.health.connect.client.records.Record.simpleRecord(
    type: RecordType,
    start: java.time.Instant,
    end: java.time.Instant,
    value: Double,
    metadata: String,
): List<Record> = listOf(Record(this.metadata.id, 0, type, start, end, value, metadata = metadata))

private fun SleepSessionRecord.toSleepRecord(): List<Record> {
    val stages = this.stages.map { stage ->
        SleepStage(stage.startTime.toEpochMilli(), stage.endTime.toEpochMilli(), stage.stage)
    }
    val durations = this.stages.groupBy { it.stage }
        .mapValues { (_, v) -> v.sumOf { Duration.between(it.startTime, it.endTime).toMillis() } }

    return listOf(
        Record(
            this.metadata.id,
            0,
            RecordType.Sleep,
            this.startTime,
            this.endTime,
            Duration.between(this.startTime, this.endTime).toMillis().toDouble() /
                MILLIS_PER_SECOND / SECONDS_PER_MINUTE / MINUTES_PER_HOUR,
            sleepData = com.vayunmathur.health.data.SleepData(
                awakeDurationMillis = (durations[SleepSessionRecord.STAGE_TYPE_AWAKE] ?: 0) +
                    (durations[SleepSessionRecord.STAGE_TYPE_OUT_OF_BED] ?: 0),
                remDurationMillis = durations[SleepSessionRecord.STAGE_TYPE_REM] ?: 0,
                lightDurationMillis = durations[SleepSessionRecord.STAGE_TYPE_LIGHT] ?: 0,
                deepDurationMillis = durations[SleepSessionRecord.STAGE_TYPE_DEEP] ?: 0,
                unknownDurationMillis = durations[SleepSessionRecord.STAGE_TYPE_UNKNOWN] ?: 0,
                stagesJson = Json.encodeToString(stages)
            ),
            metadata = "Sleep Session"
        )
    )
}

private fun MindfulnessSessionRecord.toMindfulnessRecord(): List<Record> = listOf(
    Record(
        this.metadata.id,
        0,
        RecordType.Mindfulness,
        this.startTime,
        this.endTime,
        Duration.between(this.startTime, this.endTime).toMillis().toDouble() /
            MILLIS_PER_SECOND / SECONDS_PER_MINUTE,
        metadata = "Mindfulness",
    )
)

private fun ExerciseSessionRecord.toExerciseRecord(): List<Record> {
    val durationMinutes = Duration.between(this.startTime, this.endTime).toMinutes().toDouble()
    val segments = this.segments.map { seg ->
        com.vayunmathur.health.data.ExerciseSegmentData(
            seg.startTime.toEpochMilli(), seg.endTime.toEpochMilli(),
            seg.segmentType, seg.repetitions
        )
    }
    val laps = this.laps.map { lap ->
        com.vayunmathur.health.data.ExerciseLapData(
            lap.startTime.toEpochMilli(), lap.endTime.toEpochMilli(),
            lap.length?.inMeters
        )
    }
    val hasRoute =
        this.exerciseRouteResult is androidx.health.connect.client.records.ExerciseRouteResult.Data
    return listOf(
        Record(
            this.metadata.id, 0, RecordType.Exercise,
            this.startTime, this.endTime,
            durationMinutes,
            secondaryValue = this.exerciseType.toDouble(),
            exerciseData = com.vayunmathur.health.data.ExerciseData(
                exerciseType = this.exerciseType,
                title = this.title,
                notes = this.notes,
                segmentsJson = if (segments.isNotEmpty()) Json.encodeToString(segments) else null,
                lapsJson = if (laps.isNotEmpty()) Json.encodeToString(laps) else null,
                hasRoute = hasRoute,
            ),
            metadata = this.title ?: exerciseTypeName(this.exerciseType)
        )
    )
}

private fun HydrationRecord.toHydrationRecord(): List<Record> = listOf(
    Record(
        this.metadata.id,
        0,
        RecordType.Hydration,
        this.startTime,
        this.endTime,
        this.volume.inLiters,
        metadata = "Hydration",
    )
)

private fun NutritionRecord.toNutritionRecord(): List<Record> {
    val kcal = this.energy?.inKilocalories.orZero()
    return listOf(
        Record(
            this.metadata.id, 0, RecordType.Nutrition, this.startTime, this.endTime,
            kcal,
            nutritionData = com.vayunmathur.health.data.NutritionData(
                protein = this.protein?.inGrams.orZero(),
                carbohydrates = this.totalCarbohydrate?.inGrams.orZero(),
                fat = this.totalFat?.inGrams.orZero(),
                fiber = this.dietaryFiber?.inGrams.orZero(),
                sugar = this.sugar?.inGrams.orZero(),
                sodium = this.sodium?.inMilligrams.orZero(),
                biotin = this.biotin?.inMicrograms.orZero(),
                caffeine = this.caffeine?.inMilligrams.orZero(),
                calcium = this.calcium?.inMilligrams.orZero(),
                chloride = this.chloride?.inMilligrams.orZero(),
                cholesterol = this.cholesterol?.inMilligrams.orZero(),
                chromium = this.chromium?.inMicrograms.orZero(),
                copper = this.copper?.inMilligrams.orZero(),
                folate = this.folate?.inMicrograms.orZero(),
                folicAcid = this.folicAcid?.inMicrograms.orZero(),
                iodine = this.iodine?.inMicrograms.orZero(),
                iron = this.iron?.inMilligrams.orZero(),
                magnesium = this.magnesium?.inMilligrams.orZero(),
                manganese = this.manganese?.inMilligrams.orZero(),
                molybdenum = this.molybdenum?.inMicrograms.orZero(),
                monounsaturatedFat = this.monounsaturatedFat?.inGrams.orZero(),
                niacin = this.niacin?.inMilligrams.orZero(),
                pantothenicAcid = this.pantothenicAcid?.inMilligrams.orZero(),
                phosphorus = this.phosphorus?.inMilligrams.orZero(),
                polyunsaturatedFat = this.polyunsaturatedFat?.inGrams.orZero(),
                potassium = this.potassium?.inMilligrams.orZero(),
                riboflavin = this.riboflavin?.inMilligrams.orZero(),
                saturatedFat = this.saturatedFat?.inGrams.orZero(),
                selenium = this.selenium?.inMicrograms.orZero(),
                thiamin = this.thiamin?.inMilligrams.orZero(),
                transFat = this.transFat?.inGrams.orZero(),
                unsaturatedFat = this.unsaturatedFat?.inGrams.orZero(),
                vitaminA = this.vitaminA?.inMicrograms.orZero(),
                vitaminB12 = this.vitaminB12?.inMicrograms.orZero(),
                vitaminB6 = this.vitaminB6?.inMilligrams.orZero(),
                vitaminC = this.vitaminC?.inMilligrams.orZero(),
                vitaminD = this.vitaminD?.inMicrograms.orZero(),
                vitaminE = this.vitaminE?.inMilligrams.orZero(),
                vitaminK = this.vitaminK?.inMicrograms.orZero(),
                zinc = this.zinc?.inMilligrams.orZero(),
                calories = kcal
            ),
            metadata = this.name
        )
    )
}

/** An unreported nutrient reads as 0.0, matching what `/api/food/data/:id` returns. */
private fun Double?.orZero(): Double = this ?: 0.0

/** Millis → hours and millis → minutes factors for session durations. */
private const val MILLIS_PER_SECOND = 1000.0
private const val SECONDS_PER_MINUTE = 60.0
private const val MINUTES_PER_HOUR = 60.0

private val EXERCISE_TYPE_NAMES: Map<Int, String> = mapOf(
    ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON to "Badminton",
    ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL to "Baseball",
    ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL to "Basketball",
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "Cycling",
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to "Stationary Bike",
    ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP to "Boot Camp",
    ExerciseSessionRecord.EXERCISE_TYPE_BOXING to "Boxing",
    ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS to "Calisthenics",
    ExerciseSessionRecord.EXERCISE_TYPE_CRICKET to "Cricket",
    ExerciseSessionRecord.EXERCISE_TYPE_DANCING to "Dancing",
    ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to "Elliptical",
    ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS to "Exercise Class",
    ExerciseSessionRecord.EXERCISE_TYPE_FENCING to "Fencing",
    ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN to "Football",
    ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN to "Australian Football",
    ExerciseSessionRecord.EXERCISE_TYPE_GOLF to "Golf",
    ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING to "Guided Breathing",
    ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS to "Gymnastics",
    ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL to "Handball",
    ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "HIIT",
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING to "Hiking",
    ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY to "Ice Hockey",
    ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING to "Ice Skating",
    ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS to "Martial Arts",
    ExerciseSessionRecord.EXERCISE_TYPE_PADDLING to "Paddling",
    ExerciseSessionRecord.EXERCISE_TYPE_PARAGLIDING to "Paragliding",
    ExerciseSessionRecord.EXERCISE_TYPE_PILATES to "Pilates",
    ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL to "Racquetball",
    ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING to "Rock Climbing",
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING to "Rowing",
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE to "Rowing Machine",
    ExerciseSessionRecord.EXERCISE_TYPE_RUGBY to "Rugby",
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "Running",
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to "Treadmill",
    ExerciseSessionRecord.EXERCISE_TYPE_SAILING to "Sailing",
    ExerciseSessionRecord.EXERCISE_TYPE_SCUBA_DIVING to "Scuba Diving",
    ExerciseSessionRecord.EXERCISE_TYPE_SKATING to "Skating",
    ExerciseSessionRecord.EXERCISE_TYPE_SKIING to "Skiing",
    ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING to "Snowboarding",
    ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING to "Snowshoeing",
    ExerciseSessionRecord.EXERCISE_TYPE_SOCCER to "Soccer",
    ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL to "Softball",
    ExerciseSessionRecord.EXERCISE_TYPE_SQUASH to "Squash",
    ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING to "Stair Climbing",
    ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE to "Stair Machine",
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to "Strength Training",
    ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING to "Stretching",
    ExerciseSessionRecord.EXERCISE_TYPE_SURFING to "Surfing",
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "Open Water Swimming",
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to "Pool Swimming",
    ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS to "Table Tennis",
    ExerciseSessionRecord.EXERCISE_TYPE_TENNIS to "Tennis",
    ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL to "Volleyball",
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING to "Walking",
    ExerciseSessionRecord.EXERCISE_TYPE_WATER_POLO to "Water Polo",
    ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to "Weightlifting",
    ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR to "Wheelchair",
    ExerciseSessionRecord.EXERCISE_TYPE_YOGA to "Yoga",
)

fun exerciseTypeName(type: Int): String = EXERCISE_TYPE_NAMES[type] ?: "Workout"

private val EXERCISE_SEGMENT_TYPE_NAMES: Map<Int, String> = mapOf(
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL to "Arm Curl",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_BACK_EXTENSION to "Back Extension",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_BALL_SLAM to "Ball Slam",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_BARBELL_SHOULDER_PRESS to "Barbell Shoulder Press",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS to "Bench Press",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_SIT_UP to "Bench Sit-Up",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING to "Biking",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY to "Biking (Stationary)",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_BURPEE to "Burpee",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_CRUNCH to "Crunch",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT to "Deadlift",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_DOUBLE_ARM_TRICEPS_EXTENSION to "Double Arm Triceps Extension",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_CURL_LEFT_ARM to "Dumbbell Curl (Left)",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_CURL_RIGHT_ARM to "Dumbbell Curl (Right)",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_FRONT_RAISE to "Dumbbell Front Raise",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_LATERAL_RAISE to "Dumbbell Lateral Raise",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_ROW to "Dumbbell Row",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_TRICEPS_EXTENSION_LEFT_ARM to "Dumbbell Triceps Extension (Left)",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_TRICEPS_EXTENSION_RIGHT_ARM to "Dumbbell Triceps Extension (Right)",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_TRICEPS_EXTENSION_TWO_ARM to "Dumbbell Triceps Extension (Two Arm)",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_ELLIPTICAL to "Elliptical",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_FORWARD_TWIST to "Forward Twist",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_FRONT_RAISE to "Front Raise",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "High Intensity Interval Training",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIP_THRUST to "Hip Thrust",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_HULA_HOOP to "Hula Hoop",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_JUMPING_JACK to "Jumping Jack",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_JUMP_ROPE to "Jump Rope",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_KETTLEBELL_SWING to "Kettlebell Swing",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_LATERAL_RAISE to "Lateral Raise",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_LAT_PULL_DOWN to "Lat Pull-Down",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_CURL to "Leg Curl",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_EXTENSION to "Leg Extension",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_PRESS to "Leg Press",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_RAISE to "Leg Raise",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE to "Lunge",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_MOUNTAIN_CLIMBER to "Mountain Climber",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT to "Other",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE to "Pause",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_PILATES to "Pilates",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_PLANK to "Plank",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP to "Pull-Up",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_PUNCH to "Punch",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST to "Rest",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_ROWING_MACHINE to "Rowing Machine",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING to "Running",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL to "Running (Treadmill)",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS to "Shoulder Press",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SINGLE_ARM_TRICEPS_EXTENSION to "Single Arm Triceps Extension",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SIT_UP to "Sit-Up",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT to "Squat",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_STAIR_CLIMBING to "Stair Climbing",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_STAIR_CLIMBING_MACHINE to "Stair Climbing Machine",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING to "Stretching",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_BACKSTROKE to "Swimming (Backstroke)",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_BREASTSTROKE to "Swimming (Breaststroke)",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_BUTTERFLY to "Swimming (Butterfly)",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_FREESTYLE to "Swimming (Freestyle)",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_MIXED to "Swimming (Mixed)",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_OPEN_WATER to "Swimming (Open Water)",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_OTHER to "Swimming (Other)",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_POOL to "Swimming (Pool)",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_UPPER_TWIST to "Upper Twist",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_WALKING to "Walking",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING to "Weightlifting",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_WHEELCHAIR to "Wheelchair",
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_YOGA to "Yoga",
)

fun exerciseSegmentTypeName(type: Int): String = EXERCISE_SEGMENT_TYPE_NAMES[type] ?: "Activity"
