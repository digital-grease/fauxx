package com.fauxx.targeting.layer3

import android.content.Context
import androidx.annotation.Keep
import com.fauxx.targeting.layer1.AgeRange
import com.fauxx.targeting.layer1.Profession
import com.fauxx.targeting.layer1.Region
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * One (ageRange, profession, region) cell of the joint demographic pmf. All three values
 * are enum names from the layer-1 taxonomy ("AGE_35_44", "FINANCE_PROF", "US_MIDWEST").
 *
 * @Keep: same R8 trap as `PersonaTemplate` (issue #49) — without it, release builds strip
 * the field names and Gson reflection silently yields `LinkedTreeMap`-backed objects.
 */
@Keep
data class DemographicCell(
    val age: String = "",
    val profession: String = "",
    val region: String = "",
    val weight: Double = 0.0,
)

@Keep
private data class DistributionFile(
    val version: Int = 0,
    val cells: List<DemographicCell> = emptyList(),
)

/**
 * Joint P(AgeRange, Profession, Region) over US adults, PWGTP-weighted from US Census
 * ACS PUMS microdata (E7 #173). Built offline by `scripts/build_persona_distribution.py`
 * and bundled as `assets/persona_distribution.json`.
 *
 * [sample] draws a whole cell at once, so the three traits CO-OCCUR the way they do in
 * the real population — a 22-year-old retiree in a region with no such people cannot be
 * drawn, unlike independent per-trait sampling.
 *
 * US-only by design (hybrid scope, ratified 2026-06-09): es/fr/ru locales keep their
 * hand-authored persona templates because PUMS covers only the US.
 *
 * If the asset is missing, malformed, or contains no usable cells, [sample] returns null
 * and [PersonaGenerator] falls back to its template path — never a crash.
 */
@Singleton
class PersonaDistribution @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val gson = Gson()

    /** Validated cells with precomputed cumulative weights; null if the asset is unusable. */
    private val table: Table? by lazy { load() }

    /**
     * Multinomial-sample one joint (age, profession, region) cell, or null when the
     * bundled distribution is unavailable.
     */
    fun sample(random: Random): DemographicCell? {
        val t = table ?: return null
        val x = random.nextDouble() * t.totalWeight
        // First index whose cumulative weight exceeds x (binary search).
        var lo = 0
        var hi = t.cumulative.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (t.cumulative[mid] <= x) lo = mid + 1 else hi = mid
        }
        return t.cells[lo]
    }

    private class Table(
        val cells: List<DemographicCell>,
        val cumulative: DoubleArray,
        val totalWeight: Double,
    )

    // The whole pipeline sits in one try/catch: Gson bypasses constructors, so a corrupt
    // asset can yield null list ELEMENTS and even null values in non-null String fields —
    // validation itself can throw and must degrade to the template fallback, not crash.
    // (`by lazy` re-evaluates after a throw, so an escaped exception here would resurface
    // on every sample() call.)
    private fun load(): Table? = try {
        val parsed = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            .let { gson.fromJson(it, DistributionFile::class.java) }
        val all = parsed?.cells.orEmpty().filterNotNull()
        val valid = all.filter(::isValidCell)
        if (valid.size < all.size) {
            Timber.w("persona_distribution: dropped ${all.size - valid.size} invalid cells")
        }
        if (valid.isEmpty()) {
            Timber.w("persona_distribution: no usable cells, falling back to templates")
            null
        } else {
            val cumulative = DoubleArray(valid.size)
            var sum = 0.0
            valid.forEachIndexed { i, cell ->
                sum += cell.weight
                cumulative[i] = sum
            }
            Table(valid, cumulative, sum)
        }
    } catch (e: Exception) {
        Timber.w(e, "persona_distribution.json unusable, falling back to templates")
        null
    }

    private fun isValidCell(cell: DemographicCell): Boolean =
        cell.weight > 0.0 &&
            runCatching { AgeRange.valueOf(cell.age) }.isSuccess &&
            runCatching { Profession.valueOf(cell.profession) }.isSuccess &&
            runCatching { Region.valueOf(cell.region) }.isSuccess &&
            cell.region.startsWith("US_")

    companion object {
        const val ASSET_PATH = "persona_distribution.json"
    }
}
