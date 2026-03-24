package com.fauxx.targeting.layer3

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.Log
import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private const val TAG = "PersonaGenerator"
private val NINETY_DAYS_MS = TimeUnit.DAYS.toMillis(90)

/**
 * Generates coherent [SyntheticPersona] instances by sampling from persona templates
 * and validating against consistency rules and recent history.
 *
 * A persona is rejected if:
 * - It fails [PersonaConsistencyRules.isValid]
 * - It shares >60% trait overlap with any persona from the past 90 days
 *
 * Falls back to a built-in generic persona if no valid persona can be generated after
 * [MAX_ATTEMPTS] tries.
 */
@Singleton
class PersonaGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val historyDao: PersonaHistoryDao
) {
    private val gson = Gson()
    private val templates: List<PersonaTemplate> by lazy { loadTemplates() }

    companion object {
        private const val MAX_ATTEMPTS = 10
        private val ROTATION_JITTER_DAYS = 1L..3L
        private val BASE_ROTATION_DAYS = 7L
    }

    /**
     * Generate a new persona, avoiding recent history and ensuring internal consistency.
     * [weightHints] can optionally bias interest selection toward high-weight categories.
     */
    suspend fun generate(weightHints: Map<CategoryPool, Float> = emptyMap()): SyntheticPersona {
        val cutoff = System.currentTimeMillis() - NINETY_DAYS_MS
        val recentEntries = historyDao.getRecentPersonas(cutoff)
        val recentPersonas = recentEntries.mapNotNull { entry ->
            runCatching {
                gson.fromJson(entry.personaJson, SyntheticPersona::class.java)
            }.getOrNull()
        }

        repeat(MAX_ATTEMPTS) {
            val candidate = buildPersona(weightHints)
            if (!PersonaConsistencyRules.isValid(candidate)) return@repeat

            val tooSimilar = recentPersonas.any { recent ->
                PersonaConsistencyRules.overlapFraction(candidate, recent) >
                    PersonaConsistencyRules.OVERLAP_THRESHOLD
            }
            if (!tooSimilar) return candidate
        }

        Log.w(TAG, "Could not generate unique persona after $MAX_ATTEMPTS attempts, using fallback")
        return buildFallbackPersona()
    }

    /** Calculate the next rotation timestamp with jitter. */
    fun nextRotationTime(): Long {
        val jitterDays = Random.nextLong(ROTATION_JITTER_DAYS.first, ROTATION_JITTER_DAYS.last + 1)
        val totalDays = BASE_ROTATION_DAYS + jitterDays
        return System.currentTimeMillis() + TimeUnit.DAYS.toMillis(totalDays)
    }

    private fun buildPersona(weightHints: Map<CategoryPool, Float>): SyntheticPersona {
        val template = if (templates.isNotEmpty()) templates.random() else null
        val interests = selectInterests(template, weightHints)
        val now = System.currentTimeMillis()

        return SyntheticPersona(
            id = UUID.randomUUID().toString(),
            name = generateName(),
            ageRange = template?.ageRange ?: pickAgeRange(),
            profession = template?.profession ?: pickProfession(),
            region = template?.region ?: pickRegion(),
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
            pool.shuffled().take(5 - base.size)
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
            var threshold = Random.nextDouble() * totalWeight
            val chosen = remaining.firstOrNull { cat ->
                threshold -= weights.getOrDefault(cat, 1f)
                threshold <= 0
            } ?: remaining.random()
            result.add(chosen)
            remaining.remove(chosen)
        }
        return result
    }

    private fun buildFallbackPersona(): SyntheticPersona {
        val now = System.currentTimeMillis()
        return SyntheticPersona(
            id = UUID.randomUUID().toString(),
            name = "Alex Johnson",
            ageRange = "35-44",
            profession = "Professional",
            region = "US_MIDWEST",
            interests = setOf(CategoryPool.COOKING, CategoryPool.TRAVEL, CategoryPool.FITNESS),
            createdAt = now,
            activeUntil = now + TimeUnit.DAYS.toMillis(7)
        )
    }

    private fun generateName(): String {
        val firstNames = listOf("Alex", "Morgan", "Jordan", "Taylor", "Casey", "Riley",
            "Drew", "Jamie", "Avery", "Peyton", "Sam", "Chris", "Dana", "Pat", "Jesse")
        val lastNames = listOf("Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller",
            "Davis", "Wilson", "Anderson", "Taylor", "Thomas", "Jackson", "White", "Harris")
        return "${firstNames.random()} ${lastNames.random()}"
    }

    private fun pickAgeRange(): String =
        listOf("18-24", "25-34", "35-44", "45-54", "55-64", "65+").random()

    private fun pickProfession(): String =
        listOf("Engineer", "Teacher", "Healthcare Worker", "Business Professional",
            "Retail Worker", "Retired", "Student", "Homemaker", "Creative").random()

    private fun pickRegion(): String =
        listOf("US_NORTHEAST", "US_SOUTHEAST", "US_MIDWEST", "US_SOUTHWEST", "US_WEST",
            "CANADA", "UK", "WESTERN_EUROPE").random()

    private fun loadTemplates(): List<PersonaTemplate> {
        return try {
            val json = context.assets.open("persona_templates.json")
                .bufferedReader().readText()
            val type = object : TypeToken<List<PersonaTemplate>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persona_templates.json", e)
            emptyList()
        }
    }
}

/** JSON-mapped persona template from persona_templates.json. */
private data class PersonaTemplate(
    val archetype: String = "",
    val ageRange: String = "",
    val interests: List<String> = emptyList(),
    val region: String = "",
    val profession: String = ""
)
