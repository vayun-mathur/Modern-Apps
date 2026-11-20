package com.vayunmathur.health.domain

import com.vayunmathur.health.data.DoseEvent
import com.vayunmathur.health.data.MedicationEntry
import com.vayunmathur.health.data.MedicationSchedule
import com.vayunmathur.health.data.MedicationStatus
import com.vayunmathur.health.data.RepeatUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.datetime.LocalDate as KotlinLocalDate

/**
 * A medication the user is on, as a FHIR `MedicationRequest` with `intent: plan`.
 *
 * A request rather than a statement because that is the split FHIR draws: `MedicationRequest`
 * is the plan to take something, carrying the dosage instructions; `MedicationStatement` is a
 * report that a dose was actually taken. Recording an ongoing regimen as a statement would
 * assert that the whole course had been consumed.
 *
 * `intent` is `plan` rather than `order` because nobody prescribed this — it is the user's own
 * account of what they intend to take. The course dates always travel as
 * `dosageInstruction.timing.repeat.boundsPeriod`, since MedicationRequest has no other field for
 * them; a [schedule] adds the reminder times to the same `repeat`.
 */
internal fun medicationRequestJson(
    entry: MedicationEntry,
    schedule: MedicationSchedule? = null,
): String = buildJsonObject {
    put("resourceType", "MedicationRequest")
    put("id", entry.fhirId())
    put("status", requestStatusToFhir(entry.status))
    put("intent", "plan")
    // The user is the source. Without this a reader would assume a clinician entered it.
    put("reportedBoolean", true)
    putMedicationConcept(entry)
    putJsonObject("subject") { put("reference", "Patient/${FhirRecords.PATIENT_RESOURCE_ID}") }
    put("authoredOn", formatClinicalDate(entry.startedAt))

    putJsonArray("dosageInstruction") {
        add(
            buildJsonObject {
                entry.dosageText?.takeIf { it.isNotBlank() }?.let { put("text", it) }
                putJsonObject("timing") { putRepeat(entry, schedule) }
            }
        )
    }
    entry.note?.takeIf { it.isNotBlank() }?.let {
        putJsonArray("note") { add(buildJsonObject { put("text", it) }) }
    }
}.toString()

private fun MedicationEntry.fhirId(): String = fhirResourceId ?: id

private fun JsonObjectBuilder.putMedicationConcept(entry: MedicationEntry) {
    putJsonObject("medicationCodeableConcept") {
        if (entry.rxcui != null) {
            putJsonArray("coding") {
                add(
                    buildJsonObject {
                        put("system", FhirRecords.RXNORM_SYSTEM)
                        put("code", entry.rxcui)
                        put("display", fullMedicationName(entry))
                    }
                )
            }
        }
        put("text", fullMedicationName(entry))
    }
}

/**
 * The course dates and, when there is one, the dose schedule as a FHIR `Timing.repeat`.
 *
 * Times of day become `timeOfDay`, the interval and its unit become `period` and `periodUnit`,
 * and chosen weekdays become `dayOfWeek`.
 */

/** Times of day and the derived frequency for a schedule's repeat. */
private fun JsonObjectBuilder.putScheduleTimes(
    schedule: MedicationSchedule,
    weekly: Boolean,
    doseDays: Int,
) {
    if (schedule.times.isEmpty()) return
    putJsonArray("timeOfDay") {
        schedule.times.sorted().forEach { add(JsonPrimitive(formatTimeOfDay(it))) }
    }
    // frequency counts doses per period, so a weekly schedule multiplies its times of
    // day by the number of days it falls on.
    if (!weekly || doseDays > 0) {
        put("frequency", schedule.times.size * (if (weekly) doseDays else 1))
    }
}
private fun JsonObjectBuilder.putRepeat(entry: MedicationEntry, schedule: MedicationSchedule?) {
    putJsonObject("repeat") {
        putJsonObject("boundsPeriod") {
            put("start", formatClinicalDate(entry.startedAt))
            // A finished course ends when it ended. An ongoing one ends when its reminders do,
            // if they are set to.
            val end = entry.endedAt?.takeIf { entry.status != MedicationStatus.Active }
                ?.let { formatClinicalDate(it) }
                ?: schedule?.endDate?.let { formatScheduleDate(it) }
            end?.let { put("end", it) }
        }
        if (schedule == null) return@putJsonObject

        val weekly = schedule.repeatUnit == RepeatUnit.Weekly
        val doseDays = Integer.bitCount(schedule.daysOfWeek and ALL_DAYS_MASK)
        putScheduleTimes(schedule, weekly, doseDays)
        put("period", schedule.interval)
        put("periodUnit", if (weekly) "wk" else "d")
        if (weekly && doseDays > 0) {
            putJsonArray("dayOfWeek") {
                FHIR_DAYS.forEachIndexed { index, day ->
                    if (schedule.daysOfWeek and (1 shl index) != 0) add(JsonPrimitive(day))
                }
            }
        }
    }
}

