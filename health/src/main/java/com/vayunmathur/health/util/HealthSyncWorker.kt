package com.vayunmathur.health.util
import android.content.Context
import android.util.Log
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
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vayunmathur.health.CLASSES
import com.vayunmathur.health.data.HealthDatabase
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.room.buildDatabase
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

    override suspend fun doWork(): Result {
        return try {
            sync()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Health sync failed", e)
            Result.retry()
        }
    }

    private suspend fun sync() {
        val healthConnectClient = HealthConnectClient.getOrCreate(applicationContext)
        val db = applicationContext.buildDatabase<HealthDatabase>()
        val ds = DataStoreUtils.getInstance(applicationContext)
        var token = ds.getString("hc_token")
        if (token == null) {
            // 2. If no token exists, initialize one for the data types you care about
            CLASSES.forEach { clazz ->
                var pageToken: String? = null
                do {
                    val records = healthConnectClient.readRecords(ReadRecordsRequest(clazz, TimeRangeFilter.after(Instant.EPOCH), pageSize = 5000, pageToken = pageToken))
                    db.healthDao().upsert(records.records.flatMap { it.toRecord() })
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
            db.healthDao().upsert(newRecords)
            println("Upserted ${newRecords.size} records")

            // Handle deleted records
            val deletedIds = response.changes.filterIsInstance<DeletionChange>().map { it.recordId }
            db.healthDao().deleteByIds(deletedIds)

            // Update token for the next iteration/sync
            token = response.nextChangesToken
            ds.setString("hc_token", token)

            println("Deleted ${deletedIds.size} records")

        } while (response.hasMore)
    }

    companion object {
        private const val TAG = "HealthSyncWorker"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

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
            WorkManager.getInstance(context).enqueueUniqueWork("HealthSyncWork_now", ExistingWorkPolicy.KEEP, syncRequestNow)
        }
    }
}

