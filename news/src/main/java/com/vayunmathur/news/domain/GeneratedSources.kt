package com.vayunmathur.news.domain

/**
 * Aggregates the bulk-generated catalog in catalog order.
 *
 * The generated entries live in `generatedSourcesA`
 * (GeneratedSourcesA.kt) and `generatedSourcesB`
 * (GeneratedSourcesB.kt), split by catalog order so each file stays
 * under the 800-line FileLength limit. [SourceCatalog.sources] is the
 * curated entries from SourceCatalog.kt followed by this list, so the
 * overall catalog order is curated first, then generated A, then B.
 *
 * Do not hand-edit the generated entries; regenerate with
 * `analysis/news-scale/curate.py --apply` — its --apply target is
 * GeneratedSourcesA.kt + GeneratedSourcesB.kt in this directory (this
 * aggregator file is not overwritten). If a future regeneration needs
 * more than two parts, add the new part here in catalog order.
 */
internal val generatedSources: List<NewsSource> = generatedSourcesA + generatedSourcesB
