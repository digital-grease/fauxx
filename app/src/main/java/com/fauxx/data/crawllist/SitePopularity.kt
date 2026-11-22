package com.fauxx.data.crawllist

import com.google.gson.JsonParser
import kotlin.math.pow

/**
 * How popular each crawl site is on the real web (#322 item 8), from the Tranco ranks bundled in
 * `assets/site_popularity.json` (built by `scripts/build_site_popularity.py`).
 *
 * People visit a few big sites often and many small ones rarely; a stream that draws sites evenly
 * is easy to tell from a real one, since real interests follow a skewed popularity distribution
 * (Beugin and McDaniel, PETS 2024, told uniformly drawn noise topics apart 93.9% of the time). A
 * site's weight falls with its rank as a power law, `rank^-ALPHA`, with ranks above
 * [RANK_CAP] counted as [RANK_CAP] so the corpus's very biggest sites cannot swallow their
 * category, and sites not on the list counted as [UNRANKED]. With the shipped corpus that gives a
 * category's most popular site about 15% of its visits (median over categories), its top three
 * about 35%, and none more than 60%.
 *
 * A site is the Tranco domain a host matched (the longest suffix on the list), so
 * `www.bbc.co.uk` and `bbc.co.uk` are one site, or the host without `www.` when none matched.
 */
class SitePopularity private constructor(private val ranks: Map<String, Int>) {

    private val siteCache = HashMap<String, String>()

    /** The site [host] belongs to; see the class KDoc. */
    @Synchronized
    fun siteOf(host: String): String = siteCache.getOrPut(host.lowercase()) {
        val labels = host.lowercase().split('.')
        (0 until labels.size - 1).map { labels.drop(it).joinToString(".") }.firstOrNull { it in ranks }
            ?: host.lowercase().removePrefix("www.")
    }

    /** The Tranco rank of [site], or null when it is not on the list. */
    fun rankOf(site: String): Int? = ranks[site]

    /** The relative weight of [site]; only ratios between sites matter. */
    fun weightOf(site: String): Double = (rankOf(site) ?: UNRANKED).coerceAtLeast(RANK_CAP).toDouble().pow(-ALPHA)

    companion object {
        /** Power-law exponent: real traffic by rank is close to Zipf (1.0); softened for a small curated corpus. */
        const val ALPHA = 0.7

        /** Ranks above this count as this, so the biggest sites cannot dominate a category. */
        const val RANK_CAP = 100

        /** The rank given to a site not on the list: below anything on it. */
        const val UNRANKED = 2_000_000

        /** No ranks: every site weighs the same, the even draw from before. */
        val EVEN = SitePopularity(emptyMap())

        /** Parse the bundled asset; anything unreadable gives [EVEN]. */
        fun parse(json: String): SitePopularity = runCatching {
            val ranks = JsonParser.parseString(json).asJsonObject.getAsJsonObject("ranks")
            SitePopularity(ranks.entrySet().associate { (site, rank) -> site.lowercase() to rank.asInt })
        }.getOrDefault(EVEN)
    }
}
