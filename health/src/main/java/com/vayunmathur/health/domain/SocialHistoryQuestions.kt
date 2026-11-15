package com.vayunmathur.health.domain

import com.vayunmathur.health.R

/**
 * The social history questions the app asks, with the codes FHIR wants for each answer.
 *
 * Every code here was taken from the LOINC 2.83 release rather than recalled: the question codes
 * from `Loinc.csv` and the LA answer codes from the release's own `AnswerList.csv`, using the answer
 * list LOINC marks NORMATIVE for that question. Getting a concept id wrong in a medical record is
 * not a cosmetic bug, so none of these should be edited without checking them against the release
 * the same way — `analysis/loinc_answers.py` does exactly that.
 *
 * Deliberately a data table rather than more columns on the profile. C-CDA's Social History
 * Observation covers occupation, industry, lifestyle and environmental risk factors, so this list
 * will grow; a table grows by one entry, whereas the fixed-column shape would have needed three
 * more columns and a migration each time.
 *
 * Smoking and pregnancy are *not* here. Both have their own US Core profiles bound to SNOMED rather
 * than to a LOINC answer list, so they stay as their own fields — see `HealthProfile`.
 */
object SocialHistoryQuestions {

    /**
     * One answer option.
     *
     * [display] is the canonical English from LOINC and goes into the FHIR resource; [labelRes] is
     * what the user sees and is translatable. They are deliberately separate — the record should say
     * the same thing regardless of the phone's language.
     */
    data class Answer(val code: String, val display: String, val labelRes: Int)

    /** Immutable value describing question. */
    data class Question(
        /** LOINC code for the question itself, used as `Observation.code`. */
        val loinc: String,
        /** Canonical English name of the question, for `Observation.code.display`. */
        val display: String,
        /**
         * The question, in full, as the instrument asks it.
         *
         * Not a topic and not a paraphrase. These are validated screening instruments whose wording
         * is the measurement: drop "within the past 12 months" or "before we got money to buy more"
         * and the answer no longer means what the code says it means. It is shown as a wrapping row
         * title rather than a field label for the same reason — there has to be room for all of it.
         */
        val titleRes: Int,
        val answers: List<Answer>,
    )

    /**
     * LOINC list LL5890-0, shared by both Hunger Vital Sign questions.
     *
     * "Often true" is the instrument's own answer and reads correctly because the statement it
     * agrees with is on screen above it. Rewriting these to be self-describing was an attempt to
     * compensate for having hidden the question, which was the actual mistake.
     */
    private val HUNGER = listOf(
        Answer("LA28397-0", "Often true", R.string.hvs_often_true),
        Answer("LA6729-3", "Sometimes true", R.string.hvs_sometimes_true),
        Answer("LA28398-8", "Never true", R.string.hvs_never_true),
    )

    val ALCOHOL = Question(
        loinc = "68518-0",
        display = "How often do you have a drink containing alcohol",
        titleRes = R.string.social_alcohol,
        // LL2179-1. Its own scale, not the shared one - AUDIT-C asks about frequency of any
        // drinking rather than counting occasions.
        answers = listOf(
            Answer("LA6270-8", "Never", R.string.freq_never),
            Answer("LA18926-8", "Monthly or less", R.string.audit_monthly_or_less),
            Answer("LA18927-6", "2-4 times a month", R.string.audit_2_4_month),
            Answer("LA18928-4", "2-3 times a week", R.string.audit_2_3_week),
            Answer("LA18929-2", "4 or more times a week", R.string.audit_4_plus_week),
        ),
    )

    val DRUGS = Question(
        loinc = "68524-8",
        display = "How many times in the past year have you used an illegal drug or used a " +
            "prescription medication for non-medical reasons",
        titleRes = R.string.social_drugs,
        answers = listOf(
            Answer("LA6270-8", "Never", R.string.freq_never),
            Answer("LA26460-8", "Once or twice", R.string.freq_once_or_twice),
            Answer("LA18876-5", "Monthly", R.string.freq_monthly),
            Answer("LA18891-4", "Weekly", R.string.freq_weekly),
            Answer("LA18934-2", "Daily or almost daily", R.string.freq_daily),
        ),
    )

    val HOUSING = Question(
        loinc = "71802-3",
        display = "Housing status",
        titleRes = R.string.social_housing,
        // LL5876-9, the three-answer PRAPARE-style list rather than the older five-answer one,
        // which was written for a clinician describing a patient ("Patient is homeless").
        answers = listOf(
            Answer("LA31993-1", "I have a steady place to live", R.string.housing_steady),
            Answer(
                "LA31994-9",
                "I have a place to live today, but I am worried about losing it in the future",
                R.string.housing_worried,
            ),
            Answer("LA31995-6", "I do not have a steady place to live", R.string.housing_none),
        ),
    )

    val FOOD_WORRIED = Question(
        loinc = "88122-7",
        display = "Within the past 12 months we worried whether our food would run out before we " +
            "got money to buy more",
        titleRes = R.string.social_food_worried,
        answers = HUNGER,
    )

    val FOOD_RAN_OUT = Question(
        loinc = "88123-5",
        display = "Within the past 12 months the food we bought just didn't last and we didn't " +
            "have money to get more",
        titleRes = R.string.social_food_ran_out,
        answers = HUNGER,
    )

    val TRANSPORT = Question(
        loinc = "93030-5",
        display = "Has lack of transportation kept you from medical appointments, meetings, work, " +
            "or from getting things needed for daily living",
        titleRes = R.string.social_transport,
        // LL5336-4. "I choose not to answer" is kept: declining is a real answer and the record
        // should be able to say so rather than leaving it indistinguishable from never asked.
        answers = listOf(
            Answer(
                "LA30133-5",
                "Yes, it has kept me from medical appointments or from getting my medications",
                R.string.transport_medical,
            ),
            Answer(
                "LA30134-3",
                "Yes, it has kept me from non-medical meetings, appointments, work, " +
                    "or from getting things that I need",
                R.string.transport_non_medical,
            ),
            Answer("LA32-8", "No", R.string.transport_no),
            Answer("LA30122-8", "I choose not to answer this question", R.string.decline_to_answer),
        ),
    )

    /** Habits. Grouped only so the page reads as two short lists rather than one long one. */
    val LIFESTYLE = listOf(ALCOHOL, DRUGS)

    /** The social determinants: where you live, whether you can eat, whether you can travel. */
    val CIRCUMSTANCES = listOf(HOUSING, FOOD_WORRIED, FOOD_RAN_OUT, TRANSPORT)

    val ALL = LIFESTYLE + CIRCUMSTANCES

    fun byLoinc(code: String): Question? = ALL.firstOrNull { it.loinc == code }
}
