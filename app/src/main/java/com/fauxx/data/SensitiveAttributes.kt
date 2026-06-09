package com.fauxx.data

/**
 * Sensitive attributes Fauxx must never infer or target: race, religion, sexual
 * orientation, gender identity, disability, and political affiliation. This is an
 * ethical invariant (issue #167).
 *
 * This is the single shared denylist that future work must consult: persona seeding
 * (E7) and adversarial noise allocation (E4) must run candidate categories and
 * interests through [matches] before adopting them.
 *
 * Matching is word-boundary aware so topical words are not mistaken for sensitive
 * attributes (for example "race" the ethnicity is flagged, while "racing" is not).
 */
object SensitiveAttributes {

    val DENYLIST: List<String> = listOf(
        "race",
        "ethnicity",
        "religion",
        "religious",
        "sexual orientation",
        "sexuality",
        "gender identity",
        "transgender",
        "disability",
        "disabled",
        "political affiliation",
        "political party",
    )

    private val patterns: List<Regex> =
        DENYLIST.map { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE) }

    /** True if [text] names a sensitive attribute as a whole word or phrase. */
    fun matches(text: String): Boolean {
        val normalized = text.replace('_', ' ')
        return patterns.any { it.containsMatchIn(normalized) }
    }
}