/** Bit 0 is Sunday, matching `MedicationSchedule.daysOfWeek`. */
private val FHIR_DAYS = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
private const val ALL_DAYS_MASK = 0x7F

/** A schedule date as a FHIR `date`. */
private fun formatScheduleDate(date: KotlinLocalDate): String =
    String.format(Locale.US, "%04d-%02d-%02d", date.year, date.monthNumber, date.dayOfMonth)

/**
 * The local calendar date of an instant, for the schedule's anchor.
 *
 * Local rather than UTC because a schedule anchored "today" means the user's today.
 */
internal fun Instant.toScheduleDate(zone: ZoneId = ZoneId.systemDefault()): KotlinLocalDate {
    val local = atZone(zone).toLocalDate()
    return KotlinLocalDate(local.year, local.monthValue, local.dayOfMonth)
}

/** "08:00:00" from seconds since midnight, the form `Timing.repeat.timeOfDay` takes. */
private fun formatTimeOfDay(seconds: Int): String =
    String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)

/** "Amoxicillin", "500 mg", "Capsule" rendered as one line for `display` and `text`. */
internal fun fullMedicationName(entry: MedicationEntry): String =
    listOfNotNull(
        entry.displayName.takeIf { it.isNotBlank() },
        entry.strength?.takeIf { it.isNotBlank() },
        entry.doseForm?.takeIf { it.isNotBlank() },
    ).joinToString(" ")

/** The display name and RxNorm code of a `medicationCodeableConcept`, or null with no name. */
private fun JsonObject.medicationName(): Pair<String, String?>? {
    val concept = this["medicationCodeableConcept"] as? JsonObject
    val coding = concept.codingWithSystem(FhirRecords.RXNORM_SYSTEM)
    val displayName = coding?.string("display")
        ?: concept?.string("text")
        ?: concept.anyCoding()?.string("display")
        ?: return null
    return displayName to coding?.string("code")
}

/**
 * A medication regimen read back from a `MedicationRequest`.
 *
 * Returns the entry and, when the request carries a `timing.repeat` this app can represent, the
 * schedule behind it — a provider's instructions are worth keeping rather than flattening to a
 * date range. The entry's `id` is the FHIR id; the importer assigns the local row id.
 */

/** The shared medication header: root, id, display name and RxCUI. */
private data class MedHeader(
    val root: JsonObject,
    val id: String,
    val displayName: String,
    val rxcui: String?,
)

/** Parses and type-checks the resource header; null when it is not a [type] with a name. */
private fun medHeader(data: String, type: String): MedHeader? {
    val root = parseObject(data) ?: return null
    if (root.string("resourceType") != type) return null
    val id = root.string("id") ?: return null
    val (displayName, rxcui) = root.medicationName() ?: return null
    return MedHeader(root, id, displayName, rxcui)
}
internal fun parseMedicationRequest(
    data: String,
    dataSourceId: String,
): Pair<MedicationEntry, MedicationSchedule?>? {
    val header = medHeader(data, "MedicationRequest") ?: return null
    val root = header.root
    val id = header.id
    val status = requestStatusFromFhir(root.string("status")) ?: return null
    val (displayName, rxcui) = header.displayName to header.rxcui

    val dosage = (root["dosageInstruction"] as? JsonArray)?.firstOrNull() as? JsonObject
    val repeat = ((dosage?.get("timing") as? JsonObject)?.get("repeat")) as? JsonObject
    val bounds = repeat?.get("boundsPeriod") as? JsonObject

    // boundsPeriod is when dosing runs; authoredOn is when the request was written, which for a
    // prescription can be well before the first dose.
    val startedAt = parseDateTime(bounds.string("start"))
        ?: parseDateTime(root.string("authoredOn"))
        ?: return null
    val boundsEnd = parseDateTime(bounds.string("end"))

    val entry = MedicationEntry(
        id = id,
        rxcui = rxcui,
        displayName = displayName,
        status = status,
        startedAt = startedAt,
        // An ongoing course's end is only planned, and belongs to the reminders.
        endedAt = boundsEnd?.takeIf { status != MedicationStatus.Active },
        dosageText = dosage?.string("text"),
        note = root.firstNoteText(),
        fhirResourceId = id,
        dataSourceId = dataSourceId,
    )
    return entry to repeat?.let { parseRepeat(it, id, startedAt, boundsEnd) }
}