fun androidx.health.connect.client.records.Record.toRecord(): List<Record> =
    when (this) {
        // --- Activity ---
        is StepsRecord -> listOf(Record(this.metadata.id, 0, RecordType.Steps, this.startTime, this.endTime, this.count.toDouble(), metadata = "Steps"))
        is WheelchairPushesRecord -> listOf(Record(this.metadata.id, 0, RecordType.Wheelchair, this.startTime, this.endTime, this.count.toDouble(), metadata = "Wheelchair Pushes"))
        is DistanceRecord -> listOf(Record(this.metadata.id, 0, RecordType.Distance, this.startTime, this.endTime, this.distance.inKilometers, metadata = "Distance"))
        is TotalCaloriesBurnedRecord -> listOf(Record(this.metadata.id, 0, RecordType.CaloriesTotal, this.startTime, this.endTime, this.energy.inKilocalories, metadata = "Total Calories"))
        is ActiveCaloriesBurnedRecord -> listOf(Record(this.metadata.id, 0, RecordType.CaloriesActive, this.startTime, this.endTime, this.energy.inKilocalories, metadata = "Active Calories"))
        is BasalMetabolicRateRecord -> listOf(Record(this.metadata.id, 0, RecordType.CaloriesBasal, this.time, this.time, this.basalMetabolicRate.inKilocaloriesPerDay, metadata = "Basal Metabolic Rate"))
        is FloorsClimbedRecord -> listOf(Record(this.metadata.id, 0, RecordType.Floors, this.startTime, this.endTime, this.floors, metadata = "Floors Climbed"))
        is ElevationGainedRecord -> listOf(Record(this.metadata.id, 0, RecordType.Elevation, this.startTime, this.endTime, this.elevation.inMeters, metadata = "Elevation Gained"))

        // --- Vitals ---
        is HeartRateRecord -> this.samples.mapIndexed { idx, sample -> Record(this.metadata.id, idx, RecordType.HeartRate, sample.time, sample.time, sample.beatsPerMinute.toDouble(), metadata = "Heart Rate") }
        is RestingHeartRateRecord -> listOf(Record(this.metadata.id, 0, RecordType.RestingHeartRate, this.time, this.time, this.beatsPerMinute.toDouble(), metadata = "Resting Heart Rate"))
        is HeartRateVariabilityRmssdRecord -> listOf(Record(this.metadata.id, 0, RecordType.HeartRateVariabilityRmssd, this.time, this.time, this.heartRateVariabilityMillis, metadata = "HRV"))
        is RespiratoryRateRecord -> listOf(Record(this.metadata.id, 0, RecordType.RespiratoryRate, this.time, this.time, this.rate, metadata = "Respiratory Rate"))
        is OxygenSaturationRecord -> listOf(Record(this.metadata.id, 0, RecordType.OxygenSaturation, this.time, this.time, this.percentage.value, metadata = "Oxygen Saturation"))
        is BloodPressureRecord -> listOf(Record(this.metadata.id, 0, RecordType.BloodPressure, this.time, this.time, this.systolic.inMillimetersOfMercury, this.diastolic.inMillimetersOfMercury, metadata = "Blood Pressure"))
        is BloodGlucoseRecord -> listOf(Record(this.metadata.id, 0, RecordType.BloodGlucose, this.time, this.time, this.level.inMilligramsPerDeciliter, metadata = "Blood Glucose"))
        is Vo2MaxRecord -> listOf(Record(this.metadata.id, 0, RecordType.Vo2Max, this.time, this.time, this.vo2MillilitersPerMinuteKilogram, metadata = "VO2 Max"))
        is SkinTemperatureRecord -> this.deltas.mapIndexed { idx, delta -> Record(this.metadata.id, idx, RecordType.SkinTemperature, delta.time, delta.time, delta.delta.inCelsius, metadata = "Skin Temperature") }

        // --- Body Composthision ---
        is WeightRecord -> listOf(Record(this.metadata.id, 0, RecordType.Weight, this.time, this.time, this.weight.inKilograms, metadata = "Weight"))
        is HeightRecord -> listOf(Record(this.metadata.id, 0, RecordType.Height, this.time, this.time, this.height.inMeters, metadata = "Height"))
        is BodyFatRecord -> listOf(Record(this.metadata.id, 0, RecordType.BodyFat, this.time, this.time, this.percentage.value, metadata = "Body Fat"))
        is LeanBodyMassRecord -> listOf(Record(this.metadata.id, 0, RecordType.LeanBodyMass, this.time, this.time, this.mass.inKilograms, metadata = "Lean Body Mass"))
        is BoneMassRecord -> listOf(Record(this.metadata.id, 0, RecordType.BoneMass, this.time, this.time, this.mass.inKilograms, metadata = "Bone Mass"))
        is BodyWaterMassRecord -> listOf(Record(this.metadata.id, 0, RecordType.BodyWaterMass, this.time, this.time, this.mass.inKilograms, metadata = "Body Water Mass"))

        // --- Lifestyle ---
        is SleepSessionRecord -> {
            val stages = this.stages.map { stage ->
                SleepStage(stage.startTime.toEpochMilli(), stage.endTime.toEpochMilli(), stage.stage)
            }
            val durations = this.stages.groupBy { it.stage }
                .mapValues { (_, v) -> v.sumOf { Duration.between(it.startTime, it.endTime).toMillis() } }

            listOf(Record(
                this.metadata.id, 0, RecordType.Sleep, this.startTime, this.endTime,
                Duration.between(this.startTime, this.endTime).toMillis().toDouble() / 1000.0 / 60.0 / 60.0,
                sleepData = com.vayunmathur.health.data.SleepData(
                    awakeDurationMillis = (durations[SleepSessionRecord.STAGE_TYPE_AWAKE] ?: 0) + (durations[SleepSessionRecord.STAGE_TYPE_OUT_OF_BED] ?: 0),
                    remDurationMillis = durations[SleepSessionRecord.STAGE_TYPE_REM] ?: 0,
                    lightDurationMillis = durations[SleepSessionRecord.STAGE_TYPE_LIGHT] ?: 0,
                    deepDurationMillis = durations[SleepSessionRecord.STAGE_TYPE_DEEP] ?: 0,
                    unknownDurationMillis = durations[SleepSessionRecord.STAGE_TYPE_UNKNOWN] ?: 0,
                    stagesJson = Json.encodeToString(stages)
                ),
                metadata = "Sleep Session"
            ))
        }
        is MindfulnessSessionRecord -> listOf(Record(this.metadata.id, 0, RecordType.Mindfulness, this.startTime, this.endTime, Duration.between(this.startTime, this.endTime).toMillis().toDouble() / 1000.0 / 60.0, metadata = "Mindfulness"))
        is ExerciseSessionRecord -> {
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
            val hasRoute = this.exerciseRouteResult is androidx.health.connect.client.records.ExerciseRouteResult.Data
            listOf(Record(
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
            ))
        }
        is HydrationRecord -> listOf(Record(this.metadata.id, 0, RecordType.Hydration, this.startTime, this.endTime, this.volume.inLiters, metadata = "Hydration"))
        is NutritionRecord -> {
            val kcal = this.energy?.inKilocalories ?: 0.0
            listOf(Record(
                this.metadata.id, 0, RecordType.Nutrition, this.startTime, this.endTime,
                kcal,
                nutritionData = com.vayunmathur.health.data.NutritionData(
                    protein = this.protein?.inGrams ?: 0.0,
                    carbohydrates = this.totalCarbohydrate?.inGrams ?: 0.0,
                    fat = this.totalFat?.inGrams ?: 0.0,
                    fiber = this.dietaryFiber?.inGrams ?: 0.0,
                    sugar = this.sugar?.inGrams ?: 0.0,
                    sodium = this.sodium?.inMilligrams ?: 0.0,
                    biotin = this.biotin?.inMicrograms ?: 0.0,
                    caffeine = this.caffeine?.inMilligrams ?: 0.0,
                    calcium = this.calcium?.inMilligrams ?: 0.0,
                    chloride = this.chloride?.inMilligrams ?: 0.0,
                    cholesterol = this.cholesterol?.inMilligrams ?: 0.0,
                    chromium = this.chromium?.inMicrograms ?: 0.0,
                    copper = this.copper?.inMilligrams ?: 0.0,
                    folate = this.folate?.inMicrograms ?: 0.0,
                    folicAcid = this.folicAcid?.inMicrograms ?: 0.0,
                    iodine = this.iodine?.inMicrograms ?: 0.0,
                    iron = this.iron?.inMilligrams ?: 0.0,
                    magnesium = this.magnesium?.inMilligrams ?: 0.0,
                    manganese = this.manganese?.inMilligrams ?: 0.0,
                    molybdenum = this.molybdenum?.inMicrograms ?: 0.0,
                    monounsaturatedFat = this.monounsaturatedFat?.inGrams ?: 0.0,
                    niacin = this.niacin?.inMilligrams ?: 0.0,
                    pantothenicAcid = this.pantothenicAcid?.inMilligrams ?: 0.0,
                    phosphorus = this.phosphorus?.inMilligrams ?: 0.0,
                    polyunsaturatedFat = this.polyunsaturatedFat?.inGrams ?: 0.0,
                    potassium = this.potassium?.inMilligrams ?: 0.0,
                    riboflavin = this.riboflavin?.inMilligrams ?: 0.0,
                    saturatedFat = this.saturatedFat?.inGrams ?: 0.0,
                    selenium = this.selenium?.inMicrograms ?: 0.0,
                    thiamin = this.thiamin?.inMilligrams ?: 0.0,
                    transFat = this.transFat?.inGrams ?: 0.0,
                    unsaturatedFat = this.unsaturatedFat?.inGrams ?: 0.0,
                    vitaminA = this.vitaminA?.inMicrograms ?: 0.0,
                    vitaminB12 = this.vitaminB12?.inMicrograms ?: 0.0,
                    vitaminB6 = this.vitaminB6?.inMilligrams ?: 0.0,
                    vitaminC = this.vitaminC?.inMilligrams ?: 0.0,
                    vitaminD = this.vitaminD?.inMicrograms ?: 0.0,
                    vitaminE = this.vitaminE?.inMilligrams ?: 0.0,
                    vitaminK = this.vitaminK?.inMicrograms ?: 0.0,
                    zinc = this.zinc?.inMilligrams ?: 0.0,
                    calories = kcal
                ),
                metadata = this.name
            ))
        }

        else -> throw IllegalArgumentException("Unsupported record type")
    }

