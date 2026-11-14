package com.vayunmathur.calculator.util

import kotlin.math.PI

/**
 * A dimensional-analysis layer that lets the expression engine carry units through a
 * calculation (e.g. `5m + 2ft`) and lets the Units tab convert between them.
 *
 * The model is coherent-SI at heart: every [Quantity] holds its magnitude in coherent base
 * units (metre, kilogram, second, kelvin, …), so addition just checks the [Dimension]s match
 * and multiplication just multiplies. Temperatures are the one affine exception and are
 * documented on [Quantity].
 */

/**
 * The single source of unit truth
 * The single source of unit truth: the converter's [categories], the parser's [parseTokens],
 * and the output-selector's [unitsFor].
 */
object UnitRegistry {

    // Names the engine reserves for variables/constants/ans; a unit token must never shadow one.
    private val RESERVED = setOf("e", "t", "x", "theta", "pi", "tau", "phi", "ans")

    private const val FAHRENHEIT_OFFSET_K = 273.15 - 32.0 * 5.0 / 9.0

    // ---- Conversion factors to coherent base units (one per unit, named for detekt). ----
    // Temperature.
    private const val KELVIN_OFFSET_OF_CELSIUS = 273.15
    private const val KELVIN_PER_FAHRENHEIT_DEGREE = 5.0 / 9.0

    // Area (square metres).
    private const val SQM_PER_SQUARE_KM = 1e6
    private const val SQM_PER_SQUARE_CM = 1e-4
    private const val SQM_PER_SQUARE_MM = 1e-6
    private const val SQM_PER_HECTARE = 10000.0
    private const val SQM_PER_ACRE = 4046.8564224
    private const val SQM_PER_SQUARE_FOOT = 0.09290304
    private const val SQM_PER_SQUARE_INCH = 0.00064516
    private const val SQM_PER_SQUARE_MILE = 2589988.110336

    // Volume (cubic metres).
    private const val CUBM_PER_CUBIC_CM = 1e-6
    private const val CUBM_PER_LITRE = 0.001
    private const val CUBM_PER_MILLILITRE = 1e-6
    private const val CUBM_PER_GALLON = 0.003785411784
    private const val CUBM_PER_QUART = 9.46352946e-4
    private const val CUBM_PER_PINT = 4.73176473e-4
    private const val CUBM_PER_CUP = 2.365882365e-4
    private const val CUBM_PER_FLUID_OUNCE = 2.95735295625e-5
    private const val CUBM_PER_CUBIC_FOOT = 0.028316846592
    private const val CUBM_PER_CUBIC_INCH = 1.6387064e-5

    // Speed (metres per second).
    private const val MPS_PER_KMH = 1.0 / 3.6
    private const val MPS_PER_MPH = 0.44704
    private const val MPS_PER_KNOT = 0.514444
    private const val METRES_PER_FOOT = 0.3048

    // Data (bits).
    private const val BITS_PER_BYTE = 8.0
    private const val BITS_PER_KILOBYTE = 8e3
    private const val BITS_PER_MEGABYTE = 8e6
    private const val BITS_PER_GIGABYTE = 8e9
    private const val BITS_PER_TERABYTE = 8e12
    private const val BITS_PER_KIBIBYTE = 8.0 * 1024.0
    private const val BITS_PER_MEBIBYTE = 8.0 * 1024.0 * 1024.0
    private const val BITS_PER_GIBIBYTE = 8.0 * 1024.0 * 1024.0 * 1024.0
    private const val BITS_PER_TEBIBYTE = 8.0 * 1024.0 * 1024.0 * 1024.0 * 1024.0
    private const val BITS_PER_KILOBIT = 1e3
    private const val BITS_PER_MEGABIT = 1e6
    private const val BITS_PER_GIGABIT = 1e9

    // Energy (joules).
    private const val JOULES_PER_KILOJOULE = 1000.0
    private const val JOULES_PER_MEGAJOULE = 1e6
    private const val JOULES_PER_CALORIE = 4.184
    private const val JOULES_PER_KILOCALORIE = 4184.0
    private const val JOULES_PER_WATT_HOUR = 3600.0
    private const val JOULES_PER_KILOWATT_HOUR = 3.6e6
    private const val JOULES_PER_ELECTRONVOLT = 1.602176634e-19
    private const val JOULES_PER_BTU = 1055.05585262
    private const val JOULES_PER_ERG = 1e-7

