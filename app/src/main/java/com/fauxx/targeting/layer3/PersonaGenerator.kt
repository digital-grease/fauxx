package com.fauxx.targeting.layer3

import android.content.Context
import androidx.annotation.Keep
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import com.fauxx.targeting.layer1.AgeRange
import com.fauxx.targeting.layer1.DemographicProfileDao
import com.fauxx.targeting.layer1.Profession
import com.fauxx.targeting.layer1.UserDemographicProfile
import com.fauxx.util.Clock
import com.fauxx.util.SystemClockImpl
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random


/**
 * Generates coherent [SyntheticPersona] instances and validates them against consistency
 * rules and recent history.
 *
 * Demographics (E7 #173, hybrid scope): for the EN locale, (ageRange, profession, region)
 * are JOINTLY multinomial-sampled from the bundled ACS PUMS distribution via
 * [PersonaDistribution], so traits co-occur per real US population data. Non-EN locales
 * (and EN when the distribution asset is unusable) keep the hand-authored template path.
 * Interests are still sampled from templates/weight hints independently of the joint
 * demographics — P(interests | age, profession) is a tracked follow-up.
 *
 * All demographic fields are stored as layer-1 enum NAMES ("AGE_35_44", "FINANCE_PROF",
 * "US_MIDWEST"); display labels are resolved only in the UI via DemographicLabels.
 *
 * A persona is rejected if:
 * - It fails [PersonaConsistencyRules.isValid]
 * - It matches the user's own demographics on 2+ traits
 * - It shares >60% trait overlap with any persona from the past 90 days
 *
 * Falls back to a built-in generic persona if no valid persona can be generated after
 * [MAX_ATTEMPTS] tries.
 */
