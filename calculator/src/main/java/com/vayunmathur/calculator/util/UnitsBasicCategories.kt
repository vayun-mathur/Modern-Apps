package com.vayunmathur.calculator.util

/**
 * Length, mass and time categories for [UnitRegistry], split out so the registry
 * stays a single readable source without one oversized category builder.
 */
internal object BasicUnitCategories {

    // Length (metres).
    private const val METRES_PER_KM = 1000.0
    private const val METRES_PER_CM = 0.01
    private const val METRES_PER_MM = 0.001
    private const val METRES_PER_MICROMETRE = 1e-6
    private const val METRES_PER_NM = 1e-9
    private const val METRES_PER_MILE = 1609.344
    private const val METRES_PER_YARD = 0.9144
    private const val METRES_PER_FOOT = 0.3048
    private const val METRES_PER_INCH = 0.0254
    private const val METRES_PER_NAUTICAL_MILE = 1852.0
    private const val METRES_PER_LIGHT_NANOSECOND = 0.299792458
    private const val METRES_PER_LIGHT_MICROSECOND = 299.792458
    private const val METRES_PER_LIGHT_MILLISECOND = 299792.458
    private const val METRES_PER_LIGHT_SECOND = 299792458.0
    private const val METRES_PER_LIGHT_MINUTE = 17987547480.0
    private const val METRES_PER_LIGHT_HOUR = 1079252848800.0
    private const val METRES_PER_LIGHT_DAY = 25902068371200.0
    private const val METRES_PER_LIGHT_WEEK = 181314478598400.0
    private const val METRES_PER_LIGHT_YEAR = 9.4607304725808e15
    private const val METRES_PER_AU = 1.495978707e11
    private const val METRES_PER_PARSEC = 3.08567758149137e16
    private const val METRES_PER_KILOPARSEC = 3.08567758149137e19
    private const val METRES_PER_MEGAPARSEC = 3.08567758149137e22
    private const val METRES_PER_GIGAPARSEC = 3.08567758149137e25

    // Mass (kilograms).
    private const val KG_PER_TONNE = 1000.0
    private const val KG_PER_GRAM = 0.001
    private const val KG_PER_MILLIGRAM = 1e-6
    private const val KG_PER_MICROGRAM = 1e-9
    private const val KG_PER_POUND = 0.45359237
    private const val KG_PER_OUNCE = 0.028349523125
    private const val KG_PER_STONE = 6.35029318

    // Time (seconds).
    private const val SECONDS_PER_NANOSECOND = 1e-9
    private const val SECONDS_PER_MICROSECOND = 1e-6
    private const val SECONDS_PER_MILLISECOND = 0.001
    private const val SECONDS_PER_MINUTE = 60.0
    private const val SECONDS_PER_HOUR = 3600.0
    private const val SECONDS_PER_DAY = 86400.0
    private const val SECONDS_PER_WEEK = 604800.0
    private const val SECONDS_PER_YEAR = 31557600.0

    fun lengthCategory(): UnitCategory = UnitCategory(
        "Length",
        metricLengthUnits() + imperialLengthUnits() + parsecLengthUnits() +
            largeLightLengthUnits() + smallLightLengthUnits() + astronomicalLengthUnits(),
    )

    private fun metricLengthUnits(): List<UnitDef> = listOf(
            UnitDef("km", "km", "Kilometre", Dimension.LENGTH, METRES_PER_KM),
            UnitDef("m", "m", "Metre", Dimension.LENGTH, 1.0),
            UnitDef("cm", "cm", "Centimetre", Dimension.LENGTH, METRES_PER_CM),
            UnitDef("mm", "mm", "Millimetre", Dimension.LENGTH, METRES_PER_MM),
            UnitDef("um", "µm", "Micrometre", Dimension.LENGTH, METRES_PER_MICROMETRE),
            UnitDef("nm", "nm", "Nanometre", Dimension.LENGTH, METRES_PER_NM),
        )

    private fun imperialLengthUnits(): List<UnitDef> = listOf(
            UnitDef("mi", "mi", "Mile", Dimension.LENGTH, METRES_PER_MILE),
            UnitDef("yd", "yd", "Yard", Dimension.LENGTH, METRES_PER_YARD),
            UnitDef("ft", "ft", "Foot", Dimension.LENGTH, METRES_PER_FOOT),
            UnitDef("in", "in", "Inch", Dimension.LENGTH, METRES_PER_INCH),
            UnitDef("nmi", "nmi", "Nautical mile", Dimension.LENGTH, METRES_PER_NAUTICAL_MILE),
        )

    private fun parsecLengthUnits(): List<UnitDef> = listOf(
            UnitDef(
                "Gpc",
                "Gpc",
                "Gigaparsec",
                Dimension.LENGTH,
                METRES_PER_GIGAPARSEC,
                aliases = listOf("gpc", "gigaparsec", "gigaparsecs"),
                ),
            UnitDef(
                "Mpc",
                "Mpc",
                "Megaparsec",
                Dimension.LENGTH,
                METRES_PER_MEGAPARSEC,
                aliases = listOf("mpc", "megaparsec", "megaparsecs"),
                ),
            UnitDef(
                "kpc",
                "kpc",
                "Kiloparsec",
                Dimension.LENGTH,
                METRES_PER_KILOPARSEC,
                aliases = listOf("kiloparsec", "kiloparsecs"),
                ),
            UnitDef(
                "pc",
                "pc",
                "Parsec",
                Dimension.LENGTH,
                METRES_PER_PARSEC,
                aliases = listOf("parsec", "parsecs"),
                ),
        )