    // Power (watts).
    private const val WATTS_PER_MILLIWATT = 0.001
    private const val WATTS_PER_KILOWATT = 1000.0
    private const val WATTS_PER_MEGAWATT = 1e6
    private const val WATTS_PER_GIGAWATT = 1e9
    private const val WATTS_PER_HORSEPOWER = 745.6998715823

    // Pressure (pascals).
    private const val PA_PER_HECTOPASCAL = 100.0
    private const val PA_PER_KILOPASCAL = 1000.0
    private const val PA_PER_MEGAPASCAL = 1e6
    private const val PA_PER_BAR = 1e5
    private const val PA_PER_MILLIBAR = 100.0
    private const val PA_PER_ATMOSPHERE = 101325.0
    private const val PA_PER_PSI = 6894.757293168
    private const val PA_PER_MMHG = 133.322387415
    private const val PA_PER_TORR = 133.32236842105263

    // Force (newtons).
    private const val NEWTONS_PER_KILONEWTON = 1000.0
    private const val NEWTONS_PER_MILLINEWTON = 0.001
    private const val NEWTONS_PER_POUND_FORCE = 4.4482216152605
    private const val NEWTONS_PER_KG_FORCE = 9.80665
    private const val NEWTONS_PER_DYNE = 1e-5

    // Frequency (hertz).
    private const val HZ_PER_KILOHERTZ = 1000.0
    private const val HZ_PER_MEGAHERTZ = 1e6
    private const val HZ_PER_GIGAHERTZ = 1e9

    // Current (amperes).
    private const val AMPS_PER_MICROAMP = 1e-6
    private const val AMPS_PER_MILLIAMP = 0.001
    private const val AMPS_PER_KILOAMP = 1000.0

    // Voltage (volts).
    private const val VOLTS_PER_MICROVOLT = 1e-6
    private const val VOLTS_PER_MILLIVOLT = 0.001
    private const val VOLTS_PER_KILOVOLT = 1000.0

    // Resistance (ohms).
    private const val OHMS_PER_MILLIOHM = 0.001
    private const val OHMS_PER_KILOOHM = 1000.0
    private const val OHMS_PER_MEGAOHM = 1e6

    // Charge (coulombs).
    private const val COULOMBS_PER_MICROCOULOMB = 1e-6
    private const val COULOMBS_PER_MILLICOULOMB = 0.001
    private const val COULOMBS_PER_MILLIAMP_HOUR = 3.6
    private const val COULOMBS_PER_AMP_HOUR = 3600.0

    // Capacitance (farads).
    private const val FARADS_PER_PICOFARAD = 1e-12
    private const val FARADS_PER_NANOFARAD = 1e-9
    private const val FARADS_PER_MICROFARAD = 1e-6
    private const val FARADS_PER_MILLIFARAD = 0.001

    // Amount (moles).
    private const val MOLES_PER_MICROMOLE = 1e-6
    private const val MOLES_PER_MILLIMOLE = 0.001
    private const val MOLES_PER_KILOMOLE = 1000.0

    // Angle (radians) — derived from PI, so plain vals rather than consts.
    private val RADIANS_PER_DEGREE = PI / 180.0
    private val RADIANS_PER_GRADIAN = PI / 200.0
    private val RADIANS_PER_ARCMINUTE = PI / 10800.0
    private val RADIANS_PER_ARCSECOND = PI / 648000.0
    private val RADIANS_PER_REVOLUTION = 2 * PI

    val categories: List<UnitCategory> = buildCategories()

    /** Case-sensitive lookup for the parser, keyed by every token and alias. */
    val parseTokens: Map<String, UnitDef> = buildMap {
        for (category in categories) {
            if (!category.inEquations) continue
            for (unit in category.units) {
                for (key in listOf(unit.token) + unit.aliases) {
                    if (key.lowercase() in RESERVED) continue
                    putIfAbsent(key, unit)
                }
            }
        }
    }

    /** Units sharing [dimension], to populate the output-unit selector. */
    fun unitsFor(dimension: Dimension): List<UnitDef> =
        categories.flatMap { it.units }.filter { it.dimension == dimension }

