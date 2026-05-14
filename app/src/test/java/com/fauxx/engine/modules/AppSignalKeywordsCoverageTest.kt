package com.fauxx.engine.modules

import com.fauxx.data.querybank.CategoryPool
import com.fauxx.locale.SupportedLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lock-in test for issue #18: every [CategoryPool] value must have an entry in
 * every locale's [CATEGORY_APP_KEYWORDS] map. Without this, missing categories
 * silently fall through to the `productivity+tools` default — which is exactly
 * what users saw in the original report (BEAUTY and LEGAL both opening to the
 * same Play Store search).
 *
 * Pure Kotlin: no Android. The keyword map is a top-level `internal val` so the
 * test reaches it directly without instantiating the module (which would require
 * Hilt + Robolectric for the `@Inject` context).
 */
class AppSignalKeywordsCoverageTest {

    private val allCategories: List<CategoryPool> = CategoryPool.values().toList()

    @Test
    fun `every locale has every CategoryPool covered`() {
        // SupportedLocale.values() includes EN/ES/FR plus any future additions —
        // verifying all of them catches regressions when a new locale is added but
        // the keyword bank is forgotten.
        for (locale in SupportedLocale.values()) {
            val localeMap = CATEGORY_APP_KEYWORDS[locale]
                ?: error("Locale $locale has no entry in CATEGORY_APP_KEYWORDS")
            val missing = allCategories - localeMap.keys
            assertTrue(
                "Locale $locale is missing keywords for: $missing",
                missing.isEmpty()
            )
        }
    }

    @Test
    fun `no keyword is empty or blank`() {
        for ((locale, localeMap) in CATEGORY_APP_KEYWORDS) {
            for ((category, keywords) in localeMap) {
                assertTrue(
                    "Empty/blank keywords for $locale / $category",
                    keywords.isNotBlank()
                )
            }
        }
    }

    @Test
    fun `keywords use plus-encoded form expected by the Play Store URL`() {
        // The URL builds as `?q=$keywords` — keywords already contain `+` for spaces
        // rather than encoded `%20`. A space character would produce a broken URL.
        for ((locale, localeMap) in CATEGORY_APP_KEYWORDS) {
            for ((category, keywords) in localeMap) {
                assertFalse(
                    "Keyword for $locale / $category contains a literal space: '$keywords'",
                    keywords.contains(' ')
                )
            }
        }
    }

    @Test
    fun `each locale covers all categories with the same count as EN`() {
        val enCount = CATEGORY_APP_KEYWORDS[SupportedLocale.EN]?.size
            ?: error("EN must be populated")
        for (locale in SupportedLocale.values()) {
            val size = CATEGORY_APP_KEYWORDS[locale]?.size ?: 0
            assertEquals(
                "Locale $locale has $size categories but EN has $enCount — keep them in parity",
                enCount,
                size
            )
        }
    }
}
