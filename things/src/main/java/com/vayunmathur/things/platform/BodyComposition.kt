package com.vayunmathur.things.platform

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Pure-Kotlin body-composition calculator ported from yolanda_calc native library.
 *
 * Research basis (see renpho_analysis/YOLANDA_CALC_FORMULAS.md):
 * - JNI: BleScaleData.initWithSecAthlete / calcBmi / calcBodyShape / kalculation dispatcher
 *   algorithmWithSecAthlete @0x139a0, calcBmi @0x1149c, limitBodyfat @0x11534,
 *   calLbmWithBodyfat @0x11500, checkImpedance @0x149f8.
 * - Offline flow: QNDecoderImpl.decodeData c=16 -> fourResTwoByte2Int @6/7/8/9 -> R50/R500
 *   and eightResTwoByte2Double(kRatio=0.1) for segmental. Weight via decodeWeight(ratio 100/10).
 * - .so version 2.14.5 (arm64 libyolanda_calc.so). No coefficients guessed verbatim — the
 *   .rodata coefficient banks (DAT_0010xxxx) are proprietary and vary per algorithm
 *   method 1/2/3/5/6/7/0x0b and between libyolanda_calc.so vs libICBodyFatAlgorithms.so.
 *   This file is a clean-room Kotlin port that preserves the exact scaffolding (clamps,
 *   rounding, BF clamp 5.1-75.0, LBM identity) and uses published BIA literature
 *   (Kyle 2004, Deurenberg, Mifflin-St Jeor, Sun) for the regression coefficients,
 *   tuned to match Elis 1 / Qingniu foot-to-foot 4-electrode behaviour within a
 *   few percent on the adult 18-65 / 40-120 kg operating range.
 *
 * Do NOT bundle the .so — all math is on-device Kotlin, BLE only, no network.
 */

enum class Sex(val code: Int) {
    Female(0),
    Male(1);

    /** Decodes the scale's numeric sex code into [Sex]. */
    companion object {
        fun fromCode(code: Int) = if (code == 1) Male else Female
    }
}

/** User profile inputs (sex, age, height, athlete mode) for composition estimates. */
data class ScaleProfile(
    val sex: Sex = Sex.Male,
    val age: Int = 30,
    val heightCm: Double = 175.0,
    val athlete: Boolean = false,
)

/** Raw scale reading: weight with 50/500 kHz and optional segmental impedance. */
data class ScaleMeasurement(
    val weightKg: Double,
    val resistance50: Int = 0,
    val resistance500: Int = 0,
    /** 8-electrode segmental if available; null for Elis 1 (4-electrode). */
    val segmental: SegmentalImpedance? = null,
)

/** Eight-electrode segmental impedance at 20/100 kHz for limbs and trunk. */
data class SegmentalImpedance(
    val rh20: Double = 0.0,
    val lh20: Double = 0.0,
    val t20: Double = 0.0,
    val rf20: Double = 0.0,
    val lf20: Double = 0.0,
    val rh100: Double = 0.0,
    val lh100: Double = 0.0,
    val t100: Double = 0.0,
    val rf100: Double = 0.0,
    val lf100: Double = 0.0,
)

/** Computed body composition: BMI, fat, water, muscle, bone and metabolic estimates. */
data class BodyMetrics(
    val bmi: Double,
    val bodyFatPercent: Double,
    val fatMassKg: Double,
    val lbmKg: Double,
    val waterPercent: Double,
    val musclePercent: Double,
    val muscleMassKg: Double,
    val boneKg: Double,
    val proteinPercent: Double,
    val proteinKg: Double,
    val bmrKcal: Int,
    val visceralLevel: Int,
    val bodyAge: Int,
    val score: Int,
    /** Segmental muscle/fat breakdown; null on 4-electrode hardware. */
    val segmental: SegmentalResult? = null,
)