/**
 * A medication a provider reports the patient as taking, from a `MedicationStatement` that is not
 * a dose against one of this app's plans.
 */
internal fun parseMedicationStatement(data: String, dataSourceId: String): MedicationEntry? {
    val header = medHeader(data, "MedicationStatement") ?: return null
    val root = header.root
    if (root.basedOnRequestId() != null) return null
    val id = header.id
    val status = statementStatusFromFhir(root.string("status")) ?: return null
    val (displayName, rxcui) = header.displayName to header.rxcui

    val period = root["effectivePeriod"] as? JsonObject
    val startedAt = parseDateTime(root.string("effectiveDateTime"))
        ?: parseDateTime(period.string("start"))
        ?: parseDateTime(root.string("dateAsserted"))
        ?: return null
    val dosage = (root["dosage"] as? JsonArray)?.firstOrNull() as? JsonObject

    return MedicationEntry(
        id = id,
        rxcui = rxcui,
        displayName = displayName,
        status = status,
        startedAt = startedAt,
        endedAt = parseDateTime(period.string("end"))?.takeIf { status != MedicationStatus.Active },
        dosageText = dosage?.string("text"),
        note = root.firstNoteText(),
        fhirResourceId = id,
        dataSourceId = dataSourceId,
    )
}

/**
 * Whether this is a `MedicationStatement` with no plan behind it.
 *
 * From this app's own data source that can only be a regimen an earlier build wrote as a
 * statement, before medications moved to `MedicationRequest`.
 */
internal fun isUnplannedMedicationStatement(data: String): Boolean {
    val root = parseObject(data) ?: return false
    return root.string("resourceType") == "MedicationStatement" && root.basedOnRequestId() == null
}


