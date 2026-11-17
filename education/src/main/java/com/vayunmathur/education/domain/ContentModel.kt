package com.vayunmathur.education.domain

import androidx.annotation.StringRes
import com.vayunmathur.education.R
import kotlinx.serialization.Serializable

/**
 * The three experience bands. The content spine is band-agnostic; a learner's
 * band selects which UI "shell" renders the same content.
 *
 *  - [K2]         K-2   · "Guided Playground" (audio-first, pre/early readers)
 *  - [ELEMENTARY] 3-5   · "Explorer"          (developing readers)
 *  - [SCHOLAR]    6-12  · "Scholar"           (self-directed)
 */
@Serializable
enum class Band { K2, ELEMENTARY, SCHOLAR }

/** Grade-level helpers. Grade 0 == Kindergarten. */
object Grades {
    const val KINDERGARTEN = 0
    const val MAX = 12

    /** Highest grade in the K-2 band. */
    const val K2_MAX = 2

    /** Highest grade in the elementary band. */
    const val ELEMENTARY_MAX = 5
    fun bandForGrade(grade: Int): Band = when {
        grade <= K2_MAX -> Band.K2
        grade <= ELEMENTARY_MAX -> Band.ELEMENTARY
        else -> Band.SCHOLAR
    }
    val all: List<Int> = (KINDERGARTEN..MAX).toList()
}

/** Top-level curriculum subjects, mirroring Khan Academy's taxonomy. */
@Serializable
enum class Subject(@StringRes val displayNameRes: Int) {
    MATH(R.string.subject_math),
    SCIENCE(R.string.subject_science),
    READING(R.string.subject_reading),
    SOCIAL_STUDIES(R.string.subject_social_studies),
    COMPUTING(R.string.subject_computing),
}

/**
 * The atomic unit that stars/progress attach to. Every [Question] is tagged
 * with a [Skill] id.
 */
@Serializable
data class Skill(
    val id: String,
    val subject: Subject,
    val name: String,
)

/**
 * A course (e.g. "2nd grade math", "Algebra 1"). Mirrors Khan's course/unit
 * taxonomy. [gradeLevel] is the nominal grade this course targets.
 */
@Serializable
data class Course(
    val id: String,
    val subject: Subject,
    val title: String,
    val description: String = "",
    val gradeLevel: Int,
    val units: List<CourseUnit>,
    /** Authored end-of-course challenge (older bands only). */
    val challenge: Exercise? = null,
)

/**
 * A unit within a course. Named [CourseUnit] to avoid clashing with Kotlin's
 * [Unit] type.
 */
@Serializable
data class CourseUnit(
    val id: String,
    val title: String,
    val description: String = "",
    val lessons: List<Lesson>,
    /** Authored end-of-unit quiz. */
    val quiz: Exercise? = null,
)

/** A lesson: reference video(s) plus an authored exercise. */
@Serializable
data class Lesson(
    val id: String,
    val title: String,
    val videos: List<VideoRef> = emptyList(),
    val exercise: Exercise? = null,
)

/** Reference to a lesson video streamed from the self-hosted R2 mirror. */
@Serializable
data class VideoRef(
    val videoUrl: String,
    val title: String,
    val durationSeconds: Int = 0,
)

/**
 * An authored quiz: an ordered set of question ids resolving into
 * [ContentPack.questions]. Referencing by id lets a question be reused across
 * exercises and keeps the question a first-class, skill-tagged unit.
 */
@Serializable
data class Exercise(
    val id: String,
    val title: String = "",
    val questionIds: List<String>,
)

/**
 * A data-driven content pack — the unit of authoring/distribution. Bundled in
 * assets initially; fetchable over the network later. Open-source contributors
 * add packs without touching app code.
 */
@Serializable
data class ContentPack(
    val id: String,
    val name: String,
    val version: Int = 1,
    val skills: List<Skill> = emptyList(),
    val courses: List<Course> = emptyList(),
    val questions: List<Question> = emptyList(),
)

/**
 * Stable identifier + kind for a "module" a parent can attach a deadline to.
 * Kept as a string type tag + id so deadlines survive content-pack updates.
 */
enum class ModuleType { COURSE, UNIT, LESSON }
