package com.vayunmathur.health.data

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
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

val CLASSES = setOf(
    // Activity & Energy
    StepsRecord::class,
    WheelchairPushesRecord::class,
    DistanceRecord::class,
    TotalCaloriesBurnedRecord::class,
    ActiveCaloriesBurnedRecord::class,
    BasalMetabolicRateRecord::class,
    FloorsClimbedRecord::class,
    ElevationGainedRecord::class,

    // Vitals & Clinical
    HeartRateRecord::class,
    RestingHeartRateRecord::class,
    HeartRateVariabilityRmssdRecord::class,
    RespiratoryRateRecord::class,
    OxygenSaturationRecord::class,
    BloodPressureRecord::class,
    BloodGlucoseRecord::class,
    Vo2MaxRecord::class,
    SkinTemperatureRecord::class,

    // Body Composition
    WeightRecord::class,
    HeightRecord::class,
    BodyFatRecord::class,
    LeanBodyMassRecord::class,
    BoneMassRecord::class,
    BodyWaterMassRecord::class,

    // Exercise
    ExerciseSessionRecord::class,

    // Lifestyle & Nutrition
    MindfulnessSessionRecord::class,
    HydrationRecord::class,
    NutritionRecord::class,
    SleepSessionRecord::class,
)

val PERMISSIONS = CLASSES.map { HealthPermission.getReadPermission(it) }.toSet() + 
    setOf(
        HealthPermission.getWritePermission(NutritionRecord::class),
        HealthPermission.getWritePermission(HydrationRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getWritePermission(HeightRecord::class),
        HealthPermission.getWritePermission(BodyFatRecord::class),
        HealthPermission.getWritePermission(LeanBodyMassRecord::class),
        HealthPermission.getWritePermission(BoneMassRecord::class),
        HealthPermission.getWritePermission(BodyWaterMassRecord::class),
        "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    )
