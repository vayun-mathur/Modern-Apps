package com.vayunmathur.everysync.model

/** A contact resolved from a remote source (CardDAV vCard or Google People). */
data class RemoteContact(
    /** Stable remote identifier (vCard UID or People resourceName). */
    val uid: String,
    /** Server ETag for change detection, if known. */
    val etag: String? = null,
    val displayName: String = "",
    val prefix: String = "",
    val firstName: String = "",
    val middleName: String = "",
    val lastName: String = "",
    val suffix: String = "",
    val organization: String = "",
    val note: String = "",
    /** number -> type (ContactsContract.CommonDataKinds.Phone type int). */
    val phones: List<TypedValue> = emptyList(),
    val emails: List<TypedValue> = emptyList(),
    val addresses: List<TypedValue> = emptyList(),
    /** ISO-8601 birthday (yyyy-MM-dd) or null. */
    val birthday: String? = null,
    /** For CardDAV: the resource path on the server (collection-relative). */
    val href: String? = null,
    val deleted: Boolean = false,
)

data class TypedValue(val value: String, val type: Int)

/** A calendar collection (CalDAV collection or Google calendarList entry). */
data class RemoteCalendar(
    val id: String,
    val displayName: String,
    val color: Int? = null,
    /** CalDAV collection ctag for cheap change detection. */
    val ctag: String? = null,
    /** CalDAV collection URL. */
    val url: String? = null,
)

/** A calendar event resolved from a remote source. */
data class RemoteEvent(
    val uid: String,
    val etag: String? = null,
    val calendarId: String,
    val summary: String = "",
    val description: String = "",
    val location: String = "",
    val startMillis: Long = 0L,
    val endMillis: Long = 0L,
    val allDay: Boolean = false,
    val timezone: String = "UTC",
    /** Raw RRULE line (without the "RRULE:" prefix), or null. */
    val rrule: String? = null,
    val href: String? = null,
    val deleted: Boolean = false,
)

/** A health measurement destined for Health Connect. */
data class RemoteMeasurement(
    /** Provider-scoped stable id, used as Health Connect clientRecordId. */
    val clientRecordId: String,
    val type: MeasurementType,
    /** Primary value in the type's canonical unit (see [MeasurementType]). */
    val value: Double = 0.0,
    /** Start instant; equals [endMillis] for instantaneous sample/daily records. */
    val startMillis: Long,
    /** End instant for interval/session records (steps, distance, sleep, ...). */
    val endMillis: Long = startMillis,
    /** Sleep-stage segments; populated only for [MeasurementType.SLEEP]. */
    val sleepStages: List<RemoteSleepStage> = emptyList(),
)

/** One sleep-stage segment. [stage] is a Health Connect `SleepSessionRecord.STAGE_TYPE_*` value. */
data class RemoteSleepStage(
    val startMillis: Long,
    val endMillis: Long,
    val stage: Int,
)

/**
 * Health metrics synced from Google Health into Health Connect. Each maps to one
 * Health Connect record type; the trailing comment is the canonical unit stored in
 * [RemoteMeasurement.value].
 */
enum class MeasurementType {
    // Body composition (instantaneous samples)
    WEIGHT, // kilograms
    HEIGHT, // meters
    BODY_FAT, // percent (0-100)

    // Vitals (instantaneous)
    HEART_RATE, // beats/min
    RESTING_HEART_RATE, // beats/min
    HEART_RATE_VARIABILITY, // milliseconds (RMSSD)
    OXYGEN_SATURATION, // percent (0-100)
    RESPIRATORY_RATE, // breaths/min
    BLOOD_GLUCOSE, // mg/dL
    BODY_TEMPERATURE, // degrees Celsius
    VO2_MAX, // mL/kg/min

    // Activity (intervals)
    STEPS, // count
    DISTANCE, // meters
    FLOORS, // count
    ACTIVE_CALORIES, // kilocalories
    TOTAL_CALORIES, // kilocalories

    // Lifestyle
    HYDRATION, // liters
    SLEEP, // session (start/end + sleepStages)
}
