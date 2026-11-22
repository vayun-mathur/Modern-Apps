package com.vayunmathur.camera.platform

import androidx.exifinterface.media.ExifInterface

/** Lookup tables backing [CameraViewModel]'s companion constants. */

internal val EXPOSURE_TIME_STOPS_TABLE = listOf(
    ExposureTimeStop("Auto", null),
    ExposureTimeStop("1/4000s", 250_000L),
    ExposureTimeStop("1/2000s", 500_000L),
    ExposureTimeStop("1/1000s", 1_000_000L),
    ExposureTimeStop("1/500s", 2_000_000L),
    ExposureTimeStop("1/250s", 4_000_000L),
    ExposureTimeStop("1/125s", 8_000_000L),
    ExposureTimeStop("1/60s", 16_666_667L),
    ExposureTimeStop("1/30s", 33_333_333L),
    ExposureTimeStop("1/15s", 66_666_667L),
    ExposureTimeStop("1/8s", 125_000_000L),
    ExposureTimeStop("1/4s", 250_000_000L),
    ExposureTimeStop("1/2s", 500_000_000L),
    ExposureTimeStop("1s", 1_000_000_000L),
    ExposureTimeStop("2s", 2_000_000_000L),
    ExposureTimeStop("4s", 4_000_000_000L),
)

internal val EXIF_TAGS_TO_COPY_TABLE = listOf(
    ExifInterface.TAG_DATETIME,
    ExifInterface.TAG_DATETIME_ORIGINAL,
    ExifInterface.TAG_DATETIME_DIGITIZED,
    ExifInterface.TAG_OFFSET_TIME,
    ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
    ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
    ExifInterface.TAG_SUBSEC_TIME,
    ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
    ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
    ExifInterface.TAG_MAKE,
    ExifInterface.TAG_MODEL,
    ExifInterface.TAG_SOFTWARE,
    ExifInterface.TAG_F_NUMBER,
    ExifInterface.TAG_EXPOSURE_TIME,
    ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
    ExifInterface.TAG_FOCAL_LENGTH,
    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
    ExifInterface.TAG_APERTURE_VALUE,
    ExifInterface.TAG_SHUTTER_SPEED_VALUE,
    ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
    ExifInterface.TAG_MAX_APERTURE_VALUE,
    ExifInterface.TAG_METERING_MODE,
    ExifInterface.TAG_EXPOSURE_PROGRAM,
    ExifInterface.TAG_EXPOSURE_MODE,
    ExifInterface.TAG_WHITE_BALANCE,
    ExifInterface.TAG_LIGHT_SOURCE,
    ExifInterface.TAG_FLASH,
    ExifInterface.TAG_SCENE_CAPTURE_TYPE,
    ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
)
