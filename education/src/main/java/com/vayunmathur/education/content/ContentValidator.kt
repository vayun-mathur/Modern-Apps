package com.vayunmathur.education.content

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

        val skillIds = pack.skills.map { it.id }
        val skillIdSet = skillIds.toSet()
        duplicates(skillIds).forEach { errors += "Duplicate skill id: $it" }

        val questionIds = pack.questions.map { it.id }
        val questionIdSet = questionIds.toSet()
        duplicates(questionIds).forEach { errors += "Duplicate question id: $it" }

        val courseIds = pack.courses.map { it.id }
        duplicates(courseIds).forEach { errors += "Duplicate course id: $it" }

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

        // Exercises: every referenced question resolves.
        for (course in pack.courses) {
            course.challenge?.let { errors += checkExercise(it, questionIdSet) }
            for (unit in course.units) {
                unit.quiz?.let { errors += checkExercise(it, questionIdSet) }
                for (lesson in unit.lessons) {
                    lesson.exercise?.let { errors += checkExercise(it, questionIdSet) }
                    for (v in lesson.videos) {
                        if (v.youtubeId.isBlank()) {
                            errors += "Lesson '${lesson.id}' has a video with a blank youtubeId"
                        }
                    }
                }
            }
        }

        return errors
    }

    private fun validateQuestionFields(q: Question): List<String> {
        val e = mutableListOf<String>()
        when (q) {
            is MultipleChoiceQuestion -> {
                if (q.choices.size < 2) e += "MC '${q.id}' needs >= 2 choices"
                if (q.correctIndex !in q.choices.indices) e += "MC '${q.id}' correctIndex out of range"
            }
            is MultipleSelectQuestion -> {
                if (q.choices.size < 2) e += "MS '${q.id}' needs >= 2 choices"
                if (q.correctIndices.isEmpty()) e += "MS '${q.id}' has no correct answers"
                if (q.correctIndices.any { it !in q.choices.indices }) e += "MS '${q.id}' correctIndices out of range"
            }
            is NumericQuestion -> {
                if (q.tolerance < 0) e += "Numeric '${q.id}' has negative tolerance"
            }
            is ShortTextQuestion -> {
                if (q.acceptedAnswers.isEmpty()) e += "ShortText '${q.id}' has no accepted answers"
            }
            is OrderingQuestion -> {
                if (q.items.size < 2) e += "Ordering '${q.id}' needs >= 2 items"
            }
            is MatchingQuestion -> {
                if (q.left.size != q.correctRightForLeft.size) {
                    e += "Matching '${q.id}' left/answer length mismatch"
                }
                if (q.correctRightForLeft.any { it !in q.right.indices }) {
                    e += "Matching '${q.id}' answer index out of range"
                }
            }
            is TracingQuestion -> {
                if (q.glyph.isBlank()) e += "Tracing '${q.id}' has an empty glyph"
            }
        }
        return e
    }

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
