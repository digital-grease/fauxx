package com.fauxx.engine.modules

import android.content.Context
import android.content.Intent
import android.net.Uri
import timber.log.Timber
import com.fauxx.data.db.ActionLogEntity
import com.fauxx.data.model.ActionType
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.engine.PoisonProfileRepository
import com.fauxx.locale.LocaleManager
import com.fauxx.locale.SupportedLocale
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-locale Play Store search-keyword table. The active locale's entry seeds the
 * deep-link URL; if a locale is missing a category (or the locale itself is missing
 * from the table), the EN entry is used as fallback. In Phase 2 only the EN row is
 * populated; ES and FR rows are intentionally empty pending native-speaker translation
 * in Phase 3 (translated keywords must actually return results on the localized Play
 * Store storefront — `meditación astrología bienestar` rather than a direct rendering
 * of `meditation astrology wellness`, etc.).
 */
private val CATEGORY_APP_KEYWORDS: Map<SupportedLocale, Map<CategoryPool, String>> = mapOf(
    SupportedLocale.EN to mapOf(
        CategoryPool.GAMING to "strategy+games",
        CategoryPool.FITNESS to "fitness+tracker",
        CategoryPool.COOKING to "recipe+app",
        CategoryPool.TRAVEL to "travel+planning",
        CategoryPool.FINANCE to "budget+finance",
        CategoryPool.MEDICAL to "health+medical",
        CategoryPool.SPORTS to "sports+scores",
        CategoryPool.OUTDOOR_RECREATION to "hiking+trails+outdoor",
        CategoryPool.CRAFTS to "craft+ideas+DIY",
        CategoryPool.HISTORY to "history+trivia+museum",
        CategoryPool.ENVIRONMENT to "carbon+footprint+sustainability",
        CategoryPool.MILITARY_DEFENSE to "military+veteran+benefits",
        CategoryPool.WELLNESS_ALTERNATIVE to "meditation+astrology+wellness",
        CategoryPool.RELATIONSHIPS_DATING to "dating+relationships"
    ),
    SupportedLocale.ES to mapOf(
        CategoryPool.GAMING to "juegos+estrategia",
        CategoryPool.FITNESS to "rastreador+fitness",
        CategoryPool.COOKING to "recetas+cocina",
        CategoryPool.TRAVEL to "planificar+viaje",
        CategoryPool.FINANCE to "presupuesto+finanzas",
        CategoryPool.MEDICAL to "salud+medicina",
        CategoryPool.SPORTS to "resultados+deportes",
        CategoryPool.OUTDOOR_RECREATION to "senderismo+rutas+naturaleza",
        CategoryPool.CRAFTS to "manualidades+ideas+DIY",
        CategoryPool.HISTORY to "historia+museo+trivia",
        CategoryPool.ENVIRONMENT to "huella+carbono+sostenibilidad",
        CategoryPool.MILITARY_DEFENSE to "veteranos+militar+ayudas",
        CategoryPool.WELLNESS_ALTERNATIVE to "meditación+astrología+bienestar",
        CategoryPool.RELATIONSHIPS_DATING to "citas+relaciones"
    ),
    SupportedLocale.FR to mapOf(
        CategoryPool.GAMING to "jeux+stratégie",
        CategoryPool.FITNESS to "tracker+fitness",
        CategoryPool.COOKING to "recettes+cuisine",
        CategoryPool.TRAVEL to "planifier+voyage",
        CategoryPool.FINANCE to "budget+finances",
        CategoryPool.MEDICAL to "santé+médical",
        CategoryPool.SPORTS to "résultats+sport",
        CategoryPool.OUTDOOR_RECREATION to "randonnée+nature+plein+air",
        CategoryPool.CRAFTS to "loisirs+créatifs+DIY",
        CategoryPool.HISTORY to "histoire+musée+culture",
        CategoryPool.ENVIRONMENT to "empreinte+carbone+écologie",
        CategoryPool.MILITARY_DEFENSE to "anciens+combattants+militaire",
        CategoryPool.WELLNESS_ALTERNATIVE to "méditation+astrologie+bien-être",
        CategoryPool.RELATIONSHIPS_DATING to "rencontres+couple"
    ),
    SupportedLocale.RU to mapOf(
        CategoryPool.GAMING to "стратегические+игры",
        CategoryPool.FITNESS to "фитнес+трекер",
        CategoryPool.COOKING to "рецепты+кулинария",
        CategoryPool.TRAVEL to "планирование+путешествий",
        CategoryPool.FINANCE to "учет+расходов+финансы",
        CategoryPool.MEDICAL to "здоровье+медицина",
        CategoryPool.SPORTS to "спортивные+результаты",
        CategoryPool.OUTDOOR_RECREATION to "походы+маршруты+природа",
        CategoryPool.CRAFTS to "рукоделие+идеи+DIY",
        CategoryPool.HISTORY to "история+музеи+викторины",
        CategoryPool.ENVIRONMENT to "экология+устойчивость",
        CategoryPool.MILITARY_DEFENSE to "военная+история+ветераны",
        CategoryPool.WELLNESS_ALTERNATIVE to "медитация+астрология+самочувствие",
        CategoryPool.RELATIONSHIPS_DATING to "знакомства+отношения"
    )
)

private const val DEFAULT_KEYWORDS = "productivity+tools"

/**
 * Opens deep links and app store pages for off-profile apps to trigger attribution pixel fires.
 *
 * Localized via [LocaleManager]: the Play Store URL gains `&hl=<lang>` and the search
 * keywords are picked from the active locale's bank (with EN fallback for any
 * category not yet translated for that locale).
 */
@Singleton
class AppSignalModule @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepo: PoisonProfileRepository,
    private val localeManager: LocaleManager
) : Module {

    override suspend fun start() {}
    override suspend fun stop() {}

    override fun isEnabled(): Boolean = profileRepo.getProfile().appSignalEnabled

    override suspend fun onAction(category: CategoryPool): ActionLogEntity {
        val locale = localeManager.currentLocale
        val keywords = CATEGORY_APP_KEYWORDS[locale]?.get(category)
            ?: CATEGORY_APP_KEYWORDS[SupportedLocale.EN]?.get(category)
            ?: DEFAULT_KEYWORDS
        val url = "https://play.google.com/store/search?q=$keywords&c=apps&hl=${locale.tag}"

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
