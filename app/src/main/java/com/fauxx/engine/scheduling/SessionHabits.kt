package com.fauxx.engine.scheduling

import com.fauxx.data.device.DeviceDeriver
import com.fauxx.di.QueryGrammarSeed
import com.fauxx.targeting.layer3.PersonaChannel
import com.fauxx.targeting.layer3.PersonaRotationLayer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How one persona's browsing is shaped in time (#322 item 1): how fast it browses inside a session,
 * how many actions it takes on one topic before pausing, how long it reads between pages, how
 * readily it stays on a topic, how long its breaks between sessions run, and how often it stops a
 * session before its budget is spent; and how often and how far a page visit browses on within its
 * site.
 *
 * Drawn per persona from ranges, so installs and personas do not share one rhythm, and fixed for
 * the persona's life. [SessionScheduler] turns them into delays.
 *
 * @property sessionPace In-session rate as a multiple of the intensity's rate. Above 1, a session
 *   spends the budget faster than it accrues, which is what ends it; around 2, the engine browses
 *   about half the time and rests the other half. Only the calmer tiers can reach it: at High and
 *   Max the actions' own run time is the limit.
 * @property burstMean Mean number of actions in a burst on one topic.
 * @property thinkMedianMs Median pause after an action before the next one in the same burst,
 *   on top of the action's own dwell on the page.
 * @property stayOnTopic Chance that the next burst in a session keeps the previous burst's topic.
 * @property breakMedianMs Median break between sessions, before the budget shortens it.
 * @property endEarly Chance that a session stops partway, at the end of a burst, with part of its
 *   budget left for the next one.
 * @property browseChance Chance that a page visit browses on to more pages of the same site
 *   (#322 item 2).
 * @property browseMean Mean number of pages browsed on to when it does, before the tier's cap.
 */
data class SessionHabits(
    val sessionPace: Double,
    val burstMean: Double,
    val thinkMedianMs: Long,
    val stayOnTopic: Double,
    val breakMedianMs: Long,
    val endEarly: Double,
    val browseChance: Double,
    val browseMean: Double,
) {
    companion object {
        /**
         * Ranges each persona's values are drawn from (per mille for the fractions and multiples).
         * Tuned with the stream-shape report (StreamShapeReportTest) against the #270 history:
         * about 4 sessions a day, in-session gaps with a median of about 8 s and a p90 of about
         * 2.5 min, and breaks of minutes to hours.
         */
        private val SESSION_PACE_PER_MILLE = 1_800..2_600
        private val BURST_MEAN_PER_MILLE = 2_500..4_000
        private val THINK_MEDIAN_MS = 1_000..3_000
        private val STAY_ON_TOPIC_PER_MILLE = 350..650
        private val BREAK_MEDIAN_MIN = 30..60
        private val END_EARLY_PER_MILLE = 100..300
        private val BROWSE_CHANCE_PER_MILLE = 450..800
        private val BROWSE_MEAN_PER_MILLE = 1_500..3_000

        private const val DOMAIN = "session"

        /** Mid-range habits: what [SessionHabitsSource.DEFAULT] gives tests that need no persona. */
        val DEFAULT = SessionHabits(
            sessionPace = 2.2,
            burstMean = 3.0,
            thinkMedianMs = 2_000L,
            stayOnTopic = 0.5,
            breakMedianMs = 45L * 60 * 1000,
            endEarly = 0.2,
            browseChance = 0.6,
            browseMean = 2.0,
        )

        /**
         * Derive the habits for [key] (a persona id, or an install key when Layer 3 is off) with the
         * same portable hash as [DeviceDeriver.pick] and no PRNG, so another implementation
         * (fauxx-desktop) derives the same habits from the same synced persona.
         */
        fun derive(key: String): SessionHabits = SessionHabits(
            sessionPace = perMille(key, "pace", SESSION_PACE_PER_MILLE),
            burstMean = perMille(key, "burst", BURST_MEAN_PER_MILLE),
            thinkMedianMs = draw(key, "think", THINK_MEDIAN_MS).toLong(),
            stayOnTopic = perMille(key, "topic", STAY_ON_TOPIC_PER_MILLE),
            breakMedianMs = draw(key, "break", BREAK_MEDIAN_MIN) * 60_000L,
            endEarly = perMille(key, "end-early", END_EARLY_PER_MILLE),
            browseChance = perMille(key, "browse", BROWSE_CHANCE_PER_MILLE),
            browseMean = perMille(key, "browse-mean", BROWSE_MEAN_PER_MILLE),
        )

        private fun draw(key: String, field: String, range: IntRange): Int =
            range.first + DeviceDeriver.pick(key, "$DOMAIN:$field", 0, range.last - range.first + 1)

        private fun perMille(key: String, field: String, range: IntRange): Double =
            draw(key, field, range) / 1000.0
    }
}

/** Supplies the active [SessionHabits] to [SessionScheduler]. */
fun interface SessionHabitsSource {
    fun habits(): SessionHabits

    companion object {
        /** Always [SessionHabits.DEFAULT]. */
        val DEFAULT = SessionHabitsSource { SessionHabits.DEFAULT }
    }
}

/**
 * Habits from the active persona, through its [PersonaChannel.RHYTHM] channel, the one its daily
 * rhythm already follows, so a rotation changes the persona's pace and its hours together. With
 * Layer 3 off there is no persona, so the habits come from the per-install seed and stay fixed for
 * the install.
 */
@Singleton
class PersonaSessionHabits @Inject constructor(
    private val personaLayer: PersonaRotationLayer,
    private val installSeed: QueryGrammarSeed,
) : SessionHabitsSource {
    /** Resolved on first use, which is on the engine's background dispatcher. */
    private val installKey by lazy { "install:${installSeed.load()}" }

    override fun habits(): SessionHabits =
        SessionHabits.derive(personaLayer.personaForChannel(PersonaChannel.RHYTHM)?.id ?: installKey)
}