    private fun largeLightLengthUnits(): List<UnitDef> = listOf(
            UnitDef(
                "ly",
                "ly",
                "Light-year",
                Dimension.LENGTH,
                METRES_PER_LIGHT_YEAR,
                aliases = listOf("lightyear", "lightyears"),
                ),
            UnitDef(
                "lw",
                "lw",
                "Light-week",
                Dimension.LENGTH,
                METRES_PER_LIGHT_WEEK,
                aliases = listOf("lwk", "lightweek", "lightweeks"),
                ),
            UnitDef(
                "ld",
                "ld",
                "Light-day",
                Dimension.LENGTH,
                METRES_PER_LIGHT_DAY,
                aliases = listOf("lday", "lightday", "lightdays"),
                ),
            UnitDef(
                "lh",
                "lh",
                "Light-hour",
                Dimension.LENGTH,
                METRES_PER_LIGHT_HOUR,
                aliases = listOf("lighthour", "lighthours"),
                ),
            UnitDef(
                "lmin",
                "lmin",
                "Light-minute",
                Dimension.LENGTH,
                METRES_PER_LIGHT_MINUTE,
                aliases = listOf("lightminute", "lightminutes"),
                ),
        )

    private fun smallLightLengthUnits(): List<UnitDef> = listOf(
            UnitDef(
                "ls",
                "ls",
                "Light-second",
                Dimension.LENGTH,
                METRES_PER_LIGHT_SECOND,
                aliases = listOf("lightsecond", "lightseconds"),
                ),
            UnitDef(
                "lms",
                "lms",
                "Light-millisecond",
                Dimension.LENGTH,
                METRES_PER_LIGHT_MILLISECOND,
                aliases = listOf(
                "lightmillisecond",
                "lightmilliseconds",
                ),
                ),
            UnitDef(
                "lus",
                "lµs",
                "Light-microsecond",
                Dimension.LENGTH,
                METRES_PER_LIGHT_MICROSECOND,
                aliases = listOf(
                "lightmicrosecond",
                "lightmicroseconds",
                ),
                ),
            UnitDef(
                "lns",
                "lns",
                "Light-nanosecond",
                Dimension.LENGTH,
                METRES_PER_LIGHT_NANOSECOND,
                aliases = listOf(
                "lightnanosecond",
                "lightnanoseconds",
                ),
                ),
        )

    private fun astronomicalLengthUnits(): List<UnitDef> = listOf(
            UnitDef(
                "AU",
                "AU",
                "Astronomical unit",
                Dimension.LENGTH,
                METRES_PER_AU,
                aliases = listOf("au", "astronomicalunit", "astronomicalunits"),
                ),
        )

    fun massCategory(): UnitCategory {
        val d = Dimension
        return UnitCategory(
            "Mass",
            listOf(
                UnitDef("tonne", "t", "Tonne", d.MASS, KG_PER_TONNE),
                UnitDef("kg", "kg", "Kilogram", d.MASS, 1.0),
                UnitDef("g", "g", "Gram", d.MASS, KG_PER_GRAM),
                UnitDef("mg", "mg", "Milligram", d.MASS, KG_PER_MILLIGRAM),
                UnitDef("ug", "µg", "Microgram", d.MASS, KG_PER_MICROGRAM),
                UnitDef("lb", "lb", "Pound", d.MASS, KG_PER_POUND),
                UnitDef("oz", "oz", "Ounce", d.MASS, KG_PER_OUNCE),
                UnitDef("st", "st", "Stone", d.MASS, KG_PER_STONE),
            ),
        )
    }

    fun timeCategory(): UnitCategory {
        val d = Dimension
        return UnitCategory(
            "Time",
            listOf(
                UnitDef("ns", "ns", "Nanosecond", d.TIME, SECONDS_PER_NANOSECOND),
                UnitDef("us", "µs", "Microsecond", d.TIME, SECONDS_PER_MICROSECOND),
                UnitDef("ms", "ms", "Millisecond", d.TIME, SECONDS_PER_MILLISECOND),
                UnitDef("s", "s", "Second", d.TIME, 1.0),
                UnitDef("min", "min", "Minute", d.TIME, SECONDS_PER_MINUTE),
                UnitDef("h", "h", "Hour", d.TIME, SECONDS_PER_HOUR),
                UnitDef("day", "day", "Day", d.TIME, SECONDS_PER_DAY),
                UnitDef("wk", "wk", "Week", d.TIME, SECONDS_PER_WEEK),
                UnitDef("yr", "yr", "Year", d.TIME, SECONDS_PER_YEAR),
            ),
        )
    }
}
