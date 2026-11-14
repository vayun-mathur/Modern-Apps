package com.vayunmathur.games.hub.data.entities

import kotlinx.serialization.Serializable

/**
 * Portable face description for a Yuu avatar, stored as JSON in [YuuEntity.configJson].
 *
 * Indices are positional (0..count-1 per part) so the renderer owns the variant lookup;
 * colors are ARGB longs into the fixed palettes below; tweaks are normalized 0..1 floats.
 * Only this config is persisted, keeping it portable to the future 3D games.
 *
 * [poseId] selects a 3D preset pose (see `YuuPose`); null means the idle pose.
 * It travels inside this JSON so no Room migration is needed for poses.
 */
@Serializable
data class YuuFaceConfig(
    val faceShape: Int = 0,
    val skinTone: Long = YUU_SKIN_TONES[DEFAULT_SKIN_TONE_INDEX],
    val earSize: Float = 0.5f,
    val bodyHeight: Float = 0.5f,
    val bodyWeight: Float = 0.5f,
    val hairStyle: Int = 0,
    val hairColor: Long = YUU_HAIR_COLORS[0],
    val eyeStyle: Int = 0,
    val eyeColor: Long = YUU_EYE_COLORS[0],
    val eyeSize: Float = 0.5f,
    val eyeSpacing: Float = 0.5f,
    val eyeHeight: Float = 0.5f,
    val eyeRotation: Float = 0.5f,
    val browStyle: Int = 0,
    val browColor: Long = YUU_HAIR_COLORS[0],
    val browHeight: Float = 0.5f,
    val browRotation: Float = 0.5f,
    val noseStyle: Int = 0,
    val noseHeight: Float = 0.5f,
    val mouthStyle: Int = 0,
    val mouthColor: Long = YUU_MOUTH_COLORS[0],
    val mouthSize: Float = 0.5f,
    val mouthHeight: Float = 0.5f,
    val glassesStyle: Int = 0,
    val beardStyle: Int = 0,
    val moleStyle: Int = 0,
    val wrinkleStyle: Int = 0,
    val blushStyle: Int = 0,
    val poseId: String? = null,
) {
    /** Shared constants and helpers for YuuFaceConfig. */
    companion object {
        /** Variant counts the Canvas renderer supports for v1 (Mii-like breadth). */
        const val DEFAULT_SKIN_TONE_INDEX = 3
        const val FACE_SHAPES = 8
        const val HAIR_STYLES = 12
        const val EYE_STYLES = 12
        const val BROW_STYLES = 8
        const val NOSE_STYLES = 8
        const val MOUTH_STYLES = 12
        const val GLASSES_STYLES = 6
        const val BEARD_STYLES = 5
        const val MOLE_STYLES = 4
        const val WRINKLE_STYLES = 4
        const val BLUSH_STYLES = 3

        /** 0 means "none" for the optional extras; face parts start at real variants. */
        const val NONE = 0

        val DEFAULT = YuuFaceConfig()
    }
}

/** Fixed Mii-like skin tones (ARGB). No in-app color picker in v1. */
val YUU_SKIN_TONES = listOf(
    0xFFFFE3D3L,
    0xFFFFD7B8L,
    0xFFF5C99BL,
    0xFFE8B48AL,
    0xFFD69E73L,
    0xFFB07B4FL,
    0xFF7D5233L,
    0xFF4E2F1DL,
)

/** Fixed hair / brow colors (ARGB). */
val YUU_HAIR_COLORS = listOf(
    0xFF2B2B2BL,
    0xFF5A3A22L,
    0xFF8A5A2BL,
    0xFFD9A441L,
    0xFFB34A2BL,
    0xFF7A7A7AL,
    0xFFE8E8E8L,
    0xFF4A6FA5L,
)

/** Fixed eye colors (ARGB). */
val YUU_EYE_COLORS = listOf(
    0xFF2B2B2BL,
    0xFF5A3A22L,
    0xFF4A6FA5L,
    0xFF3E8E5AL,
    0xFF7A7A7AL,
)

/** Fixed mouth / blush colors (ARGB). */
val YUU_MOUTH_COLORS = listOf(
    0xFFB34A4AL,
    0xFFD97B7BL,
    0xFF8E3B3BL,
    0xFF5A2B2BL,
)
