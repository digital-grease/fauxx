package com.fauxx.targeting.layer2

import android.content.Context
import timber.log.Timber
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.fauxx.engine.webview.PhantomWebViewPool
import com.fauxx.targeting.layer2.scrapers.FacebookAdsScraper
import com.fauxx.targeting.layer2.scrapers.GoogleAdsScraper
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val WORK_NAME = "fauxx_scrape"
private const val ONE_SHOT_WORK_NAME = "fauxx_scrape_now"

/**
 * Schedules periodic ad-profile scraping via WorkManager.
 * Default period: 7 days. Runs on Wi-Fi only to avoid mobile data usage.
 *
 * On any failure: logs the error, keeps existing cache, returns gracefully.
 */
@Singleton
class ScrapeScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Enqueue a periodic scrape job if not already scheduled.
     * Uses KEEP policy to avoid restarting an already-running job.
     */
    fun schedule() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        val request = PeriodicWorkRequestBuilder<ScrapeWorker>(7, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        Timber.d("Scrape job scheduled")
    }

    /** Cancel the scheduled scrape job. */
    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * Enqueue a one-time immediate scrape, independent of the periodic schedule. Uses
     * [NetworkType.CONNECTED] (not [NetworkType.UNMETERED]) so a user on cellular can
     * still kick a manual refresh — the periodic job is the appropriate place to be
     * WiFi-strict, but the manual button is a user-initiated explicit action.
     *
     * Returns the work request UUID so callers can observe state via
     * [WorkManager.getWorkInfoByIdFlow].
     */
    fun scrapeNow(): UUID {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<ScrapeWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
        Timber.d("One-shot scrape enqueued (id=${request.id})")
        return request.id
    }
}

/**
 * Hilt-injected WorkManager worker that runs the scraping logic.
 *
 * Distinguishes three outcomes via [Result] output data so the in-app UI can
 * surface a "you need to log in" dialog rather than silently flashing "Failed"
 * (issue follow-up to #39: users have no way to know that empty results almost
 * always mean they're not signed into the ad-platform pages):
 *
 * - [OUTCOME_SUCCESS]: at least one platform returned categories.
 * - [OUTCOME_NEEDS_LOGIN]: every platform returned an empty list with no thrown
 *   exception — almost always means the user isn't signed into the ad-platform
 *   pages, since real ad profiles are rarely empty for active accounts.
 * - [OUTCOME_FAILED]: at least one platform threw (network/parse/timeout) and
 *   nothing succeeded.
 */
@HiltWorker
class ScrapeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val googleScraper: GoogleAdsScraper,
    private val facebookScraper: FacebookAdsScraper,
    private val categoryMapper: CategoryMapper,
    private val platformProfileDao: PlatformProfileDao,
    private val webViewPool: PhantomWebViewPool
) : CoroutineWorker(context, workerParams) {

    private val gson = Gson()

    override suspend fun doWork(): Result {
        Timber.i("Starting ad profile scrape")
        webViewPool.initialize()

        val scraperWebView = withContext(Dispatchers.Main) {
            webViewPool.acquireForScraper()
        }

        val scrapers = listOf(googleScraper, facebookScraper)
        val succeeded = mutableListOf<String>()
        val emptyResult = mutableListOf<String>()
        val errored = mutableListOf<String>()

        for (scraper in scrapers) {
            try {
                val rawCategories = scraper.scrape(scraperWebView)
                if (rawCategories.isEmpty()) {
                    Timber.d("${scraper.platformId}: no categories found (likely not signed in)")
                    emptyResult.add(scraper.platformId)
                    continue
                }

                val mapped = categoryMapper.mapAll(rawCategories)
                val json = gson.toJson(mapped.map { it.name })

                platformProfileDao.upsert(
                    PlatformProfileCache(
                        platformName = scraper.platformId,
                        scrapedCategoriesJson = json,
                        lastScraped = System.currentTimeMillis()
                    )
                )
                Timber.i("${scraper.platformId}: scraped ${mapped.size} categories")
                succeeded.add(scraper.platformId)

            } catch (e: Exception) {
                Timber.e(e, "${scraper.platformId} scrape failed")
                errored.add(scraper.platformId)
                // Keep existing cache — do not clear on failure
            }
        }

        withContext(Dispatchers.Main) {
            webViewPool.release(scraperWebView)
        }

        val outcome = when {
            succeeded.isNotEmpty() -> OUTCOME_SUCCESS
            // All non-success and at least one returned cleanly empty — treat as
            // "needs login." Real ad-platform profiles for active accounts are
            // never empty; the only way to get all-empty is to not be signed in.
            emptyResult.isNotEmpty() && errored.isEmpty() -> OUTCOME_NEEDS_LOGIN
            else -> OUTCOME_FAILED
        }
        val output = workDataOf(KEY_OUTCOME to outcome)

        return when (outcome) {
            OUTCOME_SUCCESS -> {
                if (emptyResult.isNotEmpty() || errored.isNotEmpty()) {
                    Timber.w("Partial scrape: succeeded=${succeeded.joinToString()}, empty=${emptyResult.joinToString()}, errored=${errored.joinToString()}")
                }
                Result.success(output)
            }
            OUTCOME_NEEDS_LOGIN -> {
                Timber.w("Scrape produced no categories on any platform — likely not signed in (${emptyResult.joinToString()})")
                // Do not Result.retry() — retrying doesn't help; the user has to act.
                Result.failure(output)
            }
            else -> {
                Timber.w("Scrape errored on all platforms (${errored.joinToString()}) — retrying")
                Result.retry()
            }
        }
    }

    companion object {
        const val KEY_OUTCOME = "outcome"
        const val OUTCOME_SUCCESS = "success"
        const val OUTCOME_NEEDS_LOGIN = "needs_login"
        const val OUTCOME_FAILED = "failed"
    }
}