@Singleton
class PersonaGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val historyDao: PersonaHistoryDao,
    private val demographicProfileDao: DemographicProfileDao,
    private val localeManager: LocaleManager,
    private val distribution: PersonaDistribution,
    private val clock: Clock = SystemClockImpl(),
    private val random: Random = Random.Default,
) {
    private val gson = Gson()
    private val templatesByLocale = ConcurrentHashMap<SupportedLocale, List<PersonaTemplate>>()

    /** Templates for the active locale. Resolved on each access so locale changes are picked up. */
    private val templates: List<PersonaTemplate>
        get() = templatesByLocale.getOrPut(localeManager.currentLocale) {
            loadTemplates(localeManager.currentLocale)
        }

    companion object {
        private const val MAX_ATTEMPTS = 10

        /**
         * Persona lifetime bounds, redrawn every rotation.
         *
         * A persona is the engine's browsing identity for one to three months rather than the
         * ~9 days it used to be. Two reasons, and the second is the load-bearing one:
         *
         * 1. DEPTH. Poisoning works by giving a broker a synthetic profile convincing enough to
         *    keep. Nine days of accumulated cookies and site storage reads as a burner and is
         *    cheap to discard as low-confidence; months of continuous accumulation is not.
         * 2. NO PERIODIC CHANGE-POINT. Rotation resets the persona's whole story (region,
         *    interests, rhythm, device) at once. Doing that ~40 times a year on a regular
         *    cadence is itself a signature. Doing it 4-12 times at irregular intervals looks
         *    like someone who cleared their browsing data or replaced a handset, which real
         *    people do a few times a year.
         *
         * The interval is drawn uniformly across the whole range each cycle rather than being a
         * fixed base plus small jitter, so there is no modal interval left to key on.
         */
        internal const val MIN_ROTATION_DAYS = 30L
        internal const val MAX_ROTATION_DAYS = 90L

        /**
         * How many worst-case persona lifetimes the distinctness lookback spans.
         *
         * The lookback feeds the overlap check below, which rejects a candidate too similar to
         * a recent persona, so it only means something if it covers several personas. A flat 90
         * days covered about ten at the old ~9-day lifetime and would cover exactly one at a
         * 90-day lifetime, quietly turning the check into a no-op.
         *
         * [PersonaRotationLayer] prunes persona history on this same window. Widening the
         * lookback without widening retention would leave it silently truncated by pruning.
         */
        internal const val RECENT_PERSONA_CYCLES = 4L

        /** Distinctness lookback: [RECENT_PERSONA_CYCLES] worst-case persona lifetimes. */
        internal val RECENT_PERSONA_WINDOW_MS: Long =
            TimeUnit.DAYS.toMillis(MAX_ROTATION_DAYS * RECENT_PERSONA_CYCLES)

        /** Reject personas matching user demographics on this many or more traits. */
        private const val MIN_DEMOGRAPHIC_MATCHES = 2
    }

    /**
     * Generate a new persona, avoiding recent history and ensuring internal consistency.
     * [weightHints] can optionally bias interest selection toward high-weight categories.
     */
    suspend fun generate(weightHints: Map<CategoryPool, Float> = emptyMap()): SyntheticPersona {
        val cutoff = clock.currentTimeMillis() - RECENT_PERSONA_WINDOW_MS
        val recentEntries = historyDao.getRecentPersonas(cutoff)
        val recentPersonas = recentEntries.mapNotNull { entry ->
            runCatching {
                gson.fromJson(entry.personaJson, SyntheticPersona::class.java)
            }.getOrNull()
        }
        val userProfile = try { demographicProfileDao.get() } catch (_: Exception) { null }

        repeat(MAX_ATTEMPTS) {
            val candidate = buildPersona(weightHints)
            if (!PersonaConsistencyRules.isValid(candidate)) return@repeat

            if (matchesUserDemographics(candidate, userProfile)) return@repeat

            val tooSimilar = recentPersonas.any { recent ->
                PersonaConsistencyRules.overlapFraction(candidate, recent) >
                    PersonaConsistencyRules.OVERLAP_THRESHOLD
            }
            if (!tooSimilar) return candidate
        }

        Timber.w("Could not generate unique persona after $MAX_ATTEMPTS attempts, using fallback")
        return buildFallbackPersona()
    }

    /**
     * Next rotation timestamp: a fresh uniform draw over
     * [MIN_ROTATION_DAYS]..[MAX_ROTATION_DAYS], so successive lifetimes are independent and
     * no fixed base interval sits underneath the jitter.
     */
    fun nextRotationTime(): Long {
        val days = random.nextLong(MIN_ROTATION_DAYS, MAX_ROTATION_DAYS + 1)
        return clock.currentTimeMillis() + TimeUnit.DAYS.toMillis(days)
    }

    private fun buildPersona(weightHints: Map<CategoryPool, Float>): SyntheticPersona {
        val template = if (templates.isNotEmpty()) templates.random(random) else null
        val interests = selectInterests(template, weightHints)
        val now = clock.currentTimeMillis()

        // US personas joint-sample all three demographics from the same ACS PUMS cell;
        // null for non-EN locales (hand-authored templates) or when the asset is unusable.
        val cell = if (localeManager.currentLocale == SupportedLocale.EN) {
            distribution.sample(random)
        } else {
            null
        }

        return SyntheticPersona(
            id = UUID.randomUUID().toString(),
            name = generateName(),
            ageRange = cell?.age ?: template?.ageRange ?: pickAgeRange(),
            profession = cell?.profession ?: template?.profession ?: pickProfession(),
            region = cell?.region ?: template?.region ?: pickRegion(),
            interests = interests,
            createdAt = now,
            activeUntil = nextRotationTime()
        )
    }

    private fun selectInterests(
        template: PersonaTemplate?,
        weightHints: Map<CategoryPool, Float>
    ): Set<CategoryPool> {
        val base = template?.interests
            ?.mapNotNull { runCatching { CategoryPool.valueOf(it) }.getOrNull() }
            ?.toSet() ?: emptySet()

        if (base.size >= 3) return base

        // Fill up to 5 interests using weight hints
        val pool = CategoryPool.values().toMutableList()
        pool.removeAll(base)

        val supplementary = if (weightHints.isNotEmpty()) {
            weightedSample(pool, weightHints, 5 - base.size)
        } else {
            pool.shuffled(random).take(5 - base.size)
        }

        return base + supplementary
    }

    private fun weightedSample(
        pool: List<CategoryPool>,
        weights: Map<CategoryPool, Float>,
        count: Int
    ): List<CategoryPool> {
        val result = mutableListOf<CategoryPool>()
        val remaining = pool.toMutableList()

        repeat(minOf(count, remaining.size)) {
            val totalWeight = remaining.sumOf { weights.getOrDefault(it, 1f).toDouble() }
            var threshold = random.nextDouble() * totalWeight
            val chosen = remaining.firstOrNull { cat ->
                threshold -= weights.getOrDefault(cat, 1f)
                threshold <= 0
            } ?: remaining.random(random)
            result.add(chosen)
            remaining.remove(chosen)
        }
        return result
    }

    /**
     * Returns true if the candidate persona matches the user's self-reported demographics
     * on 2 or more traits (ageRange, profession, region). Gender is intentionally excluded.
     * Returns false if the user has no profile (skipped onboarding).
     *
     * Candidates store demographics as enum names, so this compares against `enum.name`
     * directly. Before that canonicalization this method compared display strings, which
     * NEVER matched template-sourced candidates — the gate was silently inert.
     */
    private fun matchesUserDemographics(
        candidate: SyntheticPersona,
        userProfile: UserDemographicProfile?
    ): Boolean {
        if (userProfile == null) return false

        var matchCount = 0
        if (userProfile.ageRange != null && candidate.ageRange == userProfile.ageRange.name) matchCount++
        if (userProfile.profession != null && candidate.profession == userProfile.profession.name) matchCount++
        if (userProfile.region != null && candidate.region == userProfile.region.name) matchCount++
        return matchCount >= MIN_DEMOGRAPHIC_MATCHES
    }

    /**
     * Last resort when every one of [MAX_ATTEMPTS] candidates was rejected — either as too similar
     * to recent history or as a match for the user's own demographics.
     *
     * The demographic traits are deliberately fixed. They are the safety guarantee this fallback
     * exists to provide: when the sampled distribution collides with the user's real profile on
     * every attempt (see PersonaGeneratorJointSamplingTest), the generator must still emit a
     * persona that does NOT mirror the user. A mid-range, unremarkable trio is the safe choice.
     *
     * The NAME and INTERESTS are randomized (issue #275). They used to be fixed too, which meant
     * every install reaching this path converged on one identical identity ("Alex Johnson", always
     * COOKING/TRAVEL/FITNESS). That is a cross-user correlation tell — a broker seeing that exact
     * interest triple could bucket Fauxx users together, the precise opposite of what rotation is
     * for — and it read to the user as "rotation stopped", since the displayed name never changed
     * again. Interests also drive the Layer 3 category weights, so a constant set meant constant
     * targeting.
     *
     * Residual, accepted for now: the three demographic traits are still shared across every
     * fallback persona. That is a far weaker signal than the full identity, and it is load-bearing
     * for the guarantee above. Measured reach of this path is ~0% (EN) to ~0.2% (ru) of rotations.
     */
    private fun buildFallbackPersona(): SyntheticPersona {
        val now = clock.currentTimeMillis()
        return SyntheticPersona(
            id = UUID.randomUUID().toString(),
            name = generateName(),
            ageRange = AgeRange.AGE_35_44.name,
            profession = Profession.OTHER.name,
            region = "US_MIDWEST",
            interests = selectInterests(template = null, weightHints = emptyMap()),
            createdAt = now,
            activeUntil = nextRotationTime()
        )
    }

    private fun generateName(): String {
        val firstNames = listOf("Alex", "Morgan", "Jordan", "Taylor", "Casey", "Riley",
            "Drew", "Jamie", "Avery", "Peyton", "Sam", "Chris", "Dana", "Pat", "Jesse")
        val lastNames = listOf("Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller",
            "Davis", "Wilson", "Anderson", "Taylor", "Thomas", "Jackson", "White", "Harris")
        return "${firstNames.random(random)} ${lastNames.random(random)}"
    }

    private fun pickAgeRange(): String = AgeRange.entries.random(random).name

    private fun pickProfession(): String =
        listOf(Profession.ENGINEER, Profession.TEACHER, Profession.HEALTHCARE,
            Profession.FINANCE_PROF, Profession.RETAIL, Profession.RETIRED,
            Profession.STUDENT, Profession.HOMEMAKER, Profession.CREATIVE)
            .random(random).name

    private fun pickRegion(): String =
        listOf("US_NORTHEAST", "US_SOUTHEAST", "US_MIDWEST", "US_SOUTHWEST", "US_WEST",
            "CANADA", "UK", "WESTERN_EUROPE").random(random)

    private fun loadTemplates(locale: SupportedLocale): List<PersonaTemplate> {
        val localePath = "persona_templates/${locale.tag}.json"
        val legacyPath = "persona_templates.json"
        return try {
            val stream = runCatching { context.assets.open(localePath) }
                .getOrElse {
                    if (locale == SupportedLocale.EN) context.assets.open(legacyPath)
                    else throw it
                }
            val json = stream.bufferedReader().readText()
            val type = object : TypeToken<List<PersonaTemplate>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load persona_templates for locale=${locale.tag}")
            emptyList()
        }
    }
}

/**
 * JSON-mapped persona template from `persona_templates/{locale}.json`.
 *
 * @Keep: without this, R8 in release builds renames the field names, Gson's
 * reflection-based deserialization returns `LinkedTreeMap`-backed objects,
 * and the first access of `template.region` / `.ageRange` / `.interests`
 * throws `ClassCastException` — silently degrading Layer 3 to neutral
 * weights for the rest of the session.
 */
@Keep
private data class PersonaTemplate(
    val archetype: String = "",
    val ageRange: String = "",
    val interests: List<String> = emptyList(),
    val region: String = "",
    val profession: String = ""
)
