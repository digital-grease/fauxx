package com.fauxx.targeting.layer3

import timber.log.Timber
import com.fauxx.data.model.SyntheticPersona
import com.fauxx.data.querybank.CategoryPool
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
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Weight for categories aligned with the current persona. */
private const val ALIGNED_WEIGHT = 2.0f

/** Weight for categories misaligned with the current persona. */
private const val MISALIGNED_WEIGHT = 0.3f

/** Neutral weight. */
private const val NEUTRAL_WEIGHT = 1.0f

/**
 * Layer 3 of the Demographic Distancing Engine — persona rotation targeting.
 *
 * Generates a new [SyntheticPersona] every 7±3 days and returns category weights based on
 * the current persona's interests:
 * - 2.0 for persona-aligned categories
 * - 0.3 for persona-misaligned categories
 * - 1.0 for uncategorized or when layer is disabled
 *
 * Uses a 70% persona-following / 30% uniform blend for natural variation.
 */
@Singleton
class PersonaRotationLayer @Inject constructor(
    private val generator: PersonaGenerator,
    private val historyDao: PersonaHistoryDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val _currentPersona = MutableStateFlow<SyntheticPersona?>(null)
    private val _enabled = MutableStateFlow(false)

    val currentPersona: Flow<SyntheticPersona?> = _currentPersona.asStateFlow()

    /**
     * Stable [StateFlow] emitting the current Layer 3 weight map.
     * Recomputes reactively whenever the current persona or enabled flag changes.
     */
    private val _weights = combine(_currentPersona, _enabled) { persona, enabled ->
        if (!enabled || persona == null) {
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

    /** Enable or disable this layer. */
    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (enabled && _currentPersona.value == null) {
            rotatePersona()
        }
    }

    /**
     * Force immediate persona rotation (e.g., user clicked "Rotate Now").
     */
    fun rotateNow() {
        rotatePersona()
    }

    /**
     * Emits the current Layer 3 weight map based on the active persona.
     * Returns the same stable [StateFlow] on every call — no coroutine leak.
     */
    fun getWeights(): Flow<Map<CategoryPool, Float>> {
        // Check if persona needs rotation (once per call is fine — idempotent)
        scope.launch {
            val current = _currentPersona.value
            if (current != null && System.currentTimeMillis() > current.activeUntil) {
                rotatePersona()
            }
        }
        return _weights
    }

    private fun computeWeights(persona: SyntheticPersona): Map<CategoryPool, Float> {
        return CategoryPool.values().associateWith { category ->
            when {
                persona.interests.contains(category) -> {
                    // 70% persona-following, 30% uniform blend
                    ALIGNED_WEIGHT * SyntheticPersona.PERSONA_FOLLOW_FRACTION +
                        NEUTRAL_WEIGHT * (1f - SyntheticPersona.PERSONA_FOLLOW_FRACTION)
                }
                else -> {
                    MISALIGNED_WEIGHT * SyntheticPersona.PERSONA_FOLLOW_FRACTION +
                        NEUTRAL_WEIGHT * (1f - SyntheticPersona.PERSONA_FOLLOW_FRACTION)
                }
            }
        }
    }

    private fun neutralWeights(): Map<CategoryPool, Float> =
        CategoryPool.values().associateWith { NEUTRAL_WEIGHT }

    /**
     * Cancel the layer's coroutine scope. Call during application teardown
     * to prevent leaked coroutines.
     */
    fun destroy() {
        scope.cancel()
    }

    private fun rotatePersona() {
        scope.launch {
            try {
                val newPersona = generator.generate()
                _currentPersona.value = newPersona
                historyDao.insert(
                    PersonaHistoryEntity(
                        personaJson = gson.toJson(newPersona),
                        createdAt = newPersona.createdAt
                    )
                )
                // Prune old history beyond 90 days
                val cutoff = System.currentTimeMillis() - HISTORY_RETENTION_MS
                historyDao.pruneOlderThan(cutoff)
            } catch (e: Exception) {
                Timber.e(e, "Failed to rotate persona, falling back to neutral weights")
            }
        }
    }

    companion object {
        /** 90 days in milliseconds. */
        private const val HISTORY_RETENTION_MS = 90L * 24 * 60 * 60 * 1000
    }
}
