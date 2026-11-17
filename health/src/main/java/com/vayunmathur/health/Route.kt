package com.vayunmathur.health

import com.vayunmathur.library.util.NavKey
import com.vayunmathur.health.ui.HealthMetricConfig
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route: NavKey {
    /** Singleton holding today state and operations. */
    @Serializable
    data object Today: Route

    /** Singleton holding body state and operations. */
    @Serializable
    data object Body: Route

    /** Singleton holding nutrition details state and operations. */
    @Serializable
    data object NutritionDetails: Route

    /** Singleton holding nutrition full breakdown state and operations. */
    @Serializable
    data object NutritionFullBreakdown: Route

    /** Singleton holding recipe management state and operations. */
    @Serializable
    data object RecipeManagement: Route

    /** Immutable value describing recipe editor; carries recipeId. */
    @Serializable
    data class RecipeEditor(val recipeId: String? = null): Route

    /** Immutable value describing bar chart details; carries healthMetric. */
    @Serializable
    data class BarChartDetails(val healthMetric: HealthMetricConfig): Route

    /** Singleton holding sleep details state and operations. */
    @Serializable
    data object SleepDetails: Route

    /** Singleton holding exercise details state and operations. */
    @Serializable
    data object ExerciseDetails: Route

    /** Singleton holding medical state and operations. */
    @Serializable
    data object Medical: Route

    /** The vaccination form. A null [id] adds a new record; otherwise it edits that one. */
    @Serializable
    data class EditVaccination(val id: String? = null): Route

    /** The medication form. A null [id] adds a new record; otherwise it edits that one. */
    @Serializable
    data class EditMedication(val id: String? = null): Route

    /** The allergy form. A null [id] adds a new record; otherwise it edits that one. */
    @Serializable
    data class EditAllergy(val id: String? = null): Route

    /** The condition form. A null [id] adds a new record; otherwise it edits that one. */
    @Serializable
    data class EditCondition(val id: String? = null): Route

    /** The lab result form. A null [id] adds a new record; otherwise it edits that one. */
    @Serializable
    data class EditLabResult(val id: String? = null): Route

    /**
     * The searchable reference-data picker. [ingredient] narrows
     * [com.vayunmathur.health.ui.CatalogKind.MedicationProduct] to one drug and is unused otherwise.
     *
     * Carries no result key: the picker writes straight into the ViewModel's draft, because this is
     * a full-screen destination and the form underneath is not composed while it is open.
     */
    @Serializable
    data class CatalogPicker(
        val kind: com.vayunmathur.health.ui.CatalogKind,
        val ingredient: String? = null,
    ): Route

    /**
     * The shared date picker, hosted here so the medical forms can reach it.
     *
     * [allowClear] adds a Clear button and changes the result type to `DateSelection`; set it for
     * any field that is allowed to have no date.
     */
    @Serializable
    data class MedicalDatePicker(
        val key: String,
        val initialDate: LocalDate,
        val allowClear: Boolean = false,
    ): Route

    /** The shared time picker, for dose reminder times. */
    @Serializable
    data class MedicalTimePicker(val key: String, val initialTime: LocalTime): Route
}
