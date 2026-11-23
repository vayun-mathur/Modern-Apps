package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MotionPhotoEligibilityTest {

    private val eligible = MotionPhotoInputs(
        enabled = true,
        plainPhoto = true,
        noBake = true,
        avifPrecludes = false,
        nativeFourThree = true,
    )

    @Test
    fun onKeepsPreviousEligibleCase() {
        assertTrue(MotionPhotoEligibility.isEligible(eligible))
    }

    @Test
    fun offNeverEligibleEvenWhenEverythingElseAllows() {
        // 4:3 + JPEG (no AVIF preclusion) + no bake + plain photo, length
        // irrelevant here: off alone must veto.
        assertFalse(MotionPhotoEligibility.isEligible(eligible.copy(enabled = false)))
    }

    @Test
    fun onPreviousMatrixUnchanged() {
        assertFalse(MotionPhotoEligibility.isEligible(eligible.copy(plainPhoto = false)))
        assertFalse(MotionPhotoEligibility.isEligible(eligible.copy(noBake = false)))
        assertFalse(MotionPhotoEligibility.isEligible(eligible.copy(avifPrecludes = true)))
        assertFalse(MotionPhotoEligibility.isEligible(eligible.copy(nativeFourThree = false)))
    }

    @Test
    fun offVetoesEveryOtherCombination() {
        for (plain in listOf(true, false)) {
            for (bake in listOf(true, false)) {
                for (avif in listOf(true, false)) {
                    for (ratio in listOf(true, false)) {
                        assertFalse(
                            MotionPhotoEligibility.isEligible(
                                MotionPhotoInputs(
                                    enabled = false, plainPhoto = plain, noBake = bake,
                                    avifPrecludes = avif, nativeFourThree = ratio,
                                )
                            ),
                            "off must veto plain=$plain bake=$bake avif=$avif ratio=$ratio",
                        )
                    }
                }
            }
        }
    }
}
