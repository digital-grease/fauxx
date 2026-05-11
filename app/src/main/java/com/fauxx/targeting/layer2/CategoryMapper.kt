package com.fauxx.targeting.layer2

import android.content.Context
import timber.log.Timber
import com.fauxx.data.querybank.CategoryPool
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps raw ad platform category strings (e.g., "Video Games", "Software Development") to
 * [CategoryPool] enum values. Uses a JSON lookup table first, then falls back to fuzzy keyword
 * matching. Unknown strings are logged and skipped without crashing.
 */
@Singleton
class CategoryMapper @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** Raw platform string → CategoryPool name, loaded from assets. */
    private val exactMap: Map<String, String> by lazy { loadExactMap() }

    /**
     * Map a raw platform category string to a [CategoryPool] value.
     * Returns null and logs a warning if no match is found.
     */
    fun map(platformString: String): CategoryPool? {
        val normalized = platformString.trim()

        // 1. Exact match from JSON map
        val exactMatch = exactMap[normalized]
            ?: exactMap.entries.firstOrNull { it.key.equals(normalized, ignoreCase = true) }?.value
        if (exactMatch != null) {
            return runCatching { CategoryPool.valueOf(exactMatch) }.getOrNull()
        }

        // 2. Fuzzy keyword matching against CategoryPool names
        val lowerInput = normalized.lowercase()
        for (category in CategoryPool.values()) {
            val categoryWords = category.name.lowercase().replace("_", " ")
            if (lowerInput.contains(categoryWords) || categoryWords.contains(lowerInput)) {
                return category
            }
        }

        // 3. Keyword-based heuristics for common platform strings
        val heuristic = applyHeuristics(lowerInput)
        if (heuristic != null) return heuristic

        Timber.d("No CategoryPool match for platform string: '$platformString'")
        return null
    }

    /**
     * Map a collection of platform strings to a set of [CategoryPool] values.
     * Unmatched strings are skipped silently.
     */
    fun mapAll(platformStrings: Collection<String>): Set<CategoryPool> =
        platformStrings.mapNotNull { map(it) }.toSet()

    private fun applyHeuristics(lower: String): CategoryPool? = when {
        containsAny(lower, "game", "gaming", "игр", "гейм") -> CategoryPool.GAMING
        containsAny(lower, "health", "medical", "doctor", "здоров", "медицин", "врач", "клиник") -> CategoryPool.MEDICAL
        containsAny(lower, "sport", "fitness", "exercise", "спорт", "фитнес", "трениров") -> CategoryPool.SPORTS
        containsAny(lower, "travel", "vacation", "hotel", "путешеств", "отпуск", "отель", "гостиниц") -> CategoryPool.TRAVEL
        containsAny(lower, "food", "recipe", "restaurant", "еда", "рецепт", "ресторан", "кафе", "продукт") -> CategoryPool.FOOD
        containsAny(lower, "tech", "software", "computer", "технолог", "софт", "программ", "компьютер", "смартфон") -> CategoryPool.TECHNOLOGY
        containsAny(lower, "fashion", "clothing", "apparel", "мода", "одежд", "гардероб", "стиль") -> CategoryPool.FASHION
        containsAny(lower, "finance", "invest", "stock", "финанс", "инвест", "акци", "облигац", "бюджет") -> CategoryPool.FINANCE
        containsAny(lower, "parent", "baby", "child", "родител", "ребен", "детск", "малыш") -> CategoryPool.PARENTING
        containsAny(lower, "auto", "car", "vehicle", "авто", "машин", "транспорт") -> CategoryPool.AUTOMOTIVE
        containsAny(lower, "pet", "dog", "cat", "питом", "собак", "кош", "животн") -> CategoryPool.PETS
        containsAny(lower, "home", "house", "ремонт", "квартир", "дом", "интерьер", "мебел") -> CategoryPool.HOME_IMPROVEMENT
        containsAny(lower, "music", "concert", "album", "музык", "концерт", "альбом") -> CategoryPool.MUSIC
        containsAny(lower, "beauty", "cosmetic", "makeup", "красот", "космет", "макияж", "уход") -> CategoryPool.BEAUTY
        containsAny(lower, "cook", "baking", "kitchen", "кулинар", "готов", "выпеч", "кухн") -> CategoryPool.COOKING
        containsAny(lower, "real estate", "property", "mortgage", "недвиж", "ипотек", "застройщик") -> CategoryPool.REAL_ESTATE
        containsAny(lower, "retire", "pension", "senior", "пенси", "пожил") -> CategoryPool.RETIREMENT
        containsAny(lower, "legal", "lawyer", "attorney", "юрид", "адвокат", "нотари", "договор") -> CategoryPool.LEGAL
        containsAny(lower, "farm", "agriculture", "crop", "ферм", "сельск", "садовод", "урожай", "теплиц") -> CategoryPool.AGRICULTURE
        containsAny(lower, "educat", "academic", "college", "учеб", "образован", "университет", "школ", "курс") -> CategoryPool.ACADEMIC
        containsAny(lower, "entertain", "movie", "film", "развлеч", "кино", "фильм", "сериал", "театр") -> CategoryPool.ENTERTAINMENT
        containsAny(lower, "science", "research", "lab", "наук", "исследован", "лаборатор", "космос") -> CategoryPool.SCIENCE
        containsAny(lower, "business", "entrepreneur", "startup", "бизнес", "предприним", "стартап", "маркетинг") -> CategoryPool.BUSINESS
        containsAny(lower, "hiking", "camping", "fishing", "outdoor", "hunting", "поход", "кемпинг", "рыбал", "палатк", "трекинг") -> CategoryPool.OUTDOOR_RECREATION
        containsAny(lower, "craft", "knitting", "pottery", "quilt", "scrapbook", "рукодел", "вяза", "вышив", "гончар", "поделк") -> CategoryPool.CRAFTS
        containsAny(lower, "history", "museum", "genealogy", "antique", "historical", "истори", "музей", "генеалог", "антиквар") -> CategoryPool.HISTORY
        containsAny(lower, "climate", "sustainability", "conservation", "renewable", "environment", "эколог", "климат", "переработ", "заповедник") -> CategoryPool.ENVIRONMENT
        containsAny(lower, "military", "veteran", "defense", "army", "navy", "военн", "армия", "флот", "оборона") -> CategoryPool.MILITARY_DEFENSE
        containsAny(lower, "meditation", "astrology", "crystal", "holistic", "essential oil", "медитац", "астролог", "кристалл", "ароматерап") -> CategoryPool.WELLNESS_ALTERNATIVE
        containsAny(lower, "dating", "relationship", "wedding", "marriage", "breakup", "знакомств", "отношен", "свидан", "свадьб") -> CategoryPool.RELATIONSHIPS_DATING
        else -> null
    }

    private fun containsAny(input: String, vararg needles: String): Boolean =
        needles.any { input.contains(it) }

    private fun loadExactMap(): Map<String, String> {
        return try {
            val json = context.assets.open("platform_category_map.json")
                .bufferedReader().readText()
            val type = object : TypeToken<Map<String, String>>() {}.type
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            Timber.e(e, "Failed to load platform_category_map.json")
            emptyMap()
        }
    }
}