    /** A sensible default output unit for [dimension]: the coherent base if present. */
    fun defaultUnitFor(dimension: Dimension): UnitDef? {
        val options = unitsFor(dimension)
        return options.firstOrNull { it.factorToBase == 1.0 && (it.offsetK == null || it.offsetK == 0.0) }
            ?: options.firstOrNull()
    }

    /** Widely-used currencies, surfaced at the top of the currency picker in this order. */
    val CURRENCY_PRIORITY: List<String> =
        listOf("USD", "EUR", "GBP", "JPY", "CNY", "INR", "CAD", "AUD", "CHF", "HKD")

    /** Display names for every ISO 4217 code the rate source returns; unknown codes fall back
     * to the code itself. */
    private val CURRENCY_NAMES: Map<String, String> = mapOf(
        "AED" to "UAE Dirham", "AFN" to "Afghan Afghani", "ALL" to "Albanian Lek",
        "AMD" to "Armenian Dram", "ANG" to "Netherlands Antillean Guilder", "AOA" to "Angolan Kwanza",
        "ARS" to "Argentine Peso", "AUD" to "Australian Dollar", "AWG" to "Aruban Florin",
        "AZN" to "Azerbaijani Manat", "BAM" to "Bosnia-Herzegovina Convertible Mark",
        "BBD" to "Barbadian Dollar", "BDT" to "Bangladeshi Taka", "BGN" to "Bulgarian Lev",
        "BHD" to "Bahraini Dinar", "BIF" to "Burundian Franc", "BMD" to "Bermudan Dollar",
        "BND" to "Brunei Dollar", "BOB" to "Bolivian Boliviano", "BRL" to "Brazilian Real",
        "BSD" to "Bahamian Dollar", "BTN" to "Bhutanese Ngultrum", "BWP" to "Botswanan Pula",
        "BYN" to "Belarusian Ruble", "BZD" to "Belize Dollar", "CAD" to "Canadian Dollar",
        "CDF" to "Congolese Franc", "CHF" to "Swiss Franc", "CLP" to "Chilean Peso",
        "CNY" to "Chinese Yuan", "COP" to "Colombian Peso", "CRC" to "Costa Rican Colon",
        "CUP" to "Cuban Peso", "CVE" to "Cape Verdean Escudo", "CZK" to "Czech Koruna",
        "DJF" to "Djiboutian Franc", "DKK" to "Danish Krone", "DOP" to "Dominican Peso",
        "DZD" to "Algerian Dinar", "EGP" to "Egyptian Pound", "ERN" to "Eritrean Nakfa",
        "ETB" to "Ethiopian Birr", "EUR" to "Euro", "FJD" to "Fijian Dollar",
        "FKP" to "Falkland Islands Pound", "FOK" to "Faroese Krona", "GBP" to "British Pound",
        "GEL" to "Georgian Lari", "GGP" to "Guernsey Pound", "GHS" to "Ghanaian Cedi",
        "GIP" to "Gibraltar Pound", "GMD" to "Gambian Dalasi", "GNF" to "Guinean Franc",
        "GTQ" to "Guatemalan Quetzal", "GYD" to "Guyanaese Dollar", "HKD" to "Hong Kong Dollar",
        "HNL" to "Honduran Lempira", "HRK" to "Croatian Kuna", "HTG" to "Haitian Gourde",
        "HUF" to "Hungarian Forint", "IDR" to "Indonesian Rupiah", "ILS" to "Israeli Shekel",
        "IMP" to "Manx Pound", "INR" to "Indian Rupee", "IQD" to "Iraqi Dinar",
        "IRR" to "Iranian Rial", "ISK" to "Icelandic Krona", "JEP" to "Jersey Pound",
        "JMD" to "Jamaican Dollar", "JOD" to "Jordanian Dinar", "JPY" to "Japanese Yen",
        "KES" to "Kenyan Shilling", "KGS" to "Kyrgystani Som", "KHR" to "Cambodian Riel",
        "KID" to "Kiribati Dollar", "KMF" to "Comorian Franc", "KRW" to "South Korean Won",
        "KWD" to "Kuwaiti Dinar", "KYD" to "Cayman Islands Dollar", "KZT" to "Kazakhstani Tenge",
        "LAK" to "Laotian Kip", "LBP" to "Lebanese Pound", "LKR" to "Sri Lankan Rupee",
        "LRD" to "Liberian Dollar", "LSL" to "Lesotho Loti", "LYD" to "Libyan Dinar",
        "MAD" to "Moroccan Dirham", "MDL" to "Moldovan Leu", "MGA" to "Malagasy Ariary",
        "MKD" to "Macedonian Denar", "MMK" to "Myanmar Kyat", "MNT" to "Mongolian Tugrik",
        "MOP" to "Macanese Pataca", "MRU" to "Mauritanian Ouguiya", "MUR" to "Mauritian Rupee",
        "MVR" to "Maldivian Rufiyaa", "MWK" to "Malawian Kwacha", "MXN" to "Mexican Peso",
        "MYR" to "Malaysian Ringgit", "MZN" to "Mozambican Metical", "NAD" to "Namibian Dollar",
        "NGN" to "Nigerian Naira", "NIO" to "Nicaraguan Cordoba", "NOK" to "Norwegian Krone",
        "NPR" to "Nepalese Rupee", "NZD" to "New Zealand Dollar", "OMR" to "Omani Rial",
        "PAB" to "Panamanian Balboa", "PEN" to "Peruvian Sol", "PGK" to "Papua New Guinean Kina",
        "PHP" to "Philippine Peso", "PKR" to "Pakistani Rupee", "PLN" to "Polish Zloty",
        "PYG" to "Paraguayan Guarani", "QAR" to "Qatari Riyal", "RON" to "Romanian Leu",
        "RSD" to "Serbian Dinar", "RUB" to "Russian Ruble", "RWF" to "Rwandan Franc",
        "SAR" to "Saudi Riyal", "SBD" to "Solomon Islands Dollar", "SCR" to "Seychellois Rupee",
        "SDG" to "Sudanese Pound", "SEK" to "Swedish Krona", "SGD" to "Singapore Dollar",
        "SHP" to "St. Helena Pound", "SLE" to "Sierra Leonean Leone", "SOS" to "Somali Shilling",
        "SRD" to "Surinamese Dollar", "SSP" to "South Sudanese Pound",
        "STN" to "Sao Tome & Principe Dobra", "SYP" to "Syrian Pound", "SZL" to "Swazi Lilangeni",
        "THB" to "Thai Baht", "TJS" to "Tajikistani Somoni", "TMT" to "Turkmenistani Manat",
        "TND" to "Tunisian Dinar", "TOP" to "Tongan Pa'anga", "TRY" to "Turkish Lira",
        "TTD" to "Trinidad & Tobago Dollar", "TVD" to "Tuvaluan Dollar", "TWD" to "Taiwan Dollar",
        "TZS" to "Tanzanian Shilling", "UAH" to "Ukrainian Hryvnia", "UGX" to "Ugandan Shilling",
        "USD" to "US Dollar", "UYU" to "Uruguayan Peso", "UZS" to "Uzbekistani Som",
        "VES" to "Venezuelan Bolivar", "VND" to "Vietnamese Dong", "VUV" to "Vanuatu Vatu",
        "WST" to "Samoan Tala", "XAF" to "Central African CFA Franc", "XCD" to "East Caribbean Dollar",
        "XCG" to "Caribbean Guilder", "XDR" to "Special Drawing Rights",
        "XOF" to "West African CFA Franc", "XPF" to "CFP Franc", "YER" to "Yemeni Rial",
        "ZAR" to "South African Rand", "ZMW" to "Zambian Kwacha", "ZWL" to "Zimbabwean Dollar",
    )

