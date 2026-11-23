package com.vayunmathur.news.domain

/**
 * Feed category taxonomy for the built-in catalog.
 *
 * Decided from the actual 88-source catalog (see [SourceCatalog]), not
 * from a generic news template:
 * - Geographic scope for general-news outlets: [WORLD] (international and
 *   non-US national generalists), [US] (US national generalists), and
 *   [LOCAL] (city/metro/state/regional outlets in any country).
 * - Editorial topic for single-topic verticals: [TECHNOLOGY] and
 *   [SCIENCE] (science includes health/medicine, e.g. STAT).
 *
 * There are deliberately no Business, Politics, Culture, Sport, or
 * Language categories: the catalog has no dedicated outlet for the first
 * four (generalists merely *cover* those sections, so tagging them by
 * section would put almost every source in almost every category and
 * make filtering meaningless), and language is already a separate field
 * ([NewsSource.language]) — it is not conflated with category.
 *
 * Assignment rule (data in [SourceCatalog], no code branches): a
 * single-topic vertical gets its topic; every other outlet gets exactly
 * one geographic category by its scope. A vertical may add one secondary
 * topic tag where that beat is definitional (e.g. Ars Technica also gets
 * [SCIENCE]). The first entry of [NewsSource.categories] is the primary
 * category; any further entries are secondary tags, and an article
 * matches a selected category when the category is among its source's
 * tags.
 *
 * Server `news.json` carries no category field. Articles inherit their
 * category client-side: the server's `source` value is resolved to a
 * catalog id by [ServerFeedParser] (id or exact display name), and the
 * category is then looked up from [SourceCatalog] by that id. The same
 * lookup applies to fallback on-device crawl articles, which are created
 * with a catalog source id directly. An unresolved source id has no
 * categories: it matches "All" only, never a specific category.
 */
enum class NewsCategory(val label: String) {
    WORLD("World"),
    US("US"),
    TECHNOLOGY("Technology"),
    SCIENCE("Science"),
    LOCAL("Local"),
}
