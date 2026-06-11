package com.fauxx.engine.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.annotation.VisibleForTesting
import com.fauxx.util.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/** Hours in a day — the fixed size of the circadian histogram. */
internal const val HOURS_PER_DAY = 24

/**
 * Lifecycle hook the engine drives so observers register/unregister their receivers in step
 * with the foreground service (E10 #177). [com.fauxx.engine.PoisonEngine] calls [start] when
 * it begins running and [stop] when it stops or is destroyed.
 */
interface UsageObserver {
    fun start()
    fun stop()

    companion object {
        /** No-op observer — the engine's default when circadian observation is not wired. */
        val NONE: UsageObserver = object : UsageObserver {
            override fun start() {}
            override fun stop() {}
        }
    }
}

/**
 * Read side of the circadian signal: a synchronous, 24-length snapshot of per-hour
 * observation counts. [CircadianRateModulator] reads this from the scheduler thread, which is
 * why it must be non-suspending — the source of truth is an in-memory array kept fresh by
 * [CircadianObserver], with the DB as the durable backing store.
 */
interface UsageHistogram {
    /** Per-hour-of-day (0-23) observation counts. Always length [HOURS_PER_DAY]. */
    fun hourlyCounts(): LongArray
}

/**
 * Learns the user's own daily rhythm from locally-observed screen-on events and exposes it as
 * a 24-bucket histogram (E10 #177).
 *
 * How it works:
 * - While the engine runs, a dynamically-registered [Intent.ACTION_SCREEN_ON] receiver fires
 *   each time the screen turns on. We bump the bucket for the current local hour. We store ONLY
 *   the per-hour aggregate count, never an event timestamp, so the persisted footprint is a
 *   coarse rhythm and nothing about any individual unlock.
 * - The histogram is held in memory (the scheduler's synchronous read path) and mirrored to
 *   the SQLCipher-encrypted [CircadianUsageDao] so a learned rhythm survives restarts.
 * - It is computed and stored entirely on-device and never leaves the device.
 *
 * Drift handling: when any bucket reaches [RESCALE_CAP] every bucket is halved. This bounds the
 * stored magnitude and gently ages old observations, so the rhythm tracks a user whose habits
 * shift over weeks rather than being frozen by the first month of data.
 *
 * Concurrency: the in-memory [buckets] are guarded by [lock]; ALL database access (load,
 * persist, wipe) is serialized through [dbMutex]. A [generation] counter, bumped by [clear],
 * lets a wipe supersede any persist whose snapshot predates it, so "Clear My Profile" can never
 * be silently undone by an in-flight persist that lands after the delete.
 *
 * Observation only happens while the engine is active (the receiver is unregistered on
 * [stop]); this is the same window the engine itself runs in, so the learned rhythm reflects
 * exactly the periods the user has Fauxx enabled.
 *
 * The signal is consumed by [CircadianRateModulator] and composed with the persona rhythm in
 * [CompositeRateModulator]; until [CircadianRateModulator.MIN_OBSERVATIONS] events have
 * accumulated the modulator stays neutral, so the scheduler falls back to its fixed window.
 */
