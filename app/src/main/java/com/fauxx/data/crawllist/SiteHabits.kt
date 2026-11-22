package com.fauxx.data.crawllist

import com.fauxx.data.device.DeviceDeriver
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.di.QueryGrammarSeed
import com.fauxx.targeting.layer3.PersonaChannel
import com.fauxx.targeting.layer3.PersonaRotationLayer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

/**
 * A persona's favourite sites (#322 item 3): a handful of sites from its interest categories that
 * it goes back to again and again, for its whole life.
 *
 * People have favourites: in the history shared in #270 the top 10 sites take 39% of visits and
 * more than half of a day's visits go to sites seen in the previous fortnight. A stream that never
 * favours anything looks like no one. When the crawl list picks a page in a category that holds
 * one of these sites, it goes to a favourite [share] of the time, the favourites weighted 1, 1/2,
 * 1/3 and so on in [sites] order (Zipf), and otherwise draws by popularity ([SitePopularity]).
 *
 * @property sites The favourites, most visited first, as [SitePopularity.siteOf] names them.
 * @property share Chance a visit goes to a favourite when one fits the category.
 */
data class HabitualSites(val sites: List<String>, val share: Double) {

    /** Zipf weight of the favourite at [index] in [sites]. */
    fun weightAt(index: Int): Double = 1.0 / (index + 1)

    companion object {
        private val SITE_COUNT = 5..15
        private val SHARE_PER_MILLE = 300..600
        private const val DOMAIN = "sites"
        private const val DRAW_RESOLUTION = 1_000_000

        /**
         * Derive the favourites for [key] (a persona id, or an install key) from the [entries] of the
         * crawl list in [localeTag]: [SITE_COUNT] sites with a page in one of [interests] (any site
         * when there are none), drawn without replacement, the more popular ones more likely.
         *
         * Uses [DeviceDeriver.pick]'s portable hash and no PRNG, so another implementation
         * (fauxx-desktop) derives the same favourites from the same persona and list. The weighted
         * draw is Efraimidis-Spirakis: each site gets `ln(u) / weight` for a hashed `u` in (0, 1),
         * and the largest keys win.
         */
        fun derive(
            key: String,
            interests: Set<CategoryPool>,
            entries: List<CrawlEntry>,
            popularity: SitePopularity,
            localeTag: String,
        ): HabitualSites {
            val corpus = entries.filterNot { it.isCustom }
            val inInterests = corpus.filter { interests.isEmpty() || it.category in interests }
            val sites = inInterests.map { popularity.siteOf(it.domain) }.distinct()
            val count = SITE_COUNT.first + DeviceDeriver.pick(key, "$DOMAIN:count:$localeTag", 0, SITE_COUNT.count())
            val chosen = sites
                .map { site ->
                    val u = (DeviceDeriver.pick(key, "$DOMAIN:$localeTag:$site", 0, DRAW_RESOLUTION) + 0.5) / DRAW_RESOLUTION
                    site to ln(u) / popularity.weightOf(site)
                }
                .sortedByDescending { it.second }
                .take(count)
                .map { it.first }
            val share = (SHARE_PER_MILLE.first +
                DeviceDeriver.pick(key, "$DOMAIN:share", 0, SHARE_PER_MILLE.count())) / 1000.0
            return HabitualSites(chosen, share)
        }
    }
}

/** Who the favourites belong to: [key] (a persona id, or an install key) and its [interests]. */
data class SiteHabitsKey(val key: String, val interests: Set<CategoryPool>)

/** Supplies the [SiteHabitsKey] the crawl list derives favourites for. [NONE] means no favourites. */
fun interface SiteHabitsSource {
    fun current(): SiteHabitsKey?

    companion object {
        val NONE = SiteHabitsSource { null }
    }
}

/**
 * Favourites from the active persona, through its own staggered [PersonaChannel.SITES], so a
 * rotation does not change favourites in the same instant as everything else. With Layer 3 off
 * there is no persona, so they come from the per-install seed, drawn from the whole list, and stay
 * fixed for the install.
 */
@Singleton
class PersonaSiteHabits @Inject constructor(
    private val personaLayer: PersonaRotationLayer,
    private val installSeed: QueryGrammarSeed,
) : SiteHabitsSource {
    /** Resolved on first use, which is on the engine's background dispatcher. */
    private val installKey by lazy { "install:${installSeed.load()}" }

    override fun current(): SiteHabitsKey =
        personaLayer.personaForChannel(PersonaChannel.SITES)?.let { SiteHabitsKey(it.id, it.interests) }
            ?: SiteHabitsKey(installKey, emptySet())
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SiteHabitsModule {
    @Binds
    abstract fun bindSiteHabitsSource(habits: PersonaSiteHabits): SiteHabitsSource
}