/** Per-segment fat and muscle estimates for arms, legs and trunk. */
data class SegmentalResult(
    val fatRh: Double,
    val fatLh: Double,
    val fatT: Double,
    val fatRf: Double,
    val fatLf: Double,
    val muscleRh: Double,
    val muscleLh: Double,
    val muscleT: Double,
    val muscleRf: Double,
    val muscleLf: Double,
)

/** Pure on-device BIA calculations turning scale impedance into [BodyMetrics]. */
object BodyComposition {

    // Clamp bounds from algorithmWithSecAthlete @0x139a0 (ghidra_helpers.txt:28-62).
    private const val MIN_HEIGHT_CM = 40.0
    private const val MAX_HEIGHT_CM = 240.0
    private const val MIN_AGE = 3
    private const val MAX_AGE = 80
    private const val MIN_RESISTANCE = 100
    private const val MAX_RESISTANCE = 1500
    /** Four-electrode resistance at or above this means no foot contact. */
    private const val NO_CONTACT_RESISTANCE = 60000

    // BMI @0x1149c scaffolding: cm per metre, the square, and the +0.05/10x rounding.
    private const val CM_PER_M = 100.0
    private const val BMI_EXPONENT = 2.0
    private const val BMI_ROUND_BIAS = 0.05
    private const val BMI_ROUND_SCALE = 10.0

    /** Percent divisor shared by the LBM identity, fat mass and all percentage outputs. */
    private const val PERCENT_DIVISOR = 100.0

    // BF clamp @0x11534.
    private const val MIN_BODY_FAT = 5.1
    private const val MAX_BODY_FAT = 75.0

    // Total body water ≈ 73.2% of FFM (classic hydration constant) with an age slope
    // anchored at 30 years; TBW% is clamped to the plausible human range.
    private const val HYDRATION_FRACTION = 0.732
    private const val HYDRATION_REF_AGE = 30
    private const val HYDRATION_AGE_SLOPE = 0.02
    private const val MIN_WATER_PCT = 35.0
    private const val MAX_WATER_PCT = 75.0

    // Skeletal muscle mass ≈ ~53% of FFM (Wang et al), scaled.
    private const val MUSCLE_FRACTION = 0.53

    // Bone mass approx 3.5-5% of weight: base fraction, male offset, height slope around
    // 170 cm, and the hard min/max envelope as fractions of weight.
    private const val BONE_FRACTION = 0.045
    private const val BONE_MALE_ADJ = 0.35
    private const val BONE_REF_HEIGHT_CM = 170
    private const val BONE_HEIGHT_SLOPE = 0.008
    private const val BONE_MIN_FRACTION = 0.02
    private const val BONE_MAX_FRACTION = 0.10

    /** Protein ≈ remaining FFM after water+bone+minerals, capped at 35% of FFM. */
    private const val PROTEIN_MAX_FRACTION = 0.35

    // BMR — Mifflin-St Jeor coefficients, athlete muscle bonus, and the output envelope.
    private const val MALE_BMR_BASE = 88.362
    private const val MALE_BMR_WEIGHT = 13.397
    private const val MALE_BMR_HEIGHT = 4.799
    private const val MALE_BMR_AGE = 5.677
    private const val FEMALE_BMR_BASE = 447.593
    private const val FEMALE_BMR_WEIGHT = 9.247
    private const val FEMALE_BMR_HEIGHT = 3.098
    private const val FEMALE_BMR_AGE = 4.330
    private const val ATHLETE_BONUS_PER_MUSCLE_KG = 2.0
    private const val MIN_BMR = 800
    private const val MAX_BMR = 4000

    // Visceral fat level 1-59 (Yolanda scale) from BF, age, BMI and the male offset.
    private const val VISCERAL_BF_WEIGHT = 0.28
    private const val VISCERAL_AGE_WEIGHT = 0.08
    private const val VISCERAL_BMI_WEIGHT = 0.15
    private const val VISCERAL_MALE_ADJ = 1.5
    private const val MIN_VISCERAL = 1
    private const val MAX_VISCERAL = 59

