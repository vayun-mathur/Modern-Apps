package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashModePolicyTest {

    @Test
    fun frontWithoutFlashUnitArmsScreenFlash() {
        assertTrue(FlashModePolicy.usesScreenFlash(LensFacing.FRONT, hasFlashUnit = false))
        assertEquals(
            FlashResolution.SCREEN,
            FlashModePolicy.resolve(LensFacing.FRONT, hasFlashUnit = false, FlashSetting.ON)
        )
        assertEquals(
            FlashResolution.SCREEN,
            FlashModePolicy.resolve(LensFacing.FRONT, hasFlashUnit = false, FlashSetting.AUTO)
        )
    }

    @Test
    fun frontWithoutFlashUnitOffStaysDark() {
        assertEquals(
            FlashResolution.NONE,
            FlashModePolicy.resolve(LensFacing.FRONT, hasFlashUnit = false, FlashSetting.OFF)
        )
    }

    @Test
    fun backWithFlashUnitKeepsHardwareFlash() {
        assertFalse(FlashModePolicy.usesScreenFlash(LensFacing.BACK, hasFlashUnit = true))
        assertEquals(
            FlashResolution.HARDWARE,
            FlashModePolicy.resolve(LensFacing.BACK, hasFlashUnit = true, FlashSetting.ON)
        )
        assertEquals(
            FlashResolution.HARDWARE,
            FlashModePolicy.resolve(LensFacing.BACK, hasFlashUnit = true, FlashSetting.AUTO)
        )
        assertEquals(
            FlashResolution.NONE,
            FlashModePolicy.resolve(LensFacing.BACK, hasFlashUnit = true, FlashSetting.OFF)
        )
    }

    @Test
    fun frontWithFlashUnitKeepsHardwareFlash() {
        assertFalse(FlashModePolicy.usesScreenFlash(LensFacing.FRONT, hasFlashUnit = true))
        assertEquals(
            FlashResolution.HARDWARE,
            FlashModePolicy.resolve(LensFacing.FRONT, hasFlashUnit = true, FlashSetting.ON)
        )
    }

    @Test
    fun backWithoutFlashUnitNeverUsesScreenFlash() {
        // CameraX rejects FLASH_MODE_SCREEN on a back camera, so no unit = no flash.
        assertFalse(FlashModePolicy.usesScreenFlash(LensFacing.BACK, hasFlashUnit = false))
        assertEquals(
            FlashResolution.HARDWARE,
            FlashModePolicy.resolve(LensFacing.BACK, hasFlashUnit = false, FlashSetting.ON)
        )
    }

    @Test
    fun flashControlShownWhenEitherFlashCanFire() {
        assertTrue(FlashModePolicy.showFlashControl(LensFacing.BACK, hasFlashUnit = true))
        assertTrue(FlashModePolicy.showFlashControl(LensFacing.FRONT, hasFlashUnit = true))
        assertTrue(FlashModePolicy.showFlashControl(LensFacing.FRONT, hasFlashUnit = false))
        assertFalse(FlashModePolicy.showFlashControl(LensFacing.BACK, hasFlashUnit = false))
    }
}
