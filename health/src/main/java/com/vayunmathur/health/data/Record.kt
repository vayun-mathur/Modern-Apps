package com.vayunmathur.health.data
import androidx.room3.ColumnTypeConverter
import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Update
import androidx.room3.Delete
import androidx.room3.migration.Migration
import androidx.sqlite.execSQL
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Kinds of record type. */
enum class RecordType {
    Steps, Wheelchair, Distance, CaloriesTotal, CaloriesActive, CaloriesBasal, Floors, Elevation,
    HeartRate, RestingHeartRate, HeartRateVariabilityRmssd, RespiratoryRate, OxygenSaturation,
    BloodPressure, BloodGlucose, Vo2Max, SkinTemperature,
    Weight, Height, BodyFat, LeanBodyMass, BoneMass, BodyWaterMass,
    Sleep, Mindfulness, Hydration, Nutrition, Exercise
}

/** Immutable value describing nutrition data. */
@Serializable
data class NutritionData(
    val protein: Double = 0.0,
    val carbohydrates: Double = 0.0,
    val fat: Double = 0.0,
    val fiber: Double = 0.0,
    val sugar: Double = 0.0,
    val sodium: Double = 0.0,
    val biotin: Double = 0.0,
    val caffeine: Double = 0.0,
    val calcium: Double = 0.0,
    val chloride: Double = 0.0,
    val cholesterol: Double = 0.0,
    val chromium: Double = 0.0,
    val copper: Double = 0.0,
    val folate: Double = 0.0,
    val folicAcid: Double = 0.0,
    val iodine: Double = 0.0,
    val iron: Double = 0.0,
    val magnesium: Double = 0.0,
    val manganese: Double = 0.0,
    val molybdenum: Double = 0.0,
    val monounsaturatedFat: Double = 0.0,
    val niacin: Double = 0.0,
    val pantothenicAcid: Double = 0.0,
    val phosphorus: Double = 0.0,
    val polyunsaturatedFat: Double = 0.0,
    val potassium: Double = 0.0,
    val riboflavin: Double = 0.0,
    val saturatedFat: Double = 0.0,
    val selenium: Double = 0.0,
    val thiamin: Double = 0.0,
    val transFat: Double = 0.0,
    val unsaturatedFat: Double = 0.0,
    val vitaminA: Double = 0.0,
    val vitaminB12: Double = 0.0,
    val vitaminB6: Double = 0.0,
    val vitaminC: Double = 0.0,
    val vitaminD: Double = 0.0,
    val vitaminE: Double = 0.0,
    val vitaminK: Double = 0.0,
    val zinc: Double = 0.0,
    val calories: Double = 0.0
)

/** Immutable value describing sleep stage. */
@Serializable
data class SleepStage(
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val stage: Int
)

/** Immutable value describing sleep data. */
@Serializable
data class SleepData(
    val awakeDurationMillis: Long = 0,
    val remDurationMillis: Long = 0,
    val lightDurationMillis: Long = 0,
    val deepDurationMillis: Long = 0,
    val unknownDurationMillis: Long = 0,
    val stagesJson: String? = null
)

/** Immutable value describing exercise segment data. */
@Serializable
data class ExerciseSegmentData(
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val segmentType: Int,
    val repetitions: Int,
)

/** Immutable value describing exercise lap data. */
@Serializable
data class ExerciseLapData(
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val lengthMeters: Double?,
)

/** Immutable value describing exercise data. */
@Serializable
data class ExerciseData(
    val exerciseType: Int = 0,
    val title: String? = null,
    val notes: String? = null,
    val segmentsJson: String? = null,
    val lapsJson: String? = null,
    val hasRoute: Boolean = false,
)

