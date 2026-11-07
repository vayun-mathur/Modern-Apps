package com.vayunmathur.education

import android.content.Context
import androidx.room3.Room
import com.vayunmathur.education.content.Choice
import com.vayunmathur.education.content.ContentPack
import com.vayunmathur.education.content.ContentRepository
import com.vayunmathur.education.content.Course
import com.vayunmathur.education.content.CourseUnit
import com.vayunmathur.education.content.Exercise
import com.vayunmathur.education.content.Lesson
import com.vayunmathur.education.content.MatchingQuestion
import com.vayunmathur.education.content.MultipleChoiceQuestion
import com.vayunmathur.education.content.MultipleSelectQuestion
import com.vayunmathur.education.content.NumericQuestion
import com.vayunmathur.education.content.OrderingQuestion
import com.vayunmathur.education.content.Prompt
import com.vayunmathur.education.content.ShortTextQuestion
import com.vayunmathur.education.content.Skill
import com.vayunmathur.education.content.Subject
import com.vayunmathur.education.content.TracingQuestion
import com.vayunmathur.education.content.VideoRef
import com.vayunmathur.education.data.EducationDatabase
import com.vayunmathur.education.data.EducationRepository
import com.vayunmathur.library.util.databases

/** Shared fixtures: one synthetic pack covering every band, subject and question type. */
object EducationFixtures {
    val mcq = MultipleChoiceQuestion(
        id = "q-mc", skillId = "count",
        prompt = Prompt("How many apples?", audioRef = "q-mc.mp3"),
        choices = listOf(Choice("1"), Choice("3"), Choice("7")),
        correctIndex = 1, hints = listOf("Count them one by one.", "More than two."),
        explanation = "There are three apples.",
    )
    val multiSelect = MultipleSelectQuestion(
        id = "q-ms", skillId = "count",
        prompt = Prompt("Pick the even numbers"),
        choices = listOf(Choice("2"), Choice("3"), Choice("4")),
        correctIndices = listOf(0, 2),
    )
    val numeric = NumericQuestion(
        id = "q-num", skillId = "add",
        prompt = Prompt("2 + 3 = ?"), answer = 5.0, tolerance = 0.1, unit = "apples",
    )
    val shortText = ShortTextQuestion(
        id = "q-text", skillId = "read",
        prompt = Prompt("First letter of the alphabet?"), acceptedAnswers = listOf("a", "A"),
    )
    val ordering = OrderingQuestion(
        id = "q-ord", skillId = "count",
        prompt = Prompt("Order smallest to largest"), items = listOf("1", "2", "3"),
    )
    val matching = MatchingQuestion(
        id = "q-match", skillId = "count",
        prompt = Prompt("Match the number to its word"),
        left = listOf("1", "2"), right = listOf("one", "two"), correctRightForLeft = listOf(0, 1),
    )
    val tracing = TracingQuestion(
        id = "q-trace", skillId = "write",
        prompt = Prompt("Trace the letter"), glyph = "A",
    )
    val allQuestions = listOf(mcq, multiSelect, numeric, shortText, ordering, matching, tracing)

    val exercise = Exercise("ex1", "Counting practice", allQuestions.map { it.id })
    val k2Exercise = Exercise("ex-k2", "Play time", listOf("q-mc", "q-trace", "q-match"))

    val lesson = Lesson(
        id = "l1", title = "Counting 1-3",
        videos = listOf(VideoRef("http://127.0.0.1:9/count.mp4", "Counting video", 125)),
        exercise = exercise,
    )
    val k2Lesson = Lesson(id = "l-k2", title = "Fun with numbers", videos = lesson.videos, exercise = k2Exercise)
    val unitQuiz = Exercise("quiz-u1", "Unit quiz", listOf("q-mc", "q-num"))
    val unit = CourseUnit("u1", "Numbers", "Counting basics", listOf(lesson), quiz = unitQuiz)
    val k2Unit = CourseUnit("u-k2", "Play numbers", lessons = listOf(k2Lesson))
    val challenge = Exercise("ch1", "Big challenge", listOf("q-mc"))

    val mathCourse = Course(
        id = "c-math", subject = Subject.MATH, title = "Math Fun", description = "Numbers",
        gradeLevel = 1, units = listOf(unit, k2Unit), challenge = challenge,
    )
    val readingCourse = Course(
        id = "c-read", subject = Subject.READING, title = "Reading Time",
        gradeLevel = 4, units = listOf(CourseUnit("u-read", "Stories", lessons = listOf(Lesson("l-read", "Story time")))),
    )
    val scholarCourse = Course(
        id = "c-scholar", subject = Subject.SCIENCE, title = "Science Lab",
        gradeLevel = 8, units = listOf(CourseUnit("u-sci", "Experiments", lessons = listOf(Lesson("l-sci", "Volcanoes")))),
    )

    val pack = ContentPack(
        id = "test-pack", name = "Test Pack",
        skills = listOf(Skill("count", Subject.MATH, "Counting"), Skill("add", Subject.MATH, "Adding"), Skill("read", Subject.READING, "Reading"), Skill("write", Subject.READING, "Writing")),
        courses = listOf(mathCourse, readingCourse, scholarCourse),
        questions = allQuestions,
    )

    fun content(): ContentRepository = ContentRepository(listOf(pack))

    /** Seeds the process-wide Room cache with an in-memory EducationDatabase (avoids SQLCipher on JVM). */
    fun seedDb(context: Context): EducationDatabase {
        val app = context.applicationContext
        synchronized(databases) {
            val existing = databases[EducationDatabase::class]
            if (existing is EducationDatabase) return existing
            val db = Room.inMemoryDatabaseBuilder(app, EducationDatabase::class.java)
                .allowMainThreadQueries().build()
            databases[EducationDatabase::class] = db
            return db
        }
    }

    fun repository(context: Context): EducationRepository {
        seedDb(context)
        return EducationRepository.get(context.applicationContext)
    }
}