/** The repeat unit and interval, normalising daily-on-weekdays to weekly. */
private fun repeatUnitInterval(
    periodUnit: String?,
    days: Int,
    intervalIn: Int,
): Pair<RepeatUnit, Int>? {
    var unit = when (periodUnit) {
        null, "d" -> RepeatUnit.Daily
        "wk" -> RepeatUnit.Weekly
        // Hours, minutes, months and years cannot be expressed as days or weeks. Reading "every 8
        // hours" as "every 8 days" would be a wrong regimen, so no schedule is better.
        else -> return null
    }
    var interval = intervalIn
    if (unit == RepeatUnit.Daily && days != 0) {
        // Daily on chosen weekdays is weekly on those days. With a multi-day period as well the
        // two constraints do not combine into anything this model can say.
        if (interval != 1) return null
        unit = RepeatUnit.Weekly
        interval = 1
    }
    if (unit == RepeatUnit.Weekly && days == 0) return null
    return unit to interval
}
private fun parseRepeat(
    repeat: JsonObject,
    medicationId: String,
    startedAt: Instant,
    endsAt: Instant?,
): MedicationSchedule? {
    val times = (repeat["timeOfDay"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content?.let(::parseTimeOfDay) }
        .orEmpty()
    // Without clock times there is nothing to remind at, whatever else the timing says.
    if (times.isEmpty()) return null

    // period is a decimal in FHIR. Only whole days or weeks fit this app's model; anything else
    // is refused rather than rounded into a different regimen.
    val period = repeat["period"]?.jsonPrimitive?.doubleOrNull ?: 1.0
    if (period < 1 || period % 1.0 != 0.0) return null
    val parsedInterval = period.toInt()

    val days = (repeat["dayOfWeek"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content }
        ?.fold(0) { acc, day ->
            val index = FHIR_DAYS.indexOf(day)
            if (index < 0) acc else acc or (1 shl index)
        } ?: 0
    val (unit, interval) = repeatUnitInterval(repeat.string("periodUnit"), days, parsedInterval)
        ?: return null

    return MedicationSchedule(
        id = medicationId,
        medicationId = medicationId,
        // Imported schedules arrive switched off. Someone else's record should not start a
        // phone ringing on its own.
        enabled = false,
        times = times.distinct().sorted(),
        repeatUnit = unit,
        interval = interval,
        daysOfWeek = days,
        anchorDate = startedAt.toScheduleDate(),
        endDate = endsAt?.toScheduleDate(),
    )
}

/** Seconds since midnight from "08:00:00", tolerating a missing seconds field. */
private fun parseTimeOfDay(value: String): Int? {
    val parts = value.split(":")
    if (parts.size < 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val s = parts.getOrNull(2)?.substringBefore('.')?.toIntOrNull() ?: 0
    // FHIR timings come from other systems; an out-of-range clock time such as
    // "24:00" or "08:60" is not a dose time. Returning it as seconds-since-
    // midnight would be stored on the schedule and later crash the editor's
    // LocalTime.fromSecondOfDay rendering, so reject it like any other
    // unparseable value.
    if (h !in 0..23 || m !in 0..59 || s !in 0..59) return null
    return h * 3600 + m * 60 + s
}

/**
 * One dose actually taken, as a FHIR `MedicationStatement`.
 *
 * This is what `MedicationStatement` is for: a report that a medication was taken, as opposed to
 * the plan to take it. `MedicationAdministration` would be the precise resource for the act of
 * swallowing a tablet, but Health Connect does not accept that type, and a completed statement
 * with a single `effectiveDateTime` says the same thing in a type it does accept.
 *
 * `basedOn` points at the MedicationRequest it fulfils, which is what
 * ties a dose to its regimen without needing an extension to tell the two apart.
 */
internal fun doseEventJson(event: DoseEvent, medication: MedicationEntry): String = buildJsonObject {
    put("resourceType", "MedicationStatement")
    put("id", event.fhirResourceId ?: event.id)
    put("status", "completed")
    putJsonArray("basedOn") {
        add(buildJsonObject { put("reference", "MedicationRequest/${medication.fhirId()}") })
    }
    putMedicationConcept(medication)
    putJsonObject("subject") { put("reference", "Patient/${FhirRecords.PATIENT_RESOURCE_ID}") }
    // A moment, not a period: this is one dose, not a course of them.
    put("effectiveDateTime", formatDateTime(event.takenAt))
    put("dateAsserted", formatDateTime(event.takenAt))
    medication.dosageText?.takeIf { it.isNotBlank() }?.let {
        putJsonArray("dosage") { add(buildJsonObject { put("text", it) }) }
    }
}.toString()

/**
 * A dose read back from a `MedicationStatement`.
 *
 * [DoseEvent.medicationId] holds the FHIR id of the MedicationRequest it is based on; the importer
 * resolves that to a local row. Only `completed` statements are doses — `not-taken`, `intended`
 * and the rest say something else, and recording them as taken would invent history.
 */
internal fun parseDoseEvent(data: String, dataSourceId: String): DoseEvent? {
    val root = parseObject(data) ?: return null
    if (root.string("resourceType") != "MedicationStatement" || root.string("status") != "completed") {
        return null
    }
    val id = root.string("id") ?: return null
    val takenAt = parseDateTime(root.string("effectiveDateTime")) ?: return null
    val requestId = root.basedOnRequestId() ?: return null

    return DoseEvent(
        id = id,
        medicationId = requestId,
        takenAt = takenAt,
        fhirResourceId = id,
        dataSourceId = dataSourceId,
    )
}

/** The id of the MedicationRequest in `basedOn`, from a relative or absolute reference. */
private fun JsonObject.basedOnRequestId(): String? =
    (this["basedOn"] as? JsonArray)
        ?.firstNotNullOfOrNull { (it as? JsonObject).string("reference") }
        ?.takeIf { "MedicationRequest/" in it }
        ?.substringAfterLast("MedicationRequest/")
        ?.substringBefore("/_history")
        ?.takeIf { it.isNotEmpty() }

/**
 * [MedicationStatus] as a `MedicationRequest.status`.
 *
 * A different value set from `MedicationStatement.status`, which is why this is not shared with
 * the dose events: a request can be `stopped`, a statement cannot.
 */
private fun requestStatusToFhir(status: MedicationStatus): String = when (status) {
    MedicationStatus.Active -> "active"
    MedicationStatus.Completed -> "completed"
    MedicationStatus.Stopped -> "stopped"
}

/** Null for `entered-in-error`: a retracted record is not a medication at all. */
private fun requestStatusFromFhir(status: String?): MedicationStatus? = when (status) {
    "entered-in-error" -> null
    "completed" -> MedicationStatus.Completed
    "stopped", "cancelled" -> MedicationStatus.Stopped
    else -> MedicationStatus.Active
}

private fun statementStatusFromFhir(status: String?): MedicationStatus? = when (status) {
    "entered-in-error" -> null
    "completed" -> MedicationStatus.Completed
    "stopped", "not-taken" -> MedicationStatus.Stopped
    else -> MedicationStatus.Active
}