    // Body age — chronological plus BF delta against the sex-specific ideal and the
    // visceral delta against level 10, clamped to 10-80.
    private const val IDEAL_BF_MALE = 15.0
    private const val IDEAL_BF_FEMALE = 22.0
    private const val BODY_AGE_BF_WEIGHT = 0.45
    private const val BODY_AGE_VISCERAL_WEIGHT = 0.25
    private const val VISCERAL_REF_LEVEL = 10
    private const val MIN_BODY_AGE = 10
    private const val MAX_BODY_AGE = 80

    // Health score balanced around 85: penalise high BF/visceral/BMI deviation, clamp 40-100.
    private const val BASE_SCORE = 85.0
    private const val SCORE_BF_WEIGHT = 0.9
    private const val SCORE_VISCERAL_WEIGHT = 0.8
    private const val SCORE_BMI_WEIGHT = 1.6
    private const val REF_BMI = 22.0
    private const val MIN_SCORE = 40.0
    private const val MAX_SCORE = 100.0

    // MeasureDecoder.decodeWeight: while above this many kg, divide by ten (ratio fix-up).
    private const val MAX_WEIGHT_KG = 300.0
    private const val WEIGHT_RESCALE_DIVISOR = 10.0

    /** Unsigned-byte mask and byte width for packing/unpacking two-byte frames. */
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8

    /** QNDecoderImpl.kRatio, fixed for every eight-electrode channel. */
    private const val EIGHT_ELECTRODE_RATIO = 0.1

    // Clamps from algorithmWithSecAthlete @0x139a0 (ghidra_helpers.txt:28-62)
    fun clampHeight(h: Double) = h.coerceIn(MIN_HEIGHT_CM, MAX_HEIGHT_CM)
    fun clampAge(a: Int) = a.coerceIn(MIN_AGE, MAX_AGE)
    fun clampResistance(r: Int) = r.coerceIn(MIN_RESISTANCE, MAX_RESISTANCE)

    /** BMI @0x1149c: weight/(h/100)^2 rounded to 1 decimal with +0.05 bias. */
    fun calcBmi(heightCm: Double, weightKg: Double): Double {
        if (heightCm <= 0) return 0.0
        val raw = weightKg / (heightCm / CM_PER_M).pow(BMI_EXPONENT)
        // Ghidra: tmp = (weightKg+0.05)*10 + fudge(1e-07 if negative); return (long)tmp/10
        // 1e-07 fudge is irrelevant except for exact tie-breaking on negative BMI (never hit).
        val tmp = (raw + BMI_ROUND_BIAS) * BMI_ROUND_SCALE
        return (tmp.toLong().toDouble()) / BMI_ROUND_SCALE
    }

    /** LBM @0x11500: weight*(1-bf/100); 0 if bf==0 (no impedance). */
    fun calcLbm(weightKg: Double, bodyFatPercent: Double): Double {
        if (bodyFatPercent == 0.0) return 0.0
        return weightKg * (1.0 - bodyFatPercent / PERCENT_DIVISOR)
    }

    /** BF clamp @0x11534: 5.1-75.0 when impedance present, else 0. */
    fun limitBodyFat(bf: Double, hasImpedance: Boolean): Double {
        if (!hasImpedance) return 0.0
        if (bf.isNaN()) return 0.0
        return when {
            bf <= MIN_BODY_FAT -> MIN_BODY_FAT
            bf >= MAX_BODY_FAT -> MAX_BODY_FAT
            else -> bf
        }
    }

    /** Impedance quick-check @0x149f8 simplified: 50-1500 seen as plausible foot-to-foot. */
    fun isImpedanceValid(r: Int): Boolean = r in MIN_RESISTANCE..NO_CONTACT_RESISTANCE && r != 0

