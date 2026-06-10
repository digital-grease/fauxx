package com.fauxx.targeting.layer3

import timber.log.Timber
import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.util.Clock
import com.fauxx.util.SystemClockImpl
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Interval between persona expiry checks. */
private const val ROTATION_CHECK_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes

/** Weight for categories aligned with the current persona. */
private const val ALIGNED_WEIGHT = 2.0f

/** Weight for categories misaligned with the current persona. */
private const val MISALIGNED_WEIGHT = 0.3f

/** Neutral weight (layer disabled / no persona): multiplicative identity. */
private const val NEUTRAL_WEIGHT = 1.0f

/**
 * Uniform-baseline component of the persona blend, lowered from 1.0 by E9 (#176).
 * This term is where the "Layer 0 uniform pull" mechanically lives: UniformEntropyLayer's
 * own constant is normalization-invariant for any value that keeps combined products
 * above the normalizer floor (see its KDoc), so reducing the uniform flattening means
 * shrinking the (1 - PERSONA_FOLLOW_FRACTION) blend-in here.
 */
private const val UNIFORM_BASELINE_WEIGHT = 0.6f

/**
 * Layer 3 of the Demographic Distancing Engine — persona rotation targeting.
 *
 * Generates a new [SyntheticPersona] every 7±3 days and returns category weights from the
 * raw constants (ALIGNED 2.0 / MISALIGNED 0.3) blended at PERSONA_FOLLOW_FRACTION with
 * the uniform baseline; 1.0 (neutral) when the layer is disabled or has no persona.
 *
 * E9 (#176) rebalance: an 85% persona-following / 15% reduced-uniform (0.6) blend, so
 * aligned categories land at 2.0*0.85 + 0.6*0.15 = 1.79 and misaligned at
 * 0.3*0.85 + 0.6*0.15 = 0.345 — a decisively persona-led (peakier) distribution versus
 * the pre-E9 1.7/0.51. With this layer alone a misaligned category keeps ~1.9% of the
 * normalized mass (~19x the [com.fauxx.targeting.WeightNormalizer.MIN_WEIGHT] floor;
 * the floor itself guarantees non-collapse even when L1/L2 shape the stack further).
 *
 * The weights channel participates in E8's staggered persona adoption (see
 * [personaForChannel]): after an automatic rotation it keeps serving the previous
 * persona's blend until its [PersonaChannel.WEIGHTS] lag elapses, so the category
 * distribution does not step in the same instant as the other bound channels. A
 * user-forced [rotateNow] adopts immediately on all channels — an explicit user action
 * should produce visible change, and it is not the weekly automatic change-point.
 */
