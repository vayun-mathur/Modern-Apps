package com.vayunmathur.education.content

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json

/**
 * In-memory index over all loaded [ContentPack]s. The content spine is uniform
 * across bands; this repository is what every band shell reads from.
 *
 * Construct via [load] (reads bundled JSON packs from `assets/content/`).
 */
class ContentRepository(val packs: List<ContentPack>) {

    val questions: Map<String, Question> =
        packs.flatMap { it.questions }.associateBy { it.id }

    val skills: Map<String, Skill> =
        packs.flatMap { it.skills }.associateBy { it.id }

    val courses: List<Course> = packs.flatMap { it.courses }

    val coursesById: Map<String, Course> = courses.associateBy { it.id }

    val units: Map<String, CourseUnit> =
        courses.flatMap { it.units }.associateBy { it.id }

    val lessons: Map<String, Lesson> =
        courses.flatMap { c -> c.units.flatMap { it.lessons } }.associateBy { it.id }

    /** Every authored exercise (lesson exercises + unit quizzes + course challenges) by id. */
    val exercises: Map<String, Exercise> = buildList {
        courses.forEach { c ->
            c.challenge?.let { add(it) }
            c.units.forEach { u ->
                u.quiz?.let { add(it) }
                u.lessons.forEach { l -> l.exercise?.let { add(it) } }
            }
        }
    }.associateBy { it.id }

    /** Subjects that actually have content, in a stable display order. */
    val subjects: List<Subject> =
        Subject.entries.filter { subj -> courses.any { it.subject == subj } }

    fun coursesForBand(band: Band): List<Course> =
        courses.filter { Grades.bandForGrade(it.gradeLevel) == band }

    fun coursesForSubject(subject: Subject, band: Band? = null): List<Course> =
        courses.filter { it.subject == subject && (band == null || Grades.bandForGrade(it.gradeLevel) == band) }

    fun course(id: String): Course? = coursesById[id]
    fun unit(id: String): CourseUnit? = units[id]
    fun lesson(id: String): Lesson? = lessons[id]
    fun exercise(id: String): Exercise? = exercises[id]

    /** The course a unit belongs to (deadlines/breadcrumbs need this). */
    fun courseOfUnit(unitId: String): Course? =
        courses.firstOrNull { c -> c.units.any { it.id == unitId } }

    /** Resolves an exercise's question-id references, preserving order. */
    fun questionsFor(exercise: Exercise): List<Question> =
        exercise.questionIds.mapNotNull { questions[it] }

    /** Distinct skills exercised by an exercise. */
    fun skillIdsOf(exercise: Exercise): List<String> =
        questionsFor(exercise).map { it.skillId }.distinct()

    /** Distinct skills across all a unit's lesson exercises + unit quiz. */
    fun skillIdsOfUnit(unit: CourseUnit): List<String> {
        val exercises = unit.lessons.mapNotNull { it.exercise } + listOfNotNull(unit.quiz)
        return exercises.flatMap { skillIdsOf(it) }.distinct()
    }

    /** Distinct skills across a whole course. */
    fun skillIdsOfCourse(course: Course): List<String> =
        course.units.flatMap { skillIdsOfUnit(it) }.distinct()

    fun moduleTitle(type: ModuleType, id: String): String? = when (type) {
        ModuleType.COURSE -> course(id)?.title
        ModuleType.UNIT -> unit(id)?.title
        ModuleType.LESSON -> lesson(id)?.title
    }

    companion object {
        private const val TAG = "ContentRepository"
        private const val ASSET_DIR = "content"

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * Reads and parses every `*.json` file under `assets/content/`. Malformed
         * packs are logged and skipped so one bad pack can't break the app.
         */
        fun load(context: Context): ContentRepository {
            val assets = context.assets
            val files = try {
                assets.list(ASSET_DIR)?.filter { it.endsWith(".json") } ?: emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to list content assets", e)
                emptyList()
            }

            val packs = files.mapNotNull { name ->
                try {
                    val text = assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }
                    json.decodeFromString<ContentPack>(text)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse content pack: $name", e)
                    null
                }
            }
            return ContentRepository(packs)
        }
    }
}