    /**
     * Build the converter's live "Currency" category from [rates] (units of the currency per
     * 1 USD, so USD == 1.0). A currency's [UnitDef.factorToBase] is `1/rate` — its value in
     * USD, the base — which makes the ordinary [UnitDef.toBase]/[UnitDef.fromBase] path convert
     * between any two currencies. Common currencies lead the list; the rest follow
     * alphabetically. Currencies are converter-only, so this category is never `inEquations`.
     * Returns null when [rates] has no usable entries.
     */
    fun currencyCategory(rates: Map<String, Double>): UnitCategory? {
        val usable = rates.filterValues { it.isFinite() && it > 0.0 }
        if (usable.isEmpty()) return null
        val priority = CURRENCY_PRIORITY.filter { it in usable }
        val rest = usable.keys.filter { it !in priority }.sorted()
        val units = (priority + rest).map { code ->
            UnitDef(
                token = code,
                symbol = code,
                name = CURRENCY_NAMES[code] ?: code,
                dimension = Dimension.CURRENCY,
                factorToBase = 1.0 / usable.getValue(code),
            )
        }
        return UnitCategory("Currency", units, inEquations = false)
    }

    private fun buildCategories(): List<UnitCategory> {
        return listOf(
            BasicUnitCategories.lengthCategory(),
            BasicUnitCategories.massCategory(),
            BasicUnitCategories.timeCategory(),
            temperatureCategory(),
            areaCategory(),
            volumeCategory(),
            speedCategory(),
            dataCategory(),
            energyCategory(),
            powerCategory(),
            pressureCategory(),
            forceCategory(),
            frequencyCategory(),
            currentCategory(),
            voltageCategory(),
            resistanceCategory(),
            chargeCategory(),
            capacitanceCategory(),
            amountCategory(),
            angleCategory(),
        )
    }