@Singleton
class PersonaRotationLayer @Inject constructor(
    private val generator: PersonaGenerator,
    private val historyDao: PersonaHistoryDao,
    private val clock: Clock = SystemClockImpl(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val _currentPersona = MutableStateFlow<SyntheticPersona?>(null)
    private val _enabled = MutableStateFlow(false)

    val currentPersona: Flow<SyntheticPersona?> = _currentPersona.asStateFlow()

    /** Persona that was current before the last rotation; in-memory only (see below). */
    private val _previousPersona = MutableStateFlow<SyntheticPersona?>(null)

    /**
     * The persona a given engine channel should BIND to (E8 #174): non-null only while
     * Layer 3 is enabled. The user's Layer 3 toggle is the single persona kill switch —
     * when off, location spoofing, app signals, and rate modulation must all fall back
     * to their unbound behavior, not keep following a stale persona.
     *
     * STAGGERED ADOPTION: if every channel switched personas at the same instant,
     * rotation would be a synchronized multi-channel change-point (region, interest
     * mix, and daily rhythm all stepping together) — a clean segmentation boundary no
     * real human produces. Each channel therefore keeps serving the PREVIOUS persona
     * for a deterministic per-(persona, channel) lag of up to [CHANNEL_MAX_LAG_MS]
     * after rotation, so the new identity phases in channel by channel over hours to
     * days. The previous persona is held in memory only: after a process restart a
     * channel still inside its lag window adopts the current persona early, which
     * degrades smoothly (one fewer staggered step) rather than incoherently.
     */
    fun personaForChannel(channel: PersonaChannel): SyntheticPersona? {
        if (!_enabled.value) return null
        val current = _currentPersona.value ?: return null
        val previous = _previousPersona.value ?: return current
        val sinceRotation = clock.currentTimeMillis() - current.createdAt
        return if (sinceRotation < adoptionLagMs(current.id, channel)) previous else current
    }

    /** Deterministic adoption lag in 0..[CHANNEL_MAX_LAG_MS] per (persona, channel). */
    @androidx.annotation.VisibleForTesting
    internal fun adoptionLagMs(personaId: String, channel: PersonaChannel): Long =
        "$personaId:${channel.name}".hashCode().mod(CHANNEL_MAX_LAG_MS.toInt()).toLong()

    @androidx.annotation.VisibleForTesting
    internal fun setPersonasForTest(current: SyntheticPersona?, previous: SyntheticPersona?) {
        _currentPersona.value = current
        _previousPersona.value = previous
    }

    /**
     * Re-evaluation ticker for [_weights]. Staggered adoption is TIME-based, but a
     * flow only recomputes on emissions — the periodic check loop bumps this so the
     * weights channel adopts a rotated persona once its lag elapses (within one
     * check interval), not only on the next persona/enabled emission.
     */
    private val _weightsTick = MutableStateFlow(0L)

    /**
     * Stable [StateFlow] emitting the current Layer 3 weight map.
     * Recomputes whenever the persona state, the enabled flag, or the ticker changes;
     * the persona itself resolves through [personaForChannel] so the category
     * distribution honors E8's staggered adoption like every other bound channel.
     */
    private val _weights = combine(
        _currentPersona, _previousPersona, _enabled, _weightsTick
    ) { _, _, _, _ ->
        val persona = personaForChannel(PersonaChannel.WEIGHTS)
        if (persona == null) {
            neutralWeights()
        } else {
            try {
                computeWeights(persona)
            } catch (e: Exception) {
                Timber.e(e, "Failed to compute persona weights, using neutral")
                neutralWeights()
            }
        }
    }.stateIn(scope, SharingStarted.Eagerly, neutralWeights())

    init {
        // Single periodic check for persona expiry — replaces per-call scope.launch
        // to avoid spawning unbounded coroutines on every getWeights() call.
        scope.launch {
            while (isActive) {
                delay(ROTATION_CHECK_INTERVAL_MS)
                _weightsTick.value = clock.currentTimeMillis()
                val current = _currentPersona.value
                if (current != null && clock.currentTimeMillis() > current.activeUntil) {
                    rotatePersona()
                }
            }
        }
    }

    /** Enable or disable this layer. */
    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (enabled && _currentPersona.value == null) {
            // Issue #63: `_currentPersona` was in-memory only, so the FGS resume cycle
            // (process restarts on reboot/app-update and after long-pause resigns)
            // caused setEnabled() to fire on a fresh process with `_currentPersona ==
            // null`, generating a brand-new persona every restart. Users perceived
            // "persona rotates daily" instead of weekly.
            // Restore the most-recent still-active persona from history before falling
            // back to generation.
            scope.launch {
                val restored = restoreMostRecentActivePersona()
                if (restored != null) {
                    _currentPersona.value = restored
                    Timber.d(
                        "Restored persona ${restored.name} from history (active until ${restored.activeUntil})"
                    )
                } else {
                    rotatePersona()
                }
            }
        }
    }

    /**
     * Walk history for the most recently created persona whose `activeUntil` window has
     * not yet expired. Returns null when no eligible persona exists (history empty, or
     * every entry has expired). Suspending because it touches the DAO. Internal for tests.
     */
    internal suspend fun restoreMostRecentActivePersona(): SyntheticPersona? {
        val cutoff = clock.currentTimeMillis() - HISTORY_RETENTION_MS
        val entries = runCatching { historyDao.getRecentPersonas(cutoff) }.getOrNull() ?: return null
        val now = clock.currentTimeMillis()
        // `getRecentPersonas` already sorts DESC by createdAt — first deserializable
        // entry whose activeUntil is in the future is the right pick.
        return entries.asSequence()
            .mapNotNull { runCatching { gson.fromJson(it.personaJson, SyntheticPersona::class.java) }.getOrNull() }
            .firstOrNull { it.activeUntil > now }
    }

    /**
     * Force immediate persona rotation (e.g., user clicked "Rotate Now"). Unlike the
     * automatic weekly rotation, the new persona is adopted on ALL channels at once:
     * an explicit user action should produce visible change, and it is not the
     * recurring change-point the staggered adoption exists to blur.
     */
    fun rotateNow() {
        rotatePersona(immediateAdoption = true)
    }

    @androidx.annotation.VisibleForTesting
    internal fun reevaluateWeightsForTest() {
        _weightsTick.value = clock.currentTimeMillis()
    }

    /**
     * Emits the current Layer 3 weight map based on the active persona.
     * Returns the same stable [StateFlow] on every call.
     */
    fun getWeights(): Flow<Map<CategoryPool, Float>> = _weights

    private fun computeWeights(persona: SyntheticPersona): Map<CategoryPool, Float> =
        weightsFor(persona.interests)

    private fun neutralWeights(): Map<CategoryPool, Float> =
        CategoryPool.values().associateWith { NEUTRAL_WEIGHT }

    /**
     * Cancel the layer's coroutine scope. Call during application teardown
     * to prevent leaked coroutines.
     */
    fun destroy() {
        scope.cancel()
    }

    private fun rotatePersona(immediateAdoption: Boolean = false) {
        scope.launch {
            try {
                val newPersona = generator.generate()
                // Keep the outgoing persona so channels can phase the new one in
                // (see personaForChannel); a user-forced rotation adopts instantly.
                _previousPersona.value = if (immediateAdoption) null else _currentPersona.value
                _currentPersona.value = newPersona
                historyDao.insert(
                    PersonaHistoryEntity(
                        personaJson = gson.toJson(newPersona),
                        createdAt = newPersona.createdAt
                    )
                )
                // Prune old history beyond 90 days
                val cutoff = clock.currentTimeMillis() - HISTORY_RETENTION_MS
                historyDao.pruneOlderThan(cutoff)
            } catch (e: Exception) {
                Timber.e(e, "Failed to rotate persona, falling back to neutral weights")
            }
        }
    }

    companion object {
        /** 90 days in milliseconds. */
        private const val HISTORY_RETENTION_MS = 90L * 24 * 60 * 60 * 1000

        /** Upper bound for per-channel persona adoption lag: 48 hours. */
        internal const val CHANNEL_MAX_LAG_MS = 48L * 60 * 60 * 1000

        /**
         * The E9 persona blend as a pure function (also the unit-test seam for the
         * concentration property): persona-following fraction of the aligned/misaligned
         * weight plus the remaining fraction of the reduced uniform baseline.
         */
        internal fun weightsFor(interests: Set<CategoryPool>): Map<CategoryPool, Float> {
            val follow = SyntheticPersona.PERSONA_FOLLOW_FRACTION
            val aligned = ALIGNED_WEIGHT * follow + UNIFORM_BASELINE_WEIGHT * (1f - follow)
            val misaligned = MISALIGNED_WEIGHT * follow + UNIFORM_BASELINE_WEIGHT * (1f - follow)
            return CategoryPool.values().associateWith { category ->
                if (category in interests) aligned else misaligned
            }
        }
    }
}

/**
 * Engine channels that bind to the active persona (E8 #174). Each adopts a freshly
 * rotated persona after its own deterministic lag — see
 * [PersonaRotationLayer.personaForChannel]. WEIGHTS is the Layer 3 category
 * distribution itself (added by E9 #176: the peakier blend made an unstaggered
 * weights flip the sharpest remaining rotation change-point).
 */
enum class PersonaChannel { LOCATION, APP_SIGNAL, RHYTHM, WEIGHTS }
