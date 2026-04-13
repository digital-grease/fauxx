package com.fauxx.targeting.layer2

import android.content.Context
import timber.log.Timber
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fauxx.engine.webview.PhantomWebViewPool
import com.fauxx.targeting.layer2.scrapers.FacebookAdsScraper
import com.fauxx.targeting.layer2.scrapers.GoogleAdsScraper
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val WORK_NAME = "fauxx_scrape"

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
}

/**
 * Hilt-injected WorkManager worker that runs the scraping logic.
 * On ANY failure: logs, keeps stale cache, returns SUCCESS (so WorkManager reschedules).
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

        var anySuccess = false

        for (scraper in listOf(googleScraper, facebookScraper)) {
            try {
                val rawCategories = scraper.scrape(scraperWebView)
                if (rawCategories.isEmpty()) {
                    Timber.d("${scraper.platformId}: no categories found (may need auth)")
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
                anySuccess = true

            } catch (e: Exception) {
                Timber.e(e, "${scraper.platformId} scrape failed")
                // Keep existing cache — do not clear on failure
            }
        }

        withContext(Dispatchers.Main) {
            webViewPool.release(scraperWebView)
        }

        if (!anySuccess) {
            Timber.w("All scrapers failed — scheduling retry")
            return Result.retry()
        }
        return Result.success()
    }
}
