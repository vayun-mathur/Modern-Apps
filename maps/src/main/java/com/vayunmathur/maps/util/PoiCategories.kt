package com.vayunmathur.maps.util

/**
 * Shared metadata for the OSM POI `type` enum baked into the v5 `ma_pois`
 * source-layer and the `poi_index.bin` side file (P27). The numbering is the
 * stable type map defined in `scripts/maps/README.md` /
 * `scripts/maps/osm_ingest/src/tags.rs`
 * (0..49 categories, 255 = "other"); never renumber, only append.
 *
 * Kept in one place so the offline search ([PoiIndex], the result subtitle) and the
 * archive's own POI vocabulary ([typeOfKind]) agree on what a numeric type means.
 *
 * The map no longer renders from these numbers — the renderer draws POIs from the archive by
 * kind name, with its own sprite sheet and per-kind zoom gating. The pin colour, glyph and
 * per-category min-zoom that used to live here went with it; what is left is the vocabulary
 * the offline index still speaks.
 */
object PoiCategories {
    /**
     * Human-readable labels indexed by type id (0..51), in the stable numbering
     * defined in `scripts/maps/README.md` / `osm_ingest/src/tags.rs`.
     *
     * A list rather than a `when`: the ids are dense, so the index IS the id and
     * there is nothing to renumber — only append. Unknown ids read as "Place".
     */
    private val LABELS: List<String> = listOf(
        "Restaurant", // 0
        "Cafe", // 1
        "Fast food", // 2
        "Bar", // 3
        "Shop", // 4
        "Grocery", // 5
        "Gas station", // 6
        "Pharmacy", // 7
        "Hotel", // 8
        "Bank", // 9
        "Hospital", // 10
        "School", // 11
        "Park", // 12
        "Gym", // 13
        "Place of worship", // 14
        "Attraction", // 15
        "Parking", // 16
        "Cinema", // 17
        "Theatre", // 18
        "Library", // 19
        "Post office", // 20
        "Police", // 21
        "Fire station", // 22
        "Town hall", // 23
        "Clothing", // 24
        "Electronics", // 25
        "Hardware", // 26
        "Beauty", // 27
        "Car", // 28
        "Bakery", // 29
        "Books", // 30
        "Furniture", // 31
        "Sports", // 32
        "Department store", // 33
        "Dentist", // 34
        "Doctor", // 35
        "Veterinary", // 36
        "Charging station", // 37
        "Museum", // 38
        "Office", // 39
        "Tourist info", // 40
        "Florist", // 41
        "Jewelry", // 42
        "Optician", // 43
        "Laundry", // 44
        "Pet", // 45
        "Liquor", // 46
        "Toys", // 47
        "Gift", // 48
        "Marketplace", // 49
        "Station", // 50
        "Place", // 51: cities, towns and other place labels
    )

    /** Human-readable category label (used as the search-result subtitle). */
    fun label(type: Int): String = LABELS.getOrElse(type) { "Place" }

    /**
     * POI types a normalised singular search term names as a category, or
     * empty when the term is not a category word.
     *
     * Derived from [LABELS] (so "pharmacy" or "bakery" work with no extra
     * table) plus synonyms people actually type ("coffee" for a cafe,
     * "gas" for a gas station, "food" for eating places). Search uses this
     * so category queries — including the category chips, whose queries are
     * words like "gas station" that no POI is literally named — return the
     * nearest places of that kind instead of nothing.
     */
    fun typesForTerm(term: String): Set<Int> {
        val out = HashSet<Int>()
        LABELS.forEachIndexed { type, label ->
            val words = label.lowercase().split(' ', '/')
            if (words.any { PoiSearch.singular(it) == term }) out.add(type)
        }
        SYNONYM_TYPES[term]?.let { out.addAll(it) }
        return out
    }

    private val SYNONYM_TYPES: Map<String, Set<Int>> = mapOf(
        "coffee" to setOf(1),
        "gas" to setOf(6),
        "petrol" to setOf(6),
        "fuel" to setOf(6),
        "food" to setOf(0, 2),
        "eat" to setOf(0, 2),
        "drink" to setOf(3, 1),
        "cash" to setOf(9),
        "atm" to setOf(9),
        "sleep" to setOf(8),
        "stay" to setOf(8),
        "shop" to setOf(4),
        "store" to setOf(4, 33),
        "train" to setOf(STATION_TYPE),
        "station" to setOf(STATION_TYPE),
        "bus" to setOf(STATION_TYPE),
        "car" to setOf(28, 16),
        "parking" to setOf(16),
        "health" to setOf(10, 34, 35),
        "medical" to setOf(10, 35),
    )