    private fun temperatureCategory(): UnitCategory {
        val d = Dimension
        return UnitCategory(
            "Temperature",
            listOf(
                UnitDef("K", "K", "Kelvin", d.TEMPERATURE, 1.0, offsetK = 0.0, aliases = listOf("k")),
                UnitDef(
                    "degC",
                    "°C",
                    "Celsius",
                    d.TEMPERATURE,
                    1.0,
                    offsetK = KELVIN_OFFSET_OF_CELSIUS,
                    aliases = listOf("c"),
                ),
                UnitDef(
                    "degF",
                    "°F",
                    "Fahrenheit",
                    d.TEMPERATURE,
                    KELVIN_PER_FAHRENHEIT_DEGREE,
                    offsetK = FAHRENHEIT_OFFSET_K,
                ),
                UnitDef(
                    "degR",
                    "°R",
                    "Rankine",
                    d.TEMPERATURE,
                    KELVIN_PER_FAHRENHEIT_DEGREE,
                    offsetK = 0.0,
                ),
            ),
        )
    }

    private fun areaCategory(): UnitCategory {
        val d = Dimension
        return UnitCategory(
            "Area",
            listOf(
                UnitDef("km2", "km²", "Square kilometre", d.AREA, SQM_PER_SQUARE_KM),
                UnitDef("m2", "m²", "Square metre", d.AREA, 1.0),
                UnitDef("cm2", "cm²", "Square centimetre", d.AREA, SQM_PER_SQUARE_CM),
                UnitDef("mm2", "mm²", "Square millimetre", d.AREA, SQM_PER_SQUARE_MM),
                UnitDef("ha", "ha", "Hectare", d.AREA, SQM_PER_HECTARE),
                UnitDef("acre", "acre", "Acre", d.AREA, SQM_PER_ACRE),
                UnitDef("ft2", "ft²", "Square foot", d.AREA, SQM_PER_SQUARE_FOOT),
                UnitDef("in2", "in²", "Square inch", d.AREA, SQM_PER_SQUARE_INCH),
                UnitDef("mi2", "mi²", "Square mile", d.AREA, SQM_PER_SQUARE_MILE),
            ),
        )
    }

    private fun volumeCategory(): UnitCategory {
        val d = Dimension
        return UnitCategory(
            "Volume",
            listOf(
                UnitDef("m3", "m³", "Cubic metre", d.VOLUME, 1.0),
                UnitDef("cm3", "cm³", "Cubic centimetre", d.VOLUME, CUBM_PER_CUBIC_CM),
                UnitDef("L", "L", "Litre", d.VOLUME, CUBM_PER_LITRE, aliases = listOf("l")),
                UnitDef("mL", "mL", "Millilitre", d.VOLUME, CUBM_PER_MILLILITRE, aliases = listOf("ml")),
                UnitDef("gal", "gal", "Gallon (US)", d.VOLUME, CUBM_PER_GALLON),
                UnitDef("qt", "qt", "Quart (US)", d.VOLUME, CUBM_PER_QUART),
                UnitDef("pt", "pt", "Pint (US)", d.VOLUME, CUBM_PER_PINT),
                UnitDef("cup", "cup", "Cup (US)", d.VOLUME, CUBM_PER_CUP),
                UnitDef("floz", "fl oz", "Fluid ounce (US)", d.VOLUME, CUBM_PER_FLUID_OUNCE),
                UnitDef("ft3", "ft³", "Cubic foot", d.VOLUME, CUBM_PER_CUBIC_FOOT),
                UnitDef("in3", "in³", "Cubic inch", d.VOLUME, CUBM_PER_CUBIC_INCH),
            ),
        )
    }

