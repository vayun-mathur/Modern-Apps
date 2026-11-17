package com.vayunmathur.education.domain

/**
 * Validates that a [ContentPack] is well-formed so contributed packs fail loudly
 * rather than rendering incorrectly. Returns a list of human-readable errors
 * (empty == valid).
 *
 * Intended for a build-time / CI check and an in-app debug assertion.
 */
object ContentValidator {

    fun validate(pack: ContentPack): List<String> {
        val errors = mutableListOf<String>()

        errors += checkDuplicateIds(pack)

        val skillIdSet = pack.skills.map { it.id }.toSet()
        val questionIdSet = pack.questions.map { it.id }.toSet()

        errors += checkQuestions(pack, skillIdSet)
        errors += checkCourses(pack, questionIdSet)

        return errors
    }

    private fun checkDuplicateIds(pack: ContentPack): List<String> {
        val errors = mutableListOf<String>()
        duplicates(pack.skills.map { it.id }).forEach { errors += "Duplicate skill id: $it" }
        duplicates(pack.questions.map { it.id }).forEach { errors += "Duplicate question id: $it" }
        duplicates(pack.courses.map { it.id }).forEach { errors += "Duplicate course id: $it" }
        return errors
    }

    private fun checkQuestions(pack: ContentPack, skillIdSet: Set<String>): List<String> {
        val errors = mutableListOf<String>()
        // Questions: skill resolvable + per-type field checks.
        for (q in pack.questions) {
            if (q.skillId !in skillIdSet) {
                errors += "Question '${q.id}' references unknown skill '${q.skillId}'"
            }
            if (q.prompt.text.isBlank() && q.prompt.audioRef == null && q.prompt.imageRef == null) {
                errors += "Question '${q.id}' has an empty prompt"
            }
            errors += validateQuestionFields(q)
        }
        return errors
    }

    private fun checkCourses(pack: ContentPack, questionIdSet: Set<String>): List<String> {
        val errors = mutableListOf<String>()
        // Exercises: every referenced question resolves.
        for (course in pack.courses) {
            course.challenge?.let { errors += checkExercise(it, questionIdSet) }
            for (unit in course.units) {
                errors += checkUnit(unit, questionIdSet)
            }
        }
        return errors
    }

    private fun checkUnit(unit: CourseUnit, questionIdSet: Set<String>): List<String> {
        val errors = mutableListOf<String>()
        unit.quiz?.let { errors += checkExercise(it, questionIdSet) }
        for (lesson in unit.lessons) {
            lesson.exercise?.let { errors += checkExercise(it, questionIdSet) }
            for (v in lesson.videos) {
                if (v.videoUrl.isBlank()) {
                    errors += "Lesson '${lesson.id}' has a video with a blank videoUrl"
                }
            }
        }
        return errors
    }

    private fun validateQuestionFields(q: Question): List<String> = when (q) {
        is MultipleChoiceQuestion -> checkMultipleChoice(q)
        is MultipleSelectQuestion -> checkMultipleSelect(q)
        is NumericQuestion -> checkNumeric(q)
        is ShortTextQuestion -> checkShortText(q)
        is OrderingQuestion -> checkOrdering(q)
        is MatchingQuestion -> checkMatching(q)
        is TracingQuestion -> checkTracing(q)
    }

    private fun checkMultipleChoice(q: MultipleChoiceQuestion): List<String> {
        val e = mutableListOf<String>()
        if (q.choices.size < MIN_CHOICES) e += "MC '${q.id}' needs >= 2 choices"
        if (q.correctIndex !in q.choices.indices) e += "MC '${q.id}' correctIndex out of range"
        return e
    }

    private fun checkMultipleSelect(q: MultipleSelectQuestion): List<String> {
        val e = mutableListOf<String>()
        if (q.choices.size < MIN_CHOICES) e += "MS '${q.id}' needs >= 2 choices"
        if (q.correctIndices.isEmpty()) e += "MS '${q.id}' has no correct answers"
        if (q.correctIndices.any { it !in q.choices.indices }) {
            e += "MS '${q.id}' correctIndices out of range"
        }
        return e
    }

    private fun checkNumeric(q: NumericQuestion): List<String> =
        if (q.tolerance < 0) {
            listOf("Numeric '${q.id}' has negative tolerance")
        } else {
            emptyList()
        }

    private fun checkShortText(q: ShortTextQuestion): List<String> =
        if (q.acceptedAnswers.isEmpty()) {
            listOf("ShortText '${q.id}' has no accepted answers")
        } else {
            emptyList()
        }

    private fun checkOrdering(q: OrderingQuestion): List<String> =
        if (q.items.size < MIN_CHOICES) {
            listOf("Ordering '${q.id}' needs >= 2 items")
        } else {
            emptyList()
        }

    private fun checkMatching(q: MatchingQuestion): List<String> {
        val e = mutableListOf<String>()
        if (q.left.size != q.correctRightForLeft.size) {
            e += "Matching '${q.id}' left/answer length mismatch"
        }
        if (q.correctRightForLeft.any { it !in q.right.indices }) {
            e += "Matching '${q.id}' answer index out of range"
        }
        return e
    }

    private fun checkTracing(q: TracingQuestion): List<String> =
        if (q.glyph.isBlank()) {
            listOf("Tracing '${q.id}' has an empty glyph")
        } else {
            emptyList()
        }

    /** Minimum choices/items for a valid multi-option question. */
    private const val MIN_CHOICES = 2

    private fun checkExercise(exercise: Exercise, questionIds: Set<String>): List<String> {
        val e = mutableListOf<String>()
        if (exercise.questionIds.isEmpty()) e += "Exercise '${exercise.id}' has no questions"
        exercise.questionIds.filter { it !in questionIds }.forEach {
            e += "Exercise '${exercise.id}' references unknown question '$it'"
        }
        return e
    }

    private fun <T> duplicates(items: List<T>): List<T> =
        items.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.toList()
}
