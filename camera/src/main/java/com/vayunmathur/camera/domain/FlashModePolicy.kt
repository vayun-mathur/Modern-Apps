package com.vayunmathur.camera.domain

/** User-selected still-flash behaviour, mirrored from the platform `FlashMode`. */
enum class FlashSetting { OFF, ON, AUTO }

/**
 * How a still capture is actually illuminated once the lens is known.
 *
 * [HARDWARE] drives the LED flash unit; [SCREEN] lights the display instead
 * (CameraX `FLASH_MODE_SCREEN` + a `ScreenFlash`); [NONE] fires no flash.
 */
enum class FlashResolution { NONE, HARDWARE, SCREEN }

/**
 * Resolves the user's flash setting against the bound lens.
 *
 * A front lens with no flash unit has no LED to drive, so ON/AUTO fall back
 * to the screen flash instead of silently capturing dark. A lens with a
 * real unit (the usual back camera) always keeps the hardware flash, and
 * OFF never illuminates on either facing. Screen flash is front-only:
 * CameraX rejects `FLASH_MODE_SCREEN` for a back camera.
 */
object FlashModePolicy {

    /** True when this lens can only flash via the screen (front, no LED unit). */
    fun usesScreenFlash(facing: LensFacing, hasFlashUnit: Boolean): Boolean =
        facing == LensFacing.FRONT && !hasFlashUnit

    /** The flash control is worth showing when either flash kind can fire. */
    fun showFlashControl(facing: LensFacing, hasFlashUnit: Boolean): Boolean =
        hasFlashUnit || usesScreenFlash(facing, hasFlashUnit)

    /** Maps the user setting + lens to the illumination a capture must use. */
    fun resolve(facing: LensFacing, hasFlashUnit: Boolean, setting: FlashSetting): FlashResolution =
        when (setting) {
            FlashSetting.OFF -> FlashResolution.NONE
            FlashSetting.ON, FlashSetting.AUTO -> if (usesScreenFlash(facing, hasFlashUnit)) {
                FlashResolution.SCREEN
            } else {
                FlashResolution.HARDWARE
            }
        }
}