    /**
     * The numeric type an archive `kind` corresponds to, or `null` when nothing here means
     * the same thing.
     *
     * The two vocabularies were designed independently — these numbers come from
     * `osm_ingest`'s tag table and predate the archive's `poi` layer by a long way — so this
     * is a best-effort join, not a bijection. Several archive kinds (`beach`, `peak`,
     * `bench`, `artwork`, `building`) describe things this enum never had a bucket for, and
     * several numbers here (`pharmacy`, `police`, `parking`) name things the archive does not
     * draw. Both directions lose.
     *
     * It exists because the numeric type is still load-bearing downstream: `PoiCategories.label`
     * writes the sheet's subtitle from it, and a tapped `station` opens a departure board by
     * matching type 50. Mapping the kind back to a number keeps those working unchanged
     * rather than making every consumer learn a second vocabulary.
     */
    fun typeOfKind(kind: String): Int? =
        foodTypeOfKind(kind) ?: shopTypeOfKind(kind)
            ?: leisureTypeOfKind(kind) ?: civicTypeOfKind(kind)

    private fun foodTypeOfKind(kind: String): Int? = when (kind) {
        "restaurant" -> TYPE_RESTAURANT
        "cafe" -> TYPE_CAFE
        "fast_food" -> TYPE_FAST_FOOD
        "bar" -> TYPE_BAR
        else -> null
    }

    private fun shopTypeOfKind(kind: String): Int? = when (kind) {
        // The archive draws no separate grocery kind; a corner shop is the closest thing.
        "supermarket", "convenience" -> TYPE_GROCERY
        "fuel" -> TYPE_FUEL
        "hotel" -> TYPE_HOTEL
        // An ATM is nearly always a bank's, and this enum has no separate number for one.
        "bank", "atm" -> TYPE_BANK
        "clothes" -> TYPE_CLOTHING
        "electronics" -> TYPE_ELECTRONICS
        "beauty" -> TYPE_BEAUTY
        "books" -> TYPE_BOOKS
        else -> null
    }

    private fun leisureTypeOfKind(kind: String): Int? = when (kind) {
        // `university` folds into school: the enum has one education bucket.
        "school", "university" -> TYPE_SCHOOL
        "park", "garden" -> TYPE_PARK
        "attraction", "zoo" -> TYPE_ATTRACTION
        "stadium" -> TYPE_SPORTS
        "animal" -> TYPE_VETERINARY
        "museum" -> TYPE_MUSEUM
        else -> null
    }

    private fun civicTypeOfKind(kind: String): Int? = when (kind) {
        "theatre" -> TYPE_THEATRE
        "library" -> TYPE_LIBRARY
        "post_office" -> TYPE_POST_OFFICE
        "townhall" -> TYPE_TOWN_HALL
        // The one mapping with behaviour attached: a tapped station opens the departure
        // board. `bus_stop` and `ferry_terminal` have no number here — this table is
        // `osm_ingest`'s, and it treats a bus pole or a ferry pier as street furniture
        // rather than a POI — so they route to the board by kind instead; see
        // [opensDepartureBoard].
        "station" -> STATION_TYPE
        else -> null
    }

    /** Place labels (cities, towns, villages…), searchable since planet4 (`osm_ingest` `TYPE_PLACE`). */
    internal const val TYPE_PLACE = 51

    private const val TYPE_RESTAURANT = 0
    private const val TYPE_CAFE = 1
    private const val TYPE_FAST_FOOD = 2
    private const val TYPE_BAR = 3
    private const val TYPE_GROCERY = 5
    private const val TYPE_FUEL = 6
    private const val TYPE_HOTEL = 8
    private const val TYPE_BANK = 9
    private const val TYPE_SCHOOL = 11
    internal const val TYPE_PARK = 12
    internal const val TYPE_ATTRACTION = 15
    private const val TYPE_THEATRE = 18
    private const val TYPE_LIBRARY = 19
    private const val TYPE_POST_OFFICE = 20
    private const val TYPE_TOWN_HALL = 23
    private const val TYPE_CLOTHING = 24
    private const val TYPE_ELECTRONICS = 25
    private const val TYPE_BEAUTY = 27
    private const val TYPE_BOOKS = 30
    private const val TYPE_SPORTS = 32
    private const val TYPE_VETERINARY = 36
    internal const val TYPE_MUSEUM = 38

    /**
     * The station type, whose taps open a departure board rather than a place sheet.
     *
     * Station POIs carry no stop id of their own; see `TransitStopsViewModel.openNearestStop`.
     */
    const val STATION_TYPE: Int = 50

    /**
     * Archive `poi` kinds whose taps open a departure board rather than a place sheet.
     *
     * Matched on the kind rather than on [typeOfKind]'s number because two of the three have
     * no number to match: `osm_ingest`'s table predates the archive and treats a bus pole or a
     * ferry pier as street furniture rather than a POI, so only `station` ever reaches 50.
     * The archive draws all three, and a tap on any of them is asking the same question.
     *
     * `bus_stop` covers tram stops too — the tiler folds `railway=tram_stop` into that kind.
     *
     * None of them carry a stop id, so the board is resolved from the nearest stop in the
     * baked pack; see `TransitStopsViewModel.openNearestStop`.
     */
    fun opensDepartureBoard(kind: String): Boolean =
        kind == "station" || kind == "bus_stop" || kind == "ferry_terminal"
}