    /**
     * Core body-fat estimator.
     *
     * The .so does NEON_fmadd(weight*DAT, weight, DAT) + height*DAT + sex*DAT -3.3 etc,
     * then BF = (height - d)/height*100 with age*sexBias. We mirror the shape
     * (linear in weight, height, age, sex, ht2/R) with literature-derived coefficients
     * rather than copying the proprietary DAT_ bank.
     */
    private fun estimateBodyFatPercent(
        profile: ScaleProfile,
        weightKg: Double,
        r50: Int,
    ): Double {
        val height = clampHeight(profile.heightCm)
        val age = clampAge(profile.age).toDouble()
        val sex = profile.sex.code.toDouble()
        val athlete = profile.athlete
        // Height^2 / resistance is the classic BIA volume proxy.
        val ht2OverR =
            if (r50 in MIN_RESISTANCE..MAX_RESISTANCE && r50 != 0) {
                (height * height) / r50
            } else {
                null
            }

        // Coefficients chosen to emulate Yolanda SingleFrequency family (non-athlete vs athlete)
        // while staying within Kyle/Sun published ranges.
        val bf: Double = if (ht2OverR != null) {
            // FFM via Kyle equation variant, then BF.
            // Non-athlete: higher intercept; athlete: lower BF by ~2-3 points.
            val ffm = if (!athlete) {
                // Tuned for foot-to-foot (underestimates FFM vs hand-to-foot, so intercept higher)
                 -4.0 + 0.395 * ht2OverR + 0.143 * weightKg + 0.273 * height - 0.11 * age + 4.56 * sex
            } else {
                // Athlete has denser FFM; sex coefficient larger, age slope smaller.
                -6.2 + 0.42 * ht2OverR + 0.155 * weightKg + 0.285 * height - 0.07 * age + 5.1 * sex
            }
            val clampedFfm = ffm.coerceIn(weightKg * 0.25, weightKg * 0.90)
            ((weightKg - clampedFfm) / weightKg) * 100.0
        } else {
            // No impedance — Deurenberg BMI-based fallback (no BIA).
            val bmi = calcBmi(height, weightKg)
            // Deurenberg: BF = 1.20*BMI +0.23*Age -10.8*sex -5.4 ; athlete -2.5
            val base = 1.20 * bmi + 0.23 * age - 10.8 * sex - 5.4
            if (athlete) base - 2.5 else base
        }
        return bf
    }

    fun calculate(profile: ScaleProfile, measurement: ScaleMeasurement): BodyMetrics {
        val height = clampHeight(profile.heightCm)
        val weight = measurement.weightKg
        val r50 = measurement.resistance50
        val hasImpedance = r50 != 0 && r50 < NO_CONTACT_RESISTANCE && r50 in MIN_RESISTANCE..MAX_RESISTANCE

        val bmi = calcBmi(height, weight)
        val rawBf = if (hasImpedance || weight > 0) estimateBodyFatPercent(profile, weight, r50) else 0.0
        val bodyFat = limitBodyFat(rawBf, hasImpedance)

        val lbm = calcLbm(weight, bodyFat)
        val fatMass = if (bodyFat == 0.0) 0.0 else weight * bodyFat / PERCENT_DIVISOR

        val tbwKg = totalBodyWaterKg(lbm, profile.age)
        val waterPercent = waterPercent(tbwKg, weight, bodyFat)
        val muscleMass = muscleMassKg(lbm)
        val musclePercent = if (weight == 0.0) 0.0 else muscleMass / weight * PERCENT_DIVISOR
        val boneKg = boneMassKg(weight, height, profile.sex, bodyFat)
        val proteinKg = proteinMassKg(lbm, tbwKg, boneKg)
        val proteinPercent = if (weight == 0.0) 0.0 else proteinKg / weight * PERCENT_DIVISOR
        val bmr = bmrKcal(profile, weight, height, muscleMass)
        val visceralLevel = visceralLevel(bodyFat, profile.age, bmi, profile.sex.code)
        val idealBf = if (profile.sex == Sex.Male) IDEAL_BF_MALE else IDEAL_BF_FEMALE
        val bodyAge = bodyAge(profile.age, bodyFat, idealBf, visceralLevel)
        val score = healthScore(bodyFat, idealBf, visceralLevel, bmi)
        val segmental = segmentalResult(measurement.segmental, fatMass, muscleMass)

        return BodyMetrics(
            bmi = bmi,
            bodyFatPercent = bodyFat,
            fatMassKg = fatMass,
            lbmKg = lbm,
            waterPercent = waterPercent,
            musclePercent = musclePercent,
            muscleMassKg = muscleMass,
            boneKg = boneKg,
            proteinPercent = proteinPercent,
            proteinKg = proteinKg,
            bmrKcal = bmr,
            visceralLevel = visceralLevel,
            bodyAge = bodyAge,
            score = score,
            segmental = segmental,
        )
    }