@Singleton
class CircadianObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: CircadianUsageDao,
    private val clock: Clock,
) : UsageObserver, UsageHistogram {

    private val lock = Any()

    /** Per-hour counts. Guarded by [lock]. */
    private val buckets = LongArray(HOURS_PER_DAY)

    /** Serializes every DB operation (getAll / upsertAll / deleteAll) so writes can't race. */
    private val dbMutex = Mutex()

    /**
     * Bumped by [clear] under [lock]; a persist captures it with its snapshot and the DB write
     * is skipped if it has since advanced. This is what makes a wipe win against a concurrent
     * or already-queued persist. Volatile because the persist coroutine re-reads it outside
     * [lock] when deciding whether its write is still current.
     */
    @Volatile
    private var generation = 0L

    /** Wall-clock ms of the last persist; throttles per-event writes. Guarded by [lock]. */
    private var lastPersistMs = Long.MIN_VALUE

    /**
     * Own scope for DB load/persist. Mirrors [com.fauxx.engine.PoisonProfileRepository]'s
     * pattern — fire-and-forget IO that must not block the broadcast/main thread.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * True once the persisted histogram has been merged into memory. Until then we do NOT
     * persist, so an event arriving mid-load can't overwrite the DB with a history-less
     * snapshot. Volatile: set on the IO scope, read on the broadcast thread.
     */
    @Volatile
    private var loaded = false

    @Volatile
    private var registered = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) onScreenOn()
        }
    }

    override fun start() {
        // Load the persisted rhythm into memory so it is available immediately (and so a
        // matured histogram keeps modulating from the very first action after a restart).
        scope.launch { loadFromDb() }
        registerReceiver()
    }

    override fun stop() {
        unregisterReceiver()
        // Flush any throttled-but-unwritten observations now that the receiver is detached.
        if (loaded) persistSnapshot()
    }

    override fun hourlyCounts(): LongArray = synchronized(lock) { buckets.copyOf() }

    /**
     * Wipe the learned rhythm. Clears the in-memory snapshot synchronously so the modulator
     * falls back to neutral on the very next action, bumps [generation] so any in-flight
     * persist is voided, then deletes the persisted rows under [dbMutex]. Part of the
     * "Clear My Profile" / reset-to-defaults trail wipe.
     *
     * The DB delete is best-effort (a failed delete is logged, not thrown, to match the rest
     * of the reset path which is itself best-effort). If it fails the in-memory snapshot is
     * still zeroed, so the modulator is neutral immediately, and the next screen-on persists
     * the zeroed snapshot over the stale rows (REPLACE on every bucket), self-healing the DB.
     */
    suspend fun clear() {
        synchronized(lock) {
            buckets.fill(0L)
            // Stay "loaded": an empty histogram is a valid learned state, and we want
            // subsequent events to persist normally rather than re-merging stale DB rows.
            loaded = true
            generation++
        }
        dbMutex.withLock {
            runCatching { dao.deleteAll() }
                .onFailure { Timber.w(it, "Circadian: failed to clear persisted histogram") }
        }
    }

    private fun onScreenOn() {
        recordEvent(currentHourOfDay())
        // Throttle writes: at most one persist per PERSIST_THROTTLE_MS. The in-memory buckets
        // are the authoritative read path, so a coarser flush cadence risks only a handful of
        // recent observations on an abrupt kill (and stop() flushes on a clean teardown).
        val shouldPersist = synchronized(lock) {
            if (!loaded) return
            val now = clock.currentTimeMillis()
            if (now - lastPersistMs >= PERSIST_THROTTLE_MS) {
                lastPersistMs = now
                true
            } else {
                false
            }
        }
        if (shouldPersist) persistSnapshot()
    }

    /**
     * Increment the bucket for [hourOfDay] and apply drift rescaling. Pure in-memory mutation,
     * separated from broadcast/IO plumbing so the histogram logic is unit-testable.
     */
    @VisibleForTesting
    internal fun recordEvent(hourOfDay: Int) {
        if (hourOfDay !in 0 until HOURS_PER_DAY) return
        synchronized(lock) {
            buckets[hourOfDay]++
            if (buckets[hourOfDay] >= RESCALE_CAP) {
                for (i in buckets.indices) buckets[i] = buckets[i] / 2
            }
        }
    }

    private suspend fun loadFromDb() {
        if (loaded) return
        val rows = dbMutex.withLock {
            runCatching { dao.getAll() }
                .onFailure { Timber.w(it, "Circadian: failed to load histogram") }
                .getOrDefault(emptyList())
        }
        val persistNeeded = synchronized(lock) {
            // A clear() (or another load) may have won the race while getAll was suspended;
            // if so, leave its state intact rather than re-merging the rows we just read.
            if (loaded) return
            // Only the events buffered in memory during the load aren't yet on disk; if none
            // arrived, the merged state equals what we just read, so skip the redundant write.
            val hadDuringLoadEvents = buckets.any { it != 0L }
            for (r in rows) {
                if (r.hourOfDay in 0 until HOURS_PER_DAY) buckets[r.hourOfDay] += r.count
            }
            loaded = true
            hadDuringLoadEvents
        }
        if (persistNeeded) persistSnapshot()
    }

    private fun persistSnapshot() {
        val rows: List<CircadianUsageEntity>
        val gen: Long
        synchronized(lock) {
            rows = (0 until HOURS_PER_DAY).map { CircadianUsageEntity(it, buckets[it]) }
            gen = generation
        }
        scope.launch {
            dbMutex.withLock {
                // A clear() that ran after this snapshot was taken voids the write, so a wipe
                // is never silently undone by a stale persist landing after the delete.
                if (gen != generation) return@withLock
                runCatching { dao.upsertAll(rows) }
                    .onFailure { Timber.w(it, "Circadian: failed to persist histogram") }
            }
        }
    }

    private fun registerReceiver() {
        if (registered) return
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        runCatching {
            // SCREEN_ON is a protected system broadcast (cannot be declared in the manifest
            // since API 26) and requires dynamic registration. RECEIVER_NOT_EXPORTED on T+:
            // only the system delivers it, no other app can spoof it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(screenReceiver, filter)
            }
            registered = true
        }.onFailure { Timber.w(it, "Circadian: screen-on receiver registration failed") }
    }

    private fun unregisterReceiver() {
        if (!registered) return
        runCatching { context.unregisterReceiver(screenReceiver) }
        registered = false
    }

    private fun currentHourOfDay(): Int =
        Calendar.getInstance().apply { timeInMillis = clock.currentTimeMillis() }
            .get(Calendar.HOUR_OF_DAY)

    /** The wipe-supersedes-persist counter; a persist captured before a [clear] is voided. */
    @VisibleForTesting
    internal fun generationForTest(): Long = generation

    companion object {
        /**
         * When any bucket reaches this, all buckets are halved (drift decay + overflow guard).
         * At a heavy ~100 screen-ons/day concentrated in a few hours, a bucket reaches 10k in
         * a few months, after which each halving keeps the recent shape while fading the past.
         */
        @VisibleForTesting
        internal const val RESCALE_CAP = 10_000L

        /**
         * Minimum spacing between persists driven by screen-on events. A heavy user fires
         * ~100 events/day; without throttling that is ~100 full-table encrypted REPLACE writes.
         * Five minutes collapses bursts to a handful of writes while keeping the on-disk
         * histogram fresh enough (the in-memory copy is always current for the modulator).
         */
        @VisibleForTesting
        internal const val PERSIST_THROTTLE_MS = 5 * 60 * 1000L
    }
}
