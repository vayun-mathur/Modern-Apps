package com.vayunmathur.education.platform

import com.vayunmathur.education.domain.Answer
import com.vayunmathur.education.domain.Exercise
import com.vayunmathur.education.domain.ModuleType
import com.vayunmathur.education.domain.Question
import com.vayunmathur.education.domain.Subject

/**
 * The UI contract between [EducationViewModel] plus the back stack and the handful of
 * screens the store listing is captured from.
 *
 * Those screens take a state value and an actions interface rather than the ViewModel, so
 * they can be rendered by a `@Preview` — see `src/screenshotTest`, which is where the
 * listing images come from. There is no content pack, no database and no narrator there,
 * which is exactly what makes the images reproducible from a clean checkout.
 *
 * Only the catalog, a course, and the two quiz shells are split this way. Every other
 * screen still takes the ViewModel directly; splitting all eighteen of them would be a
 * large change for no benefit.
 *
 * It lives in `util` rather than `ui` so the dependency runs one way: `ui` depends on
 * `util`, and the ViewModel never depends on the screens.
 */

/** A course as the catalog lists it — the card shows nothing but these three fields. */
data class HomeCourse(val id: String, val title: String, val unitCount: Int)

/** One subject's slice of the catalog. */
data class HomeSection(val subject: Subject, val courses: List<HomeCourse>)

/** A parent-set deadline, already resolved to the title of the module it points at. */
data class HomeDeadline(
    val id: Long,
    val title: String,
    val dueEpochDay: Long,
    val moduleType: ModuleType?,
    val moduleId: String,
)

/** Everything the catalog draws. */
data class HomeUiState(
    val learnerName: String = "",
    val streakCount: Int = 0,
    val totalStars: Int = 0,
    val deadlines: List<HomeDeadline> = emptyList(),
    val sections: List<HomeSection> = emptyList(),
)

/**
 * Catalog callbacks, all navigation. Every method has a no-op default so a preview can
 * render the screen without supplying behaviour — [Noop] is the whole implementation a
 * preview needs.
 */
interface HomeActions {
    fun openBadges() {}
    fun openParentArea() {}
    fun openCourse(courseId: String) {}
    fun openDeadline(deadline: HomeDeadline) {}

    companion object {
        val Noop: HomeActions = object : HomeActions {}
    }
}

/** One unit row on the course screen, with its mastery already averaged. */
data class CourseUnitRow(
    val id: String,
    val title: String,
    val lessonCount: Int,
    val stars: Int,
    val dueEpochDay: Long? = null,
)

/** Everything the course screen draws. */
data class CourseUiState(
    val title: String = "",
    val description: String = "",
    /** False once the course id stops resolving, e.g. after a content pack is removed. */
    val available: Boolean = true,
    val units: List<CourseUnitRow> = emptyList(),
    val challenge: Exercise? = null,
    /**
     * Morphs the title out of the course card that opened this screen. Null in a preview and
     * whenever the course id no longer resolves, which has no card to have come from.
     */
    val titleSharedKey: Any? = null,
)

/** Course callbacks. Same no-op-default arrangement as [HomeActions]. */
interface CourseActions {
    fun navigateUp() {}
    fun openUnit(unitId: String) {}
    fun openExercise(exerciseId: String) {}

    companion object {
        val Noop: CourseActions = object : CourseActions {}
    }
}

/**
 * Everything a quiz shell draws. Shared by the Scholar quiz and the K-2 quiz — the K-2
 * shell has no title bar text, so it ignores [title].
 */
data class QuizUiState(
    val title: String = "",
    val questions: List<Question> = emptyList(),
)

/**
 * Quiz callbacks. Grading is pure, but persisting the result and choosing where to go next
 * differ per shell (Scholar goes to results, K-2 to the reward screen), so both are behind
 * the single [finish] call.
 */
interface QuizActions {
    fun navigateUp() {}
    fun finish(questions: List<Question>, answers: Map<String, Answer?>) {}

    companion object {
        val Noop: QuizActions = object : QuizActions {}
    }
}