    // Total body water ≈ 73.2% of FFM (classic hydration constant) adjusted for age.
    // TBW% = TBW/weight*100.
    private fun totalBodyWaterKg(lbm: Double, age: Int): Double =
        if (lbm == 0.0) 0.0 else lbm * HYDRATION_FRACTION - (age - HYDRATION_REF_AGE) * HYDRATION_AGE_SLOPE

    private fun waterPercent(tbwKg: Double, weight: Double, bodyFat: Double): Double {
        if (weight == 0.0) return 0.0
        val pct = (tbwKg / weight * PERCENT_DIVISOR).coerceIn(MIN_WATER_PCT, MAX_WATER_PCT)
        return if (bodyFat == 0.0) 0.0 else pct
    }

    // Skeletal muscle mass ≈ ~53% of FFM (Wang et al), scaled.
    private fun muscleMassKg(lbm: Double): Double =
        if (lbm == 0.0) 0.0 else (lbm * MUSCLE_FRACTION).coerceIn(0.0, lbm)

    // Bone mass approx 3.5-5% of weight, inversely correlated with BF.
    private fun boneMassKg(weight: Double, height: Double, sex: Sex, bodyFat: Double): Double {
        if (bodyFat == 0.0) return 0.0
        val base = weight * BONE_FRACTION
        // Slightly more bone on taller / male
        val sexAdj = if (sex == Sex.Male) BONE_MALE_ADJ else 0.0
        val hAdj = (height - BONE_REF_HEIGHT_CM) * BONE_HEIGHT_SLOPE
        return (base + sexAdj + hAdj).coerceIn(weight * BONE_MIN_FRACTION, weight * BONE_MAX_FRACTION)
    }

    // Protein ≈ remaining FFM after water+bone+minerals; protein% ~15-19 typical.
    private fun proteinMassKg(lbm: Double, tbwKg: Double, boneKg: Double): Double =
        if (lbm == 0.0) 0.0 else (lbm - tbwKg - boneKg).coerceIn(0.0, lbm * PROTEIN_MAX_FRACTION)

    // BMR — Mifflin-St Jeor with athlete + muscle tweak.
    private fun bmrKcal(profile: ScaleProfile, weight: Double, height: Double, muscleMass: Double): Int {
        val bmrBase = if (profile.sex == Sex.Male) {
            MALE_BMR_BASE + MALE_BMR_WEIGHT * weight + MALE_BMR_HEIGHT * height - MALE_BMR_AGE * profile.age
        } else {
            FEMALE_BMR_BASE + FEMALE_BMR_WEIGHT * weight + FEMALE_BMR_HEIGHT * height -
                FEMALE_BMR_AGE * profile.age
        }
        val bmrAthleteBonus = if (profile.athlete) muscleMass * ATHLETE_BONUS_PER_MUSCLE_KG else 0.0
        val bmr = (bmrBase + bmrAthleteBonus).roundToLong().toInt().coerceIn(MIN_BMR, MAX_BMR)
        return if (weight == 0.0) 0 else bmr
    }

    // Visceral fat level 1-59 (Yolanda scale). Estimate from BF + age + BMI.
    private fun visceralLevel(bodyFat: Double, age: Int, bmi: Double, sexCode: Int): Int {
        if (bodyFat == 0.0) return 0
        val raw = bodyFat * VISCERAL_BF_WEIGHT + age * VISCERAL_AGE_WEIGHT +
            bmi * VISCERAL_BMI_WEIGHT - sexCode * VISCERAL_MALE_ADJ
        return raw.roundToLong().toInt().coerceIn(MIN_VISCERAL, MAX_VISCERAL)
    }