/** Immutable value describing record. */
@Entity(indices = [Index(value = ["type", "startTime", "endTime"])])
data class Record(
    val id: String,
    val index : Int, // for multisample records
    val type: RecordType,
    val startTime: Instant,
    val endTime: Instant,
    val value: Double,
    val secondaryValue: Double = 0.0,
    @Embedded(prefix = "nutrition_") val nutritionData: NutritionData? = null,
    @Embedded(prefix = "sleep_") val sleepData: SleepData? = null,
    @Embedded(prefix = "exercise_") val exerciseData: ExerciseData? = null,
    val metadata: String? = null,
    @PrimaryKey val primaryKey: String = "$id-$index",
)

@Dao
// Query surface of the fitness-record tables: one method per query by Room's design.
// Splitting the interface would only move the same method count to more files.
/** Contract for health dao. */
@Suppress("TooManyFunctions")
interface HealthDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(records: List<Record>)

    @Query("DELETE FROM Record WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT * FROM Record WHERE type = :type ORDER BY startTime DESC")
    fun getRecordsFlow(type: RecordType): Flow<List<Record>>

    @Query("SELECT * FROM Record WHERE type = :type ORDER BY startTime DESC LIMIT 1")
    suspend fun getLastRecord(type: RecordType): Record?

    @Query(
        "SELECT COALESCE(SUM(CASE WHEN :type = 'Nutrition' THEN nutrition_calories ELSE value END), 0.0) " +
            "FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime"
    )
    fun sumInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double>

    /**
     * Single query that returns the summed nutrient totals over the range as an [NutritionData].
     * Replaces the previous ~38 per-nutrient `sumXxxInRange` queries; callers read individual
     * nutrients off the returned object (see `nutrientCatalog`).
     */
    @Query("""
        SELECT
            COALESCE(SUM(nutrition_protein), 0.0) AS protein,
            COALESCE(SUM(nutrition_carbohydrates), 0.0) AS carbohydrates,
            COALESCE(SUM(nutrition_fat), 0.0) AS fat,
            COALESCE(SUM(nutrition_fiber), 0.0) AS fiber,
            COALESCE(SUM(nutrition_sugar), 0.0) AS sugar,
            COALESCE(SUM(nutrition_sodium), 0.0) AS sodium,
            COALESCE(SUM(nutrition_biotin), 0.0) AS biotin,
            COALESCE(SUM(nutrition_caffeine), 0.0) AS caffeine,
            COALESCE(SUM(nutrition_calcium), 0.0) AS calcium,
            COALESCE(SUM(nutrition_chloride), 0.0) AS chloride,
            COALESCE(SUM(nutrition_cholesterol), 0.0) AS cholesterol,
            COALESCE(SUM(nutrition_chromium), 0.0) AS chromium,
            COALESCE(SUM(nutrition_copper), 0.0) AS copper,
            COALESCE(SUM(nutrition_folate), 0.0) AS folate,
            COALESCE(SUM(nutrition_folicAcid), 0.0) AS folicAcid,
            COALESCE(SUM(nutrition_iodine), 0.0) AS iodine,
            COALESCE(SUM(nutrition_iron), 0.0) AS iron,
            COALESCE(SUM(nutrition_magnesium), 0.0) AS magnesium,
            COALESCE(SUM(nutrition_manganese), 0.0) AS manganese,
            COALESCE(SUM(nutrition_molybdenum), 0.0) AS molybdenum,
            COALESCE(SUM(nutrition_monounsaturatedFat), 0.0) AS monounsaturatedFat,
            COALESCE(SUM(nutrition_niacin), 0.0) AS niacin,
            COALESCE(SUM(nutrition_pantothenicAcid), 0.0) AS pantothenicAcid,
            COALESCE(SUM(nutrition_phosphorus), 0.0) AS phosphorus,
            COALESCE(SUM(nutrition_polyunsaturatedFat), 0.0) AS polyunsaturatedFat,
            COALESCE(SUM(nutrition_potassium), 0.0) AS potassium,
            COALESCE(SUM(nutrition_riboflavin), 0.0) AS riboflavin,
            COALESCE(SUM(nutrition_saturatedFat), 0.0) AS saturatedFat,
            COALESCE(SUM(nutrition_selenium), 0.0) AS selenium,
            COALESCE(SUM(nutrition_thiamin), 0.0) AS thiamin,
            COALESCE(SUM(nutrition_transFat), 0.0) AS transFat,
            COALESCE(SUM(nutrition_unsaturatedFat), 0.0) AS unsaturatedFat,
            COALESCE(SUM(nutrition_vitaminA), 0.0) AS vitaminA,
            COALESCE(SUM(nutrition_vitaminB12), 0.0) AS vitaminB12,
            COALESCE(SUM(nutrition_vitaminB6), 0.0) AS vitaminB6,
            COALESCE(SUM(nutrition_vitaminC), 0.0) AS vitaminC,
            COALESCE(SUM(nutrition_vitaminD), 0.0) AS vitaminD,
            COALESCE(SUM(nutrition_vitaminE), 0.0) AS vitaminE,
            COALESCE(SUM(nutrition_vitaminK), 0.0) AS vitaminK,
            COALESCE(SUM(nutrition_zinc), 0.0) AS zinc,
            COALESCE(SUM(nutrition_calories), 0.0) AS calories
        FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime
    """)
    fun sumNutritionInRange(
        type: RecordType,
        startTime: kotlin.time.Instant,
        endTime: kotlin.time.Instant,
    ): Flow<NutritionData>

    @Query("SELECT MIN(value) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun minInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double?>
    @Query("SELECT MAX(value) FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun maxInRange(type: RecordType, startTime: kotlin.time.Instant, endTime: kotlin.time.Instant): Flow<Double?>
    @Query("SELECT * FROM Record WHERE type = :type AND startTime >= :startTime AND endTime <= :endTime")
    fun getAllInRange(
        type: RecordType,
        startTime: kotlin.time.Instant,
        endTime: kotlin.time.Instant,
    ): Flow<List<Record>>

    @Query("""
    SELECT 
        date(startTime / 1000, 'unixepoch', 'localtime') as day, 
        SUM(value) as totalValue,
        SUM(secondaryValue) as totalValue2 
    FROM Record 
    WHERE type = :type 
      AND startTime >= :startTime 
      AND endTime <= :endTime
    GROUP BY day
    ORDER BY day ASC
""")
    suspend fun getDailySums(
        type: RecordType,
        startTime: kotlin.time.Instant,
        endTime: kotlin.time.Instant
    ): List<DailySum>

    @Query("""
    SELECT 
        strftime('%Y-%m-%d %H:00', startTime / 1000, 'unixepoch', 'localtime') AS hourBlock, 
        SUM(value) AS totalValue,
        SUM(secondaryValue) AS totalValue2
    FROM Record 
    WHERE type = :type 
      AND startTime >= :startTime 
      AND endTime <= :endTime
    GROUP BY hourBlock
    ORDER BY hourBlock ASC
""")
    suspend fun getHourlySums(
        type: RecordType,
        startTime: Long,
        endTime: Long
    ): List<HourlySum>

    @Query("""
    SELECT 
        date(startTime / 1000, 'unixepoch', 'localtime') as day, 
        AVG(value) as totalValue,
        AVG(secondaryValue) as totalValue2 
    FROM Record 
    WHERE type = :type 
      AND startTime >= :startTime 
      AND endTime <= :endTime
    GROUP BY day
    ORDER BY day ASC
""")
    suspend fun getDailyAvgs(
        type: RecordType,
        startTime: kotlin.time.Instant,
        endTime: kotlin.time.Instant
    ): List<DailySum>

    @Query("""
    SELECT 
        strftime('%Y-%m-%d %H:00', startTime / 1000, 'unixepoch', 'localtime') AS hourBlock, 
        AVG(value) AS totalValue,
        AVG(secondaryValue) AS totalValue2
    FROM Record 
    WHERE type = :type 
      AND startTime >= :startTime 
      AND endTime <= :endTime
    GROUP BY hourBlock
    ORDER BY hourBlock ASC
""")
    suspend fun getHourlyAvgs(
        type: RecordType,
        startTime: Long,
        endTime: Long
    ): List<HourlySum>

    // Helper data class to catch the results
    /** Immutable value describing daily sum. */
    data class DailySum(
        val day: String, // Format: YYYY-MM-DD
        val totalValue: Double,
        val totalValue2: Double
    )
    /** Immutable value describing hourly sum. */
    data class HourlySum(
        val hourBlock: String, // Format: 2026-03-03 15:00
        val totalValue: Double,
        val totalValue2: Double
    )

    // Food Logger Methods
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIngredient(ingredient: Ingredient)

    @Update
    suspend fun updateIngredient(ingredient: Ingredient)

    @Delete
    suspend fun deleteIngredient(ingredient: Ingredient)

    @Query("SELECT * FROM Ingredient ORDER BY originalName ASC")
    fun getAllIngredientsFlow(): Flow<List<Ingredient>>

    @Query("SELECT * FROM Ingredient WHERE isRecipe = 1 ORDER BY originalName ASC")
    fun getIngredientsAsRecipesFlow(): Flow<List<Ingredient>>

    @Query("SELECT * FROM Ingredient WHERE id = :id")
    suspend fun getIngredient(id: String): Ingredient?

    @Query(
        "SELECT * FROM Ingredient WHERE originalName LIKE '%' || :query || '%' " +
            "OR customName LIKE '%' || :query || '%'"
    )
    suspend fun searchIngredients(query: String): List<Ingredient>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipe(recipe: Recipe)

    @Update
    suspend fun updateRecipe(recipe: Recipe)

    @Delete
    suspend fun deleteRecipe(recipe: Recipe)

    @Query("SELECT * FROM Recipe ORDER BY name ASC")
    fun getAllRecipesFlow(): Flow<List<Recipe>>

    @Query("SELECT * FROM Recipe WHERE id = :id")
    suspend fun getRecipe(id: String): Recipe?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServingUnit(unit: ServingUnit)

    @Delete
    suspend fun deleteServingUnit(unit: ServingUnit)

    @Query("SELECT * FROM ServingUnit WHERE ingredientId = :ingredientId")
    suspend fun getUnitsForIngredient(ingredientId: String): List<ServingUnit>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipeIngredient(recipeIngredient: RecipeIngredient)

    @Delete
    suspend fun deleteRecipeIngredient(recipeIngredient: RecipeIngredient)

    @Query("SELECT * FROM RecipeIngredient WHERE recipeId = :recipeId")
    suspend fun getIngredientsForRecipe(recipeId: String): List<RecipeIngredient>
}

@Database(
    entities = [
        Record::class, Ingredient::class, Recipe::class, ServingUnit::class, RecipeIngredient::class,
        VaccinationEntry::class, MedicationEntry::class, MedicalAttachment::class,
        MedicationSchedule::class, AllergyEntry::class, ConditionEntry::class,
        LabResultEntry::class, HealthProfile::class, ProfileAnswer::class, DoseEvent::class,
        DeletedMedicalResource::class, PendingMirror::class,
    ],
    version = 12,
    exportSchema = true
)
/** Health Database: a room database implementation for health database. */
@ColumnTypeConverters(Converters::class)
abstract class HealthDatabase : RoomDatabase() {
    abstract fun healthDao(): HealthDao
    abstract fun medicalDao(): MedicalDao
    abstract fun scheduleDao(): ScheduleDao

    /** Shared constants and helpers for HealthDatabase. */
    companion object : com.vayunmathur.library.util.DatabaseMigrations {
        override val migrations = listOf(
            // v1 -> v2 (reconstructed): nutrition columns + the type/time index.
            // History: v1 (74d509796, "health: sync to local db first") had only
            // Record(id, index, type, startTime, endTime, value, secondaryValue,
            // primaryKey). 51de2c18a then embedded NutritionData (every field
            // except calories) and 4af82b094 added the (type, startTime, endTime)
            // index, both WITHOUT a version bump, and b091920a9 jumped straight
            // to version = 3 - version 2 was never committed or shipped. This
            // step assigns the nutrition+index half of that diff to 1->2. The
            // table is recreated (copying only the v1-guaranteed columns) so a
            // v1 file that already carries the unversioned nutrition columns
            // does not die on a duplicate-column ALTER; those columns restart
            // NULL, as they do for a plain v1 file.
            Migration(1, 2) {
                it.execSQL(
                    "CREATE TABLE IF NOT EXISTS `Record_new` (`id` TEXT NOT NULL, `index` INTEGER NOT NULL, " +
                        "`type` TEXT NOT NULL, `startTime` INTEGER NOT NULL, `endTime` INTEGER NOT NULL, " +
                        "`value` REAL NOT NULL, `secondaryValue` REAL NOT NULL, `primaryKey` TEXT NOT NULL, " +
                        "PRIMARY KEY(`primaryKey`))"
                )
                listOf(
                    "protein", "carbohydrates", "fat", "fiber", "sugar", "sodium", "biotin", "caffeine",
                    "calcium", "chloride", "cholesterol", "chromium", "copper", "folate", "folicAcid",
                    "iodine", "iron", "magnesium", "manganese", "molybdenum", "monounsaturatedFat",
                    "niacin", "pantothenicAcid", "phosphorus", "polyunsaturatedFat", "potassium",
                    "riboflavin", "saturatedFat", "selenium", "thiamin", "transFat", "unsaturatedFat",
                    "vitaminA", "vitaminB12", "vitaminB6", "vitaminC", "vitaminD", "vitaminE",
                    "vitaminK", "zinc",
                ).forEach { field -> it.execSQL("ALTER TABLE `Record_new` ADD COLUMN `nutrition_$field` REAL") }
                it.execSQL(
                    "INSERT INTO `Record_new` (`id`, `index`, `type`, `startTime`, `endTime`, `value`, " +
                        "`secondaryValue`, `primaryKey`) SELECT `id`, `index`, `type`, `startTime`, " +
                        "`endTime`, `value`, `secondaryValue`, `primaryKey` FROM `Record`"
                )
                it.execSQL("DROP TABLE IF EXISTS `Record`")
                it.execSQL("ALTER TABLE `Record_new` RENAME TO `Record`")
                it.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_Record_type_startTime_endTime` " +
                        "ON `Record` (`type`, `startTime`, `endTime`)"
                )
            },
            // v2 -> v3 (reconstructed): the rest of b091920a9's 1->3 diff -
            // nutrition_calories, Record.metadata, and the four food-logging
            // tables (Ingredient/Recipe/ServingUnit/RecipeIngredient, with
            // their foreign keys and indices) exactly as created at v3.
            Migration(2, 3) {
                it.execSQL("ALTER TABLE Record ADD COLUMN nutrition_calories REAL")
                it.execSQL("ALTER TABLE Record ADD COLUMN metadata TEXT")
                val nutrition = listOf(
                    "protein", "carbohydrates", "fat", "fiber", "sugar", "sodium", "biotin", "caffeine",
                    "calcium", "chloride", "cholesterol", "chromium", "copper", "folate", "folicAcid",
                    "iodine", "iron", "magnesium", "manganese", "molybdenum", "monounsaturatedFat",
                    "niacin", "pantothenicAcid", "phosphorus", "polyunsaturatedFat", "potassium",
                    "riboflavin", "saturatedFat", "selenium", "thiamin", "transFat", "unsaturatedFat",
                    "vitaminA", "vitaminB12", "vitaminB6", "vitaminC", "vitaminD", "vitaminE",
                    "vitaminK", "zinc", "calories",
                ).joinToString(", ") { field -> "`nutrition_$field` REAL NOT NULL" }
                it.execSQL(
                    "CREATE TABLE IF NOT EXISTS `Ingredient` (`id` TEXT NOT NULL, `originalName` TEXT NOT NULL, " +
                        "`customName` TEXT, `isRecipe` INTEGER NOT NULL, $nutrition, PRIMARY KEY(`id`))"
                )
                it.execSQL(
                    "CREATE TABLE IF NOT EXISTS `Recipe` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                it.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ServingUnit` (`id` TEXT NOT NULL, `ingredientId` TEXT, " +
                        "`recipeId` TEXT, `name` TEXT NOT NULL, `grams` REAL NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`ingredientId`) REFERENCES `Ingredient`(`id`) ON UPDATE NO ACTION " +
                        "ON DELETE CASCADE , FOREIGN KEY(`recipeId`) REFERENCES `Recipe`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                it.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ServingUnit_ingredientId` ON `ServingUnit` (`ingredientId`)"
                )
                it.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ServingUnit_recipeId` ON `ServingUnit` (`recipeId`)"
                )
                it.execSQL(
                    "CREATE TABLE IF NOT EXISTS `RecipeIngredient` (`id` TEXT NOT NULL, `recipeId` TEXT NOT NULL, " +
                        "`ingredientId` TEXT NOT NULL, `quantity` REAL NOT NULL, `unitId` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`), FOREIGN KEY(`recipeId`) REFERENCES `Recipe`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`ingredientId`) " +
                        "REFERENCES `Ingredient`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                        "FOREIGN KEY(`unitId`) REFERENCES `ServingUnit`(`id`) ON UPDATE NO ACTION " +
                        "ON DELETE RESTRICT )"
                )
                it.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_RecipeIngredient_recipeId` " +
                        "ON `RecipeIngredient` (`recipeId`)"
                )
                it.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_RecipeIngredient_ingredientId` " +
                        "ON `RecipeIngredient` (`ingredientId`)"
                )
                it.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_RecipeIngredient_unitId` ON `RecipeIngredient` (`unitId`)"
                )
            },
            // v3 -> v4 (reconstructed): a86f12f81 ("health: fix sleep page
            // layout", version 3 -> 4) embedded SleepData in Record. The
            // embedded object is nullable, so every sleep column is nullable.
            Migration(3, 4) {
                listOf(
                    "awakeDurationMillis" to "INTEGER", "remDurationMillis" to "INTEGER",
                    "lightDurationMillis" to "INTEGER", "deepDurationMillis" to "INTEGER",
                    "unknownDurationMillis" to "INTEGER", "stagesJson" to "TEXT",
                ).forEach { (field, type) ->
                    it.execSQL("ALTER TABLE Record ADD COLUMN `sleep_$field` $type")
                }
            },
            Migration(4, 5) {
                it.execSQL("ALTER TABLE Record ADD COLUMN exercise_exerciseType INTEGER NOT NULL DEFAULT 0")
                it.execSQL("ALTER TABLE Record ADD COLUMN exercise_title TEXT")
                it.execSQL("ALTER TABLE Record ADD COLUMN exercise_notes TEXT")
                it.execSQL("ALTER TABLE Record ADD COLUMN exercise_segmentsJson TEXT")
                it.execSQL("ALTER TABLE Record ADD COLUMN exercise_lapsJson TEXT")
                it.execSQL("ALTER TABLE Record ADD COLUMN exercise_hasRoute INTEGER NOT NULL DEFAULT 0")
            },
            Migration(5, 6) {
                it.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS VaccinationEntry (
                        id TEXT NOT NULL PRIMARY KEY,
                        cvxCode TEXT,
                        displayName TEXT NOT NULL,
                        occurredAt INTEGER NOT NULL,
                        lotNumber TEXT,
                        site TEXT,
                        route TEXT,
                        doseQuantity TEXT,
                        performer TEXT,
                        note TEXT,
                        fhirResourceId TEXT,
                        dataSourceId TEXT
                    )
                    """.trimIndent()
                )
                it.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_VaccinationEntry_occurredAt " +
                        "ON VaccinationEntry (occurredAt)"
                )
                it.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS MedicationEntry (
                        id TEXT NOT NULL PRIMARY KEY,
                        rxcui TEXT,
                        displayName TEXT NOT NULL,
                        strength TEXT,
                        doseForm TEXT,
                        status TEXT NOT NULL,
                        startedAt INTEGER NOT NULL,
                        endedAt INTEGER,
                        dosageText TEXT,
                        note TEXT,
                        fhirResourceId TEXT,
                        dataSourceId TEXT
                    )
                    """.trimIndent()
                )
                it.execSQL("CREATE INDEX IF NOT EXISTS index_MedicationEntry_startedAt ON MedicationEntry (startedAt)")
                it.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS MedicalAttachment (
                        id TEXT NOT NULL PRIMARY KEY,
                        vaccinationId TEXT NOT NULL,
                        fileName TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        importedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                it.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_MedicalAttachment_vaccinationId " +
                        "ON MedicalAttachment (vaccinationId)"
                )
            },
            Migration(6, 7) {
                it.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS MedicationSchedule (
                        id TEXT NOT NULL PRIMARY KEY,
                        medicationId TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        times TEXT NOT NULL DEFAULT '',
                        repeatUnit TEXT NOT NULL DEFAULT 'Daily',
                        interval INTEGER NOT NULL DEFAULT 1,
                        daysOfWeek INTEGER NOT NULL DEFAULT 0,
                        anchorDate INTEGER NOT NULL,
                        endDate INTEGER
                    )
                    """.trimIndent()
                )
                it.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_MedicationSchedule_medicationId " +
                        "ON MedicationSchedule (medicationId)"
                )
            },
            Migration(7, 8) {
                it.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS AllergyEntry (
                        id TEXT NOT NULL PRIMARY KEY,
                        rxcui TEXT,
                        displayName TEXT NOT NULL,
                        category TEXT NOT NULL DEFAULT 'Medication',
                        criticality TEXT NOT NULL DEFAULT 'Unknown',
                        reaction TEXT,
                        onsetAt INTEGER,
                        recordedAt INTEGER NOT NULL,
                        note TEXT,
                        fhirResourceId TEXT,
                        dataSourceId TEXT
                    )
                    """.trimIndent()
                )
                it.execSQL("CREATE INDEX IF NOT EXISTS index_AllergyEntry_recordedAt ON AllergyEntry (recordedAt)")
                it.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ConditionEntry (
                        id TEXT NOT NULL PRIMARY KEY,
                        icd10Code TEXT,
                        displayName TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'Active',
                        onsetAt INTEGER NOT NULL,
                        resolvedAt INTEGER,
                        note TEXT,
                        fhirResourceId TEXT,
                        dataSourceId TEXT
                    )
                    """.trimIndent()
                )
                it.execSQL("CREATE INDEX IF NOT EXISTS index_ConditionEntry_onsetAt ON ConditionEntry (onsetAt)")
            },
            Migration(8, 9) {
                it.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS LabResultEntry (
                        id TEXT NOT NULL PRIMARY KEY,
                        loincCode TEXT,
                        displayName TEXT NOT NULL,
                        value REAL,
                        valueText TEXT,
                        unit TEXT,
                        referenceLow REAL,
                        referenceHigh REAL,
                        takenAt INTEGER NOT NULL,
                        note TEXT,
                        fhirResourceId TEXT,
                        dataSourceId TEXT
                    )
                    """.trimIndent()
                )
                it.execSQL("CREATE INDEX IF NOT EXISTS index_LabResultEntry_takenAt ON LabResultEntry (takenAt)")
                it.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS HealthProfile (
                        id TEXT NOT NULL PRIMARY KEY,
                        pregnancyStatus TEXT NOT NULL DEFAULT 'Unknown',
                        dueDate INTEGER,
                        pregnancyRecordedAt INTEGER,
                        pregnancyFhirId TEXT,
                        smokingStatus TEXT NOT NULL DEFAULT 'Unknown',
                        smokingRecordedAt INTEGER,
                        smokingFhirId TEXT,
                        dataSourceId TEXT
                    )
                    """.trimIndent()
                )
            },
            Migration(9, 10) {
                it.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ProfileAnswer (
                        loincCode TEXT NOT NULL PRIMARY KEY,
                        answerCode TEXT NOT NULL,
                        recordedAt INTEGER NOT NULL,
                        fhirResourceId TEXT,
                        dataSourceId TEXT
                    )
                    """.trimIndent()
                )
            },
            Migration(10, 11) {
                it.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS DoseEvent (
                        id TEXT NOT NULL PRIMARY KEY,
                        medicationId TEXT NOT NULL,
                        takenAt INTEGER NOT NULL,
                        fhirResourceId TEXT,
                        dataSourceId TEXT
                    )
                    """.trimIndent()
                )
                it.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_DoseEvent_medicationId_takenAt " +
                        "ON DoseEvent (medicationId, takenAt)"
                )
            },
            Migration(11, 12) {
                it.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS DeletedMedicalResource (
                        kind TEXT NOT NULL,
                        dataSourceId TEXT NOT NULL,
                        fhirResourceId TEXT NOT NULL,
                        fhirResourceType INTEGER NOT NULL,
                        retryDelete INTEGER NOT NULL,
                        PRIMARY KEY(kind, dataSourceId, fhirResourceId)
                    )
                    """.trimIndent()
                )
                it.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS PendingMirror (
                        kind TEXT NOT NULL,
                        rowId TEXT NOT NULL,
                        PRIMARY KEY(kind, rowId)
                    )
                    """.trimIndent()
                )
                // Anything that never reached Health Connect gets queued, so the first sync on the
                // new version pushes it rather than leaving it local forever.
                listOf(
                    "Vaccination" to "VaccinationEntry",
                    "Medication" to "MedicationEntry",
                    "Dose" to "DoseEvent",
                    "Allergy" to "AllergyEntry",
                    "Condition" to "ConditionEntry",
                    "LabResult" to "LabResultEntry",
                ).forEach { (kind, table) ->
                    it.execSQL(
                        "INSERT OR IGNORE INTO PendingMirror (kind, rowId) " +
                            "SELECT '$kind', id FROM $table WHERE fhirResourceId IS NULL"
                    )
                }
            }
        )
    }
}

/** Type representing converters. */
class Converters {
    @ColumnTypeConverter
    fun fromTimestamp(value: Long?): Instant? = value?.let { Instant.ofEpochMilli(it) }

    @ColumnTypeConverter
    fun dateToTimestamp(date: Instant?): Long? = date?.toEpochMilli()

    @ColumnTypeConverter
    fun toTS(date: kotlin.time.Instant): Long = date.toEpochMilliseconds()

    @ColumnTypeConverter
    fun fromTS(timestamp: Long): kotlin.time.Instant = kotlin.time.Instant.fromEpochMilliseconds(timestamp)

    /** Dose times, stored as comma-separated seconds-since-midnight. */
    @ColumnTypeConverter
    fun fromIntList(value: List<Int>): String = value.joinToString(",")

    @ColumnTypeConverter
    fun toIntList(value: String): List<Int> =
        if (value.isBlank()) emptyList() else value.split(",").mapNotNull { it.trim().toIntOrNull() }

    @ColumnTypeConverter
    fun fromLocalDate(date: kotlinx.datetime.LocalDate): Long = date.toEpochDays()

    @ColumnTypeConverter
    fun toLocalDate(days: Long): kotlinx.datetime.LocalDate =
        kotlinx.datetime.LocalDate.fromEpochDays(days)

    @ColumnTypeConverter
    fun fromLocalDateNullable(date: kotlinx.datetime.LocalDate?): Long? = date?.toEpochDays()

    @ColumnTypeConverter
    fun toLocalDateNullable(days: Long?): kotlinx.datetime.LocalDate? =
        days?.let { kotlinx.datetime.LocalDate.fromEpochDays(it) }
}
