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
 * from the table), the EN entry is used as fallback. Every [CategoryPool] value
 * has an EN entry — verified by `AppSignalKeywordsCoverageTest` — so the
 * [DEFAULT_KEYWORDS] fallback is defensive-only and shouldn't be hit in practice.
 * Issue #18 ("App store links always search for 'productivity tools'") was caused
 * by missing entries silently falling through to that default; the test prevents
 * regression.
 */
internal val CATEGORY_APP_KEYWORDS: Map<SupportedLocale, Map<CategoryPool, String>> = mapOf(
    SupportedLocale.EN to mapOf(
        CategoryPool.MEDICAL to "health+medical",
        CategoryPool.LEGAL to "legal+forms+advice",
        CategoryPool.AUTOMOTIVE to "car+maintenance+repair",
        CategoryPool.PARENTING to "baby+tracker+parenting",
        CategoryPool.RETIREMENT to "retirement+planning",
        CategoryPool.GAMING to "strategy+games",
        CategoryPool.AGRICULTURE to "farming+agriculture",
        CategoryPool.FASHION to "fashion+outfit+style",
        CategoryPool.ACADEMIC to "study+flashcards+homework",
        CategoryPool.REAL_ESTATE to "real+estate+home+search",
        CategoryPool.COOKING to "recipe+app",
        CategoryPool.SPORTS to "sports+scores",
        CategoryPool.FINANCE to "budget+finance",
        CategoryPool.TRAVEL to "travel+planning",
        CategoryPool.TECHNOLOGY to "tech+news+gadgets",
        CategoryPool.PETS to "pet+care+dog+cat",
        CategoryPool.HOME_IMPROVEMENT to "home+improvement+DIY",
        CategoryPool.BEAUTY to "makeup+beauty+tutorial",
        CategoryPool.MUSIC to "music+streaming+radio",
        CategoryPool.FITNESS to "fitness+tracker",
        CategoryPool.ENTERTAINMENT to "movies+streaming",
        CategoryPool.FOOD to "food+delivery+restaurant",
        CategoryPool.POLITICS to "news+politics+current+events",
        CategoryPool.SCIENCE to "science+news+research",
        CategoryPool.BUSINESS to "business+invoicing+productivity",
        CategoryPool.OUTDOOR_RECREATION to "hiking+trails+outdoor",
        CategoryPool.CRAFTS to "craft+ideas+DIY",
        CategoryPool.HISTORY to "history+trivia+museum",
        CategoryPool.ENVIRONMENT to "carbon+footprint+sustainability",
        CategoryPool.MILITARY_DEFENSE to "military+veteran+benefits",
        CategoryPool.WELLNESS_ALTERNATIVE to "meditation+astrology+wellness",
        CategoryPool.RELATIONSHIPS_DATING to "dating+relationships"
    ),
    SupportedLocale.ES to mapOf(
        CategoryPool.MEDICAL to "salud+medicina",
        CategoryPool.LEGAL to "asesoría+legal+formularios",
        CategoryPool.AUTOMOTIVE to "mantenimiento+coche+taller",
        CategoryPool.PARENTING to "rastreador+bebé+crianza",
        CategoryPool.RETIREMENT to "planificación+jubilación",
        CategoryPool.GAMING to "juegos+estrategia",
        CategoryPool.AGRICULTURE to "agricultura+campo",
        CategoryPool.FASHION to "moda+estilo+outfit",
        CategoryPool.ACADEMIC to "estudio+tareas+universitarios",
        CategoryPool.REAL_ESTATE to "inmobiliaria+pisos+venta",
        CategoryPool.COOKING to "recetas+cocina",
        CategoryPool.SPORTS to "resultados+deportes",
        CategoryPool.FINANCE to "presupuesto+finanzas",
        CategoryPool.TRAVEL to "planificar+viaje",
        CategoryPool.TECHNOLOGY to "tecnología+gadgets+noticias",
        CategoryPool.PETS to "mascotas+perros+gatos",
        CategoryPool.HOME_IMPROVEMENT to "bricolaje+hogar+reformas",
        CategoryPool.BEAUTY to "maquillaje+belleza+tutorial",
        CategoryPool.MUSIC to "música+streaming+radio",
        CategoryPool.FITNESS to "rastreador+fitness",
        CategoryPool.ENTERTAINMENT to "películas+series+streaming",
        CategoryPool.FOOD to "comida+a+domicilio+restaurantes",
        CategoryPool.POLITICS to "noticias+política+actualidad",
        CategoryPool.SCIENCE to "ciencia+noticias+investigación",
        CategoryPool.BUSINESS to "facturación+negocios+productividad",
        CategoryPool.OUTDOOR_RECREATION to "senderismo+rutas+naturaleza",
        CategoryPool.CRAFTS to "manualidades+ideas+DIY",
        CategoryPool.HISTORY to "historia+museo+trivia",
        CategoryPool.ENVIRONMENT to "huella+carbono+sostenibilidad",
        CategoryPool.MILITARY_DEFENSE to "veteranos+militar+ayudas",
        CategoryPool.WELLNESS_ALTERNATIVE to "meditación+astrología+bienestar",
        CategoryPool.RELATIONSHIPS_DATING to "citas+relaciones"
    ),
    SupportedLocale.RU to mapOf(
        CategoryPool.MEDICAL to "здоровье+медицина",
        CategoryPool.LEGAL to "юридическая+помощь+документы",
        CategoryPool.AUTOMOTIVE to "обслуживание+автомобиля+ремонт",
        CategoryPool.PARENTING to "дневник+ребёнка+родители",
        CategoryPool.RETIREMENT to "планирование+пенсии",
        CategoryPool.GAMING to "стратегические+игры",
        CategoryPool.AGRICULTURE to "фермерство+сельское+хозяйство",
        CategoryPool.FASHION to "мода+стиль+одежда",
        CategoryPool.ACADEMIC to "учёба+конспекты+домашка",
        CategoryPool.REAL_ESTATE to "недвижимость+поиск+жилья",
        CategoryPool.COOKING to "рецепты+приложение",
        CategoryPool.SPORTS to "спорт+результаты",
        CategoryPool.FINANCE to "бюджет+финансы",
        CategoryPool.TRAVEL to "планирование+путешествий",
        CategoryPool.TECHNOLOGY to "технологии+новости+гаджеты",
        CategoryPool.PETS to "питомцы+собаки+кошки",
        CategoryPool.HOME_IMPROVEMENT to "ремонт+дома+своими+руками",
        CategoryPool.BEAUTY to "макияж+красота+туториал",
        CategoryPool.MUSIC to "музыка+стриминг+радио",
        CategoryPool.FITNESS to "фитнес+трекер",
        CategoryPool.ENTERTAINMENT to "фильмы+сериалы+стриминг",
        CategoryPool.FOOD to "доставка+еды+рестораны",
        CategoryPool.POLITICS to "новости+политика",
        CategoryPool.SCIENCE to "наука+новости+исследования",
        CategoryPool.BUSINESS to "бизнес+бухгалтерия+продуктивность",
        CategoryPool.OUTDOOR_RECREATION to "походы+маршруты+активный+отдых",
        CategoryPool.CRAFTS to "рукоделие+идеи+своими+руками",
        CategoryPool.HISTORY to "история+музей+факты",
        CategoryPool.ENVIRONMENT to "экология+устойчивое+развитие",
        CategoryPool.MILITARY_DEFENSE to "ветераны+армия+льготы",
        CategoryPool.WELLNESS_ALTERNATIVE to "медитация+астрология+велнес",
        CategoryPool.RELATIONSHIPS_DATING to "знакомства+отношения"
    ),
    SupportedLocale.FR to mapOf(
        CategoryPool.MEDICAL to "santé+médical",
        CategoryPool.LEGAL to "conseil+juridique+formulaires",
        CategoryPool.AUTOMOTIVE to "entretien+voiture+garage",
        CategoryPool.PARENTING to "bébé+parents+suivi",
        CategoryPool.RETIREMENT to "planification+retraite",
        CategoryPool.GAMING to "jeux+stratégie",
        CategoryPool.AGRICULTURE to "agriculture+ferme",
        CategoryPool.FASHION to "mode+style+tenue",
        CategoryPool.ACADEMIC to "étudiant+révisions+fiches",
        CategoryPool.REAL_ESTATE to "immobilier+annonces+location",
        CategoryPool.COOKING to "recettes+cuisine",
        CategoryPool.SPORTS to "résultats+sport",
        CategoryPool.FINANCE to "budget+finances",
        CategoryPool.TRAVEL to "planifier+voyage",
        CategoryPool.TECHNOLOGY to "tech+actualité+gadgets",
        CategoryPool.PETS to "animaux+chien+chat",
        CategoryPool.HOME_IMPROVEMENT to "bricolage+maison+travaux",
        CategoryPool.BEAUTY to "maquillage+beauté+tutoriel",
        CategoryPool.MUSIC to "musique+streaming+radio",
        CategoryPool.FITNESS to "tracker+fitness",
        CategoryPool.ENTERTAINMENT to "films+séries+streaming",
        CategoryPool.FOOD to "livraison+restaurant+repas",
        CategoryPool.POLITICS to "actualité+politique",
        CategoryPool.SCIENCE to "science+actualité+recherche",
        CategoryPool.BUSINESS to "facturation+entreprise+productivité",
        CategoryPool.OUTDOOR_RECREATION to "randonnée+nature+plein+air",
        CategoryPool.CRAFTS to "loisirs+créatifs+DIY",
        CategoryPool.HISTORY to "histoire+musée+culture",
        CategoryPool.ENVIRONMENT to "empreinte+carbone+écologie",
        CategoryPool.MILITARY_DEFENSE to "anciens+combattants+militaire",
        CategoryPool.WELLNESS_ALTERNATIVE to "méditation+astrologie+bien-être",
        CategoryPool.RELATIONSHIPS_DATING to "rencontres+couple"
    )
)

/**
 * Defensive fallback. With full EN coverage, this should never be hit; if it ever is,
 * the [AppSignalKeywordsCoverageTest] would have failed first. Kept so a runtime miss
 * (e.g. a future enum addition not yet on a release) degrades to a plausible search
 * rather than crashing.
 */
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