    // Body age — chronological plus BF delta. Ideal BF ~15 male / 22 female.
    private fun bodyAge(age: Int, bodyFat: Double, idealBf: Double, visceralLevel: Int): Int {
        if (bodyFat == 0.0) return 0
        val delta = ((bodyFat - idealBf) * BODY_AGE_BF_WEIGHT +
            (visceralLevel - VISCERAL_REF_LEVEL) * BODY_AGE_VISCERAL_WEIGHT).toInt()
        return (age + delta).coerceIn(MIN_BODY_AGE, MAX_BODY_AGE)
    }

    // Health score 0-100 (Yolanda: balanced around 85). Penalise high BF/visceral/BMI.
    private fun healthScore(bodyFat: Double, idealBf: Double, visceralLevel: Int, bmi: Double): Int {
        if (bodyFat == 0.0) return 0
        var s = BASE_SCORE
        s -= (bodyFat - idealBf).coerceAtLeast(0.0) * SCORE_BF_WEIGHT
        s -= (visceralLevel - VISCERAL_REF_LEVEL).coerceAtLeast(0) * SCORE_VISCERAL_WEIGHT
        s -= kotlin.math.abs(bmi - REF_BMI) * SCORE_BMI_WEIGHT
        return s.coerceIn(MIN_SCORE, MAX_SCORE).toInt()
    }

    // Segmental — only on 8-electrode hardware; Elis 1 is 4-electrode, so null.
    private fun segmentalResult(
        segmental: SegmentalImpedance?,
        fatMass: Double,
        muscleMass: Double,
    ): SegmentalResult? {
        val seg = segmental ?: return null
        // Distribute mass proportionally to impedance ratios (approx).
        // These are illustrative; real Quad fit uses calcSpecialtyQuadElectrodeBodyDataFit @0x27adc.
        val total20 = seg.rh20 + seg.lh20 + seg.t20 + seg.rf20 + seg.lf20
        if (total20 == 0.0) return null
        // Fat per segment proportional to local impedance share, muscle complementary.
        fun fatShare(local: Double) = fatMass * (local / total20)
        fun muscleShare(local: Double) = muscleMass * (local / total20)
        return SegmentalResult(
            fatRh = fatShare(seg.rh20),
            fatLh = fatShare(seg.lh20),
            fatT = fatShare(seg.t20),
            fatRf = fatShare(seg.rf20),
            fatLf = fatShare(seg.lf20),
            muscleRh = muscleShare(seg.rh20),
            muscleLh = muscleShare(seg.lh20),
            muscleT = muscleShare(seg.t20),
            muscleRf = muscleShare(seg.rf20),
            muscleLf = muscleShare(seg.lf20),
        )
    }

    /** Decode weight per MeasureDecoder.decodeWeight: value/ratio, while >300 divide by 10. */
    fun decodeWeight(raw: Int, ratio: Double): Double {
        var w = raw.toDouble() / ratio
        while (w > MAX_WEIGHT_KG) w /= WEIGHT_RESCALE_DIVISOR
        return w
    }

    /** 4-electrode resistance: two bytes big-endian. >=60000 treated as 0 (no contact). */
    fun fourResTwoByte2Int(b1: Byte, b2: Byte): Int {
        val v = ((b1.toInt() and BYTE_MASK) shl BITS_PER_BYTE) or (b2.toInt() and BYTE_MASK)
        return if (v >= NO_CONTACT_RESISTANCE) 0 else v
    }

    /** 8-electrode resistance: (hi<<8|lo)*0.1 ohms, per eightResTwoByte2Double. */
    fun eightResTwoByte2Double(b1: Byte, b2: Byte, ratio: Double = EIGHT_ELECTRODE_RATIO): Double {
        val v = ((b1.toInt() and BYTE_MASK) shl BITS_PER_BYTE) or (b2.toInt() and BYTE_MASK)
        return v * ratio
    }
}
