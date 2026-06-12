package com.fauxx.targeting.allocation

import android.content.Context
import androidx.annotation.Keep
import com.fauxx.data.querybank.CategoryPool
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One symmetric category-affinity edge as stored in the asset (one direction; the loader mirrors).
 *
 * @Keep: same R8 trap as [com.fauxx.targeting.layer3.DemographicCell] (issue #49) — without it
 * release builds strip the field names and Gson reflection yields LinkedTreeMap-backed objects.
 */
@Keep
internal data class AffinityEntry(val a: String = "", val b: String = "", val w: Double = 0.0)

/** Logistic coefficients for [BrokerSurrogate], shipped alongside the affinity matrix. */
@Keep
internal data class ModelParams(
    val bias: Double = 0.0,
    val selfCoef: Double = 0.0,
    val neighborCoef: Double = 0.0,
)

@Keep
internal data class CooccurrenceFile(
    val version: Int = 0,
    val provenance: String = "",
    val model: ModelParams = ModelParams(),
    val affinities: List<AffinityEntry> = emptyList(),
)

/**
 * Symmetric category co-occurrence prior plus logistic coefficients, consumed by [BrokerSurrogate]
 * (E4 #180). Built offline by `scripts/build_cooccurrence.py` and bundled as
 * `assets/ad_category_cooccurrence.json`.
 *
 * HONESTY: this is a DESIGNED prior (hand-curated semantic affinity clusters), NOT measured broker
 * statistics — no public source publishes co-occurrence over fauxx's own category taxonomy. It is a
 * research surrogate for how a broker might cluster interests, used only to decide where to place
 * adversarial noise; it never claims to mirror a real broker model. See the script's docstring.
 *
 * If the asset is missing or malformed the loader returns [empty], whose lack of neighbor structure
 * degrades the surrogate to an own-mass-only signal — never a crash, never a block.
 */
class CooccurrenceTable(
    private val neighbors: Map<CategoryPool, Map<CategoryPool, Double>>,
    val bias: Double,
    val selfCoef: Double,
    val neighborCoef: Double,
) {
    /** Affinity in [0,1] between two distinct categories; 0 for the same category or no edge. */
    fun affinity(a: CategoryPool, b: CategoryPool): Double =
        if (a == b) 0.0 else neighbors[a]?.get(b) ?: 0.0

    /** Neighbors of [c] with non-zero affinity (empty if the table has no structure for it). */
    fun neighborsOf(c: CategoryPool): Map<CategoryPool, Double> = neighbors[c] ?: emptyMap()

    val isEmpty: Boolean get() = neighbors.isEmpty()

    companion object {
        // On-device defaults; kept in sync with scripts/build_cooccurrence.py MODEL.
        const val DEFAULT_BIAS = -2.0
        const val DEFAULT_SELF_COEF = 6.0
        const val DEFAULT_NEIGHBOR_COEF = 3.0

        /** Neutral table: no neighbor structure; the surrogate degrades to own-mass only. */
        fun empty(): CooccurrenceTable =
            CooccurrenceTable(emptyMap(), DEFAULT_BIAS, DEFAULT_SELF_COEF, DEFAULT_NEIGHBOR_COEF)
    }
}

/**
 * Loads the bundled co-occurrence asset into a [CooccurrenceTable], fail-safe: any parse problem
 * degrades to [CooccurrenceTable.empty] rather than throwing, mirroring
 * [com.fauxx.targeting.layer3.PersonaDistribution]'s load pattern.
 */
@Singleton
class CooccurrenceLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val gson = Gson()

    fun load(): CooccurrenceTable = try {
        val parsed = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            .let { gson.fromJson(it, CooccurrenceFile::class.java) }
        if (parsed == null) {
            Timber.w("ad_category_cooccurrence: null parse, using empty table")
            CooccurrenceTable.empty()
        } else {
            val neighbors = HashMap<CategoryPool, HashMap<CategoryPool, Double>>()
            for (e in parsed.affinities) {
                val a = runCatching { CategoryPool.valueOf(e.a) }.getOrNull() ?: continue
                val b = runCatching { CategoryPool.valueOf(e.b) }.getOrNull() ?: continue
                if (a == b) continue
                val w = e.w.coerceIn(0.0, 1.0)
                if (w <= 0.0) continue
                neighbors.getOrPut(a) { HashMap() }[b] = w
                neighbors.getOrPut(b) { HashMap() }[a] = w // symmetrize: asset stores one direction
            }
            val m = parsed.model
            // Guard against a model block that's missing/zeroed in a corrupt asset.
            val degenerate = m.selfCoef == 0.0 && m.neighborCoef == 0.0
            CooccurrenceTable(
                neighbors,
                bias = if (degenerate) CooccurrenceTable.DEFAULT_BIAS else m.bias,
                selfCoef = if (degenerate) CooccurrenceTable.DEFAULT_SELF_COEF else m.selfCoef,
                neighborCoef = if (degenerate) CooccurrenceTable.DEFAULT_NEIGHBOR_COEF else m.neighborCoef,
            )
        }
    } catch (e: Exception) {
        Timber.w(e, "ad_category_cooccurrence.json unusable, using empty table")
        CooccurrenceTable.empty()
    }

    companion object {
        const val ASSET_PATH = "ad_category_cooccurrence.json"
    }
}
