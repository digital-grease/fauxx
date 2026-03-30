package com.fauxx.engine.modules

import android.content.Context
import android.content.Intent
import android.net.Uri
import timber.log.Timber
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** App store search URLs per category for off-profile attribution signals. */
private val CATEGORY_APP_URLS = mapOf(
    CategoryPool.GAMING to "https://play.google.com/store/search?q=strategy+games&c=apps",
    CategoryPool.FITNESS to "https://play.google.com/store/search?q=fitness+tracker&c=apps",
    CategoryPool.COOKING to "https://play.google.com/store/search?q=recipe+app&c=apps",
    CategoryPool.TRAVEL to "https://play.google.com/store/search?q=travel+planning&c=apps",
    CategoryPool.FINANCE to "https://play.google.com/store/search?q=budget+finance&c=apps",
    CategoryPool.MEDICAL to "https://play.google.com/store/search?q=health+medical&c=apps",
    CategoryPool.SPORTS to "https://play.google.com/store/search?q=sports+scores&c=apps"
)

private const val DEFAULT_APP_URL =
    "https://play.google.com/store/search?q=productivity+tools&c=apps"

/**
 * Opens deep links and app store pages for off-profile apps to trigger attribution pixel fires.
 */
@Singleton
class AppSignalModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepo: PoisonProfileRepository
) : Module {

    override suspend fun start() {}
    override suspend fun stop() {}

    override fun isEnabled(): Boolean = profileRepo.getProfile().appSignalEnabled

    override suspend fun onAction(category: CategoryPool): ActionLogEntity {
        val url = CATEGORY_APP_URLS[category] ?: DEFAULT_APP_URL

        try {
            // Use implicit intent — fires attribution pixels without opening full browser
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Note: this will open Play Store or browser — guard with try/catch
            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.w("Failed to open app signal URL: ${e.message}")
        }

        return ActionLogEntity(
            actionType = ActionType.DEEP_LINK_VISIT,
            category = category,
            detail = url
        )
    }
}