    private fun speedCategory(): UnitCategory {
        val d = Dimension
        return UnitCategory(
            "Speed",
            listOf(
                UnitDef("mps", "m/s", "Metres per second", d.SPEED, 1.0),
                UnitDef("kmh", "km/h", "Kilometres per hour", d.SPEED, MPS_PER_KMH),
                UnitDef("mph", "mph", "Miles per hour", d.SPEED, MPS_PER_MPH),
                UnitDef("fps", "ft/s", "Feet per second", d.SPEED, METRES_PER_FOOT),
                UnitDef("kn", "kn", "Knot", d.SPEED, MPS_PER_KNOT),
            ),
        )
    }

    private fun dataCategory(): UnitCategory {
        val d = Dimension
        return UnitCategory(
            "Data",
            listOf(
                UnitDef("bit", "bit", "Bit", d.INFORMATION, 1.0),
                UnitDef("B", "B", "Byte", d.INFORMATION, BITS_PER_BYTE),
                UnitDef("kB", "kB", "Kilobyte", d.INFORMATION, BITS_PER_KILOBYTE),
                UnitDef("MB", "MB", "Megabyte", d.INFORMATION, BITS_PER_MEGABYTE),
                UnitDef("GB", "GB", "Gigabyte", d.INFORMATION, BITS_PER_GIGABYTE),
                UnitDef("TB", "TB", "Terabyte", d.INFORMATION, BITS_PER_TERABYTE),
                UnitDef("KiB", "KiB", "Kibibyte", d.INFORMATION, BITS_PER_KIBIBYTE),
                UnitDef("MiB", "MiB", "Mebibyte", d.INFORMATION, BITS_PER_MEBIBYTE),
                UnitDef("GiB", "GiB", "Gibibyte", d.INFORMATION, BITS_PER_GIBIBYTE),
                UnitDef("TiB", "TiB", "Tebibyte", d.INFORMATION, BITS_PER_TEBIBYTE),
                UnitDef("kbit", "kbit", "Kilobit", d.INFORMATION, BITS_PER_KILOBIT),
                UnitDef("Mbit", "Mbit", "Megabit", d.INFORMATION, BITS_PER_MEGABIT),
                UnitDef("Gbit", "Gbit", "Gigabit", d.INFORMATION, BITS_PER_GIGABIT),
            ),
        )
    }

    private fun energyCategory(): UnitCategory {
        val d = Dimension
        return UnitCategory(
            "Energy",
            listOf(
                UnitDef("J", "J", "Joule", d.ENERGY, 1.0),
                UnitDef("kJ", "kJ", "Kilojoule", d.ENERGY, JOULES_PER_KILOJOULE),
                UnitDef("MJ", "MJ", "Megajoule", d.ENERGY, JOULES_PER_MEGAJOULE),
                UnitDef("cal", "cal", "Calorie", d.ENERGY, JOULES_PER_CALORIE),
                UnitDef("kcal", "kcal", "Kilocalorie", d.ENERGY, JOULES_PER_KILOCALORIE),
                UnitDef("Wh", "Wh", "Watt-hour", d.ENERGY, JOULES_PER_WATT_HOUR),
                UnitDef("kWh", "kWh", "Kilowatt-hour", d.ENERGY, JOULES_PER_KILOWATT_HOUR),
                UnitDef("eV", "eV", "Electronvolt", d.ENERGY, JOULES_PER_ELECTRONVOLT),
                UnitDef("BTU", "BTU", "British thermal unit", d.ENERGY, JOULES_PER_BTU),
                UnitDef("erg", "erg", "Erg", d.ENERGY, JOULES_PER_ERG),
            ),
        )
    }

