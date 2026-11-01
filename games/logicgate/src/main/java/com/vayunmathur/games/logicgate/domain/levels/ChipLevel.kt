package com.vayunmathur.games.logicgate.domain.levels

import com.vayunmathur.games.logicgate.domain.sim.TestSpec

/** A level whose pins, tests and par all come from the chip it rewards, so they cannot drift apart. */
internal fun chipLevel(
    chapter: Chapter,
    id: String,
    chipId: String,
    text: LevelText,
    title: RowTitle? = null,
    unlocks: List<String> = listOf(chipId),
    tests: TestSpec = TestGen.fromChip(chipId, checkNotNull(title) { "$id needs row titles" }),
): LevelDef {
    val spec = TestGen.chip(chipId)
    return LevelDef(
        id = id,
        chapter = chapter,
        title = text.title,
        brief = text.brief,
        hint = text.hint,
        inputs = spec.inputs,
        outputs = spec.outputs,
        tests = tests,
        par = spec.nandCost,
        unlocks = unlocks,
    )
}

/** The words of a level. */
internal data class LevelText(val title: String, val brief: String, val hint: String)