fun exerciseTypeName(type: Int): String = when (type) {
    ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> "Badminton"
    ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL -> "Baseball"
    ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> "Basketball"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Cycling"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Stationary Bike"
    ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP -> "Boot Camp"
    ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> "Boxing"
    ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "Calisthenics"
    ExerciseSessionRecord.EXERCISE_TYPE_CRICKET -> "Cricket"
    ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "Dancing"
    ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "Elliptical"
    ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS -> "Exercise Class"
    ExerciseSessionRecord.EXERCISE_TYPE_FENCING -> "Fencing"
    ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN -> "Football"
    ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN -> "Australian Football"
    ExerciseSessionRecord.EXERCISE_TYPE_GOLF -> "Golf"
    ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING -> "Guided Breathing"
    ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS -> "Gymnastics"
    ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL -> "Handball"
    ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hiking"
    ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY -> "Ice Hockey"
    ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING -> "Ice Skating"
    ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS -> "Martial Arts"
    ExerciseSessionRecord.EXERCISE_TYPE_PADDLING -> "Paddling"
    ExerciseSessionRecord.EXERCISE_TYPE_PARAGLIDING -> "Paragliding"
    ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "Pilates"
    ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL -> "Racquetball"
    ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> "Rock Climbing"
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING -> "Rowing"
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "Rowing Machine"
    ExerciseSessionRecord.EXERCISE_TYPE_RUGBY -> "Rugby"
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Running"
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Treadmill"
    ExerciseSessionRecord.EXERCISE_TYPE_SAILING -> "Sailing"
    ExerciseSessionRecord.EXERCISE_TYPE_SCUBA_DIVING -> "Scuba Diving"
    ExerciseSessionRecord.EXERCISE_TYPE_SKATING -> "Skating"
    ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> "Skiing"
    ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING -> "Snowboarding"
    ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING -> "Snowshoeing"
    ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "Soccer"
    ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL -> "Softball"
    ExerciseSessionRecord.EXERCISE_TYPE_SQUASH -> "Squash"
    ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING -> "Stair Climbing"
    ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> "Stair Machine"
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Strength Training"
    ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "Stretching"
    ExerciseSessionRecord.EXERCISE_TYPE_SURFING -> "Surfing"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Open Water Swimming"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "Pool Swimming"
    ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS -> "Table Tennis"
    ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "Tennis"
    ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL -> "Volleyball"
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walking"
    ExerciseSessionRecord.EXERCISE_TYPE_WATER_POLO -> "Water Polo"
    ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Weightlifting"
    ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR -> "Wheelchair"
    ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
    else -> "Workout"
}