    private fun powerCategory(): UnitCategory {
        val d = Dimension
        return UnitCategory(
            "Power",
            listOf(
                UnitDef("mW", "mW", "Milliwatt", d.POWER, WATTS_PER_MILLIWATT),
                UnitDef("W", "W", "Watt", d.POWER, 1.0),
                UnitDef("kW", "kW", "Kilowatt", d.POWER, WATTS_PER_KILOWATT),
                UnitDef("MW", "MW", "Megawatt", d.POWER, WATTS_PER_MEGAWATT),
                UnitDef("GW", "GW", "Gigawatt", d.POWER, WATTS_PER_GIGAWATT),
                UnitDef("hp", "hp", "Horsepower", d.POWER, WATTS_PER_HORSEPOWER),
            ),
        )
    }

    private fun pressureCategory(): UnitCategory {
        val d = Dimension
        return UnitCategory(
            "Pressure",
            listOf(
                UnitDef("Pa", "Pa", "Pascal", d.PRESSURE, 1.0),
                UnitDef("hPa", "hPa", "Hectopascal", d.PRESSURE, PA_PER_HECTOPASCAL),
                UnitDef("kPa", "kPa", "Kilopascal", d.PRESSURE, PA_PER_KILOPASCAL),
                UnitDef("MPa", "MPa", "Megapascal", d.PRESSURE, PA_PER_MEGAPASCAL),
                UnitDef("bar", "bar", "Bar", d.PRESSURE, PA_PER_BAR),
                UnitDef("mbar", "mbar", "Millibar", d.PRESSURE, PA_PER_MILLIBAR),
                UnitDef("atm", "atm", "Atmosphere", d.PRESSURE, PA_PER_ATMOSPHERE),
                UnitDef("psi", "psi", "Pound per square inch", d.PRESSURE, PA_PER_PSI),
                UnitDef("mmHg", "mmHg", "Millimetre of mercury", d.PRESSURE, PA_PER_MMHG),
                UnitDef("torr", "torr", "Torr", d.PRESSURE, PA_PER_TORR),
            ),
        )
    }

    private fun forceCategory(): UnitCategory {
        val d = Dimension
        return UnitCategory(
            "Force",
            listOf(
                UnitDef("N", "N", "Newton", d.FORCE, 1.0),
                UnitDef("kN", "kN", "Kilonewton", d.FORCE, NEWTONS_PER_KILONEWTON),
                UnitDef("mN", "mN", "Millinewton", d.FORCE, NEWTONS_PER_MILLINEWTON),
                UnitDef("lbf", "lbf", "Pound-force", d.FORCE, NEWTONS_PER_POUND_FORCE),
                UnitDef("kgf", "kgf", "Kilogram-force", d.FORCE, NEWTONS_PER_KG_FORCE),
                UnitDef("dyn", "dyn", "Dyne", d.FORCE, NEWTONS_PER_DYNE),
            ),
        )
    }

    private fun frequencyCategory(): UnitCategory {
        val d = Dimension
        return UnitCategory(
            "Frequency",
            listOf(
                UnitDef("Hz", "Hz", "Hertz", d.FREQUENCY, 1.0),
                UnitDef("kHz", "kHz", "Kilohertz", d.FREQUENCY, HZ_PER_KILOHERTZ),
                UnitDef("MHz", "MHz", "Megahertz", d.FREQUENCY, HZ_PER_MEGAHERTZ),
                UnitDef("GHz", "GHz", "Gigahertz", d.FREQUENCY, HZ_PER_GIGAHERTZ),
            ),
        )
    }

    private fun currentCategory(): UnitCategory {
        val d = Dimension
        return UnitCategory(
            "Current",
            listOf(
                UnitDef("uA", "µA", "Microampere", d.CURRENT, AMPS_PER_MICROAMP),
                UnitDef("mA", "mA", "Milliampere", d.CURRENT, AMPS_PER_MILLIAMP),
                UnitDef("A", "A", "Ampere", d.CURRENT, 1.0),
                UnitDef("kA", "kA", "Kiloampere", d.CURRENT, AMPS_PER_KILOAMP),
            ),
        )
    }