fun exerciseSegmentTypeName(type: Int): String = when (type) {
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL -> "Arm Curl"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_BACK_EXTENSION -> "Back Extension"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_BALL_SLAM -> "Ball Slam"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_BARBELL_SHOULDER_PRESS -> "Barbell Shoulder Press"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS -> "Bench Press"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_SIT_UP -> "Bench Sit-Up"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING -> "Biking"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY -> "Biking (Stationary)"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_BURPEE -> "Burpee"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_CRUNCH -> "Crunch"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT -> "Deadlift"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_DOUBLE_ARM_TRICEPS_EXTENSION -> "Double Arm Triceps Extension"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_CURL_LEFT_ARM -> "Dumbbell Curl (Left)"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_CURL_RIGHT_ARM -> "Dumbbell Curl (Right)"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_FRONT_RAISE -> "Dumbbell Front Raise"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_LATERAL_RAISE -> "Dumbbell Lateral Raise"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_ROW -> "Dumbbell Row"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_TRICEPS_EXTENSION_LEFT_ARM -> "Dumbbell Triceps Extension (Left)"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_TRICEPS_EXTENSION_RIGHT_ARM -> "Dumbbell Triceps Extension (Right)"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_DUMBBELL_TRICEPS_EXTENSION_TWO_ARM -> "Dumbbell Triceps Extension (Two Arm)"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_ELLIPTICAL -> "Elliptical"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_FORWARD_TWIST -> "Forward Twist"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_FRONT_RAISE -> "Front Raise"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "High Intensity Interval Training"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIP_THRUST -> "Hip Thrust"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_HULA_HOOP -> "Hula Hoop"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_JUMPING_JACK -> "Jumping Jack"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_JUMP_ROPE -> "Jump Rope"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_KETTLEBELL_SWING -> "Kettlebell Swing"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_LATERAL_RAISE -> "Lateral Raise"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_LAT_PULL_DOWN -> "Lat Pull-Down"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_CURL -> "Leg Curl"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_EXTENSION -> "Leg Extension"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_PRESS -> "Leg Press"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_RAISE -> "Leg Raise"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE -> "Lunge"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_MOUNTAIN_CLIMBER -> "Mountain Climber"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT -> "Other"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE -> "Pause"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_PILATES -> "Pilates"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_PLANK -> "Plank"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP -> "Pull-Up"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_PUNCH -> "Punch"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST -> "Rest"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_ROWING_MACHINE -> "Rowing Machine"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING -> "Running"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL -> "Running (Treadmill)"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS -> "Shoulder Press"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SINGLE_ARM_TRICEPS_EXTENSION -> "Single Arm Triceps Extension"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SIT_UP -> "Sit-Up"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT -> "Squat"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_STAIR_CLIMBING -> "Stair Climbing"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_STAIR_CLIMBING_MACHINE -> "Stair Climbing Machine"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_STRETCHING -> "Stretching"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_BACKSTROKE -> "Swimming (Backstroke)"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_BREASTSTROKE -> "Swimming (Breaststroke)"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_BUTTERFLY -> "Swimming (Butterfly)"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_FREESTYLE -> "Swimming (Freestyle)"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_MIXED -> "Swimming (Mixed)"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_OPEN_WATER -> "Swimming (Open Water)"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_OTHER -> "Swimming (Other)"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_SWIMMING_POOL -> "Swimming (Pool)"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_UPPER_TWIST -> "Upper Twist"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_WALKING -> "Walking"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING -> "Weightlifting"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_WHEELCHAIR -> "Wheelchair"
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_YOGA -> "Yoga"
    else -> "Activity"
}