    private fun voltageCategory(): UnitCategory {
        val d = Dimension
        return UnitCategory(
            "Voltage",
            listOf(
                UnitDef("uV", "µV", "Microvolt", d.VOLTAGE, VOLTS_PER_MICROVOLT),
                UnitDef("mV", "mV", "Millivolt", d.VOLTAGE, VOLTS_PER_MILLIVOLT),
                UnitDef("V", "V", "Volt", d.VOLTAGE, 1.0),
                UnitDef("kV", "kV", "Kilovolt", d.VOLTAGE, VOLTS_PER_KILOVOLT),
            ),
        )
    }

    private fun resistanceCategory(): UnitCategory {
        val d = Dimension
        return UnitCategory(
            "Resistance",
            listOf(
                UnitDef("mohm", "mΩ", "Milliohm", d.RESISTANCE, OHMS_PER_MILLIOHM),
                UnitDef("ohm", "Ω", "Ohm", d.RESISTANCE, 1.0),
                UnitDef("kohm", "kΩ", "Kiloohm", d.RESISTANCE, OHMS_PER_KILOOHM),
                UnitDef("Mohm", "MΩ", "Megaohm", d.RESISTANCE, OHMS_PER_MEGAOHM),
            ),
        )
    }

    private fun chargeCategory(): UnitCategory {
        val d = Dimension
        return UnitCategory(
            "Charge",
            listOf(
                UnitDef("uC", "µC", "Microcoulomb", d.CHARGE, COULOMBS_PER_MICROCOULOMB),
                UnitDef("mC", "mC", "Millicoulomb", d.CHARGE, COULOMBS_PER_MILLICOULOMB),
                UnitDef("C", "C", "Coulomb", d.CHARGE, 1.0),
                UnitDef("mAh", "mAh", "Milliamp-hour", d.CHARGE, COULOMBS_PER_MILLIAMP_HOUR),
                UnitDef("Ah", "Ah", "Amp-hour", d.CHARGE, COULOMBS_PER_AMP_HOUR),
            ),
        )
    }

    private fun capacitanceCategory(): UnitCategory {
        val d = Dimension
        return UnitCategory(
            "Capacitance",
            listOf(
                UnitDef("pF", "pF", "Picofarad", d.CAPACITANCE, FARADS_PER_PICOFARAD),
                UnitDef("nF", "nF", "Nanofarad", d.CAPACITANCE, FARADS_PER_NANOFARAD),
                UnitDef("uF", "µF", "Microfarad", d.CAPACITANCE, FARADS_PER_MICROFARAD),
                UnitDef("mF", "mF", "Millifarad", d.CAPACITANCE, FARADS_PER_MILLIFARAD),
                UnitDef("F", "F", "Farad", d.CAPACITANCE, 1.0),
            ),
        )
    }

    private fun amountCategory(): UnitCategory {
        val d = Dimension
        return UnitCategory(
            "Amount",
            listOf(
                UnitDef("umol", "µmol", "Micromole", d.AMOUNT, MOLES_PER_MICROMOLE),
                UnitDef("mmol", "mmol", "Millimole", d.AMOUNT, MOLES_PER_MILLIMOLE),
                UnitDef("mol", "mol", "Mole", d.AMOUNT, 1.0),
                UnitDef("kmol", "kmol", "Kilomole", d.AMOUNT, MOLES_PER_KILOMOLE),
            ),
        )
    }

    private fun angleCategory(): UnitCategory {
        return UnitCategory(
            "Angle",
            listOf(
                UnitDef("rad", "rad", "Radian", Dimension.NONE, 1.0),
                UnitDef("deg", "°", "Degree", Dimension.NONE, RADIANS_PER_DEGREE),
                UnitDef("grad", "grad", "Gradian", Dimension.NONE, RADIANS_PER_GRADIAN),
                UnitDef("arcmin", "′", "Arcminute", Dimension.NONE, RADIANS_PER_ARCMINUTE),
                UnitDef("arcsec", "″", "Arcsecond", Dimension.NONE, RADIANS_PER_ARCSECOND),
                UnitDef("rev", "rev", "Revolution", Dimension.NONE, RADIANS_PER_REVOLUTION),
            ),
            inEquations = false,
        )
    }
}
