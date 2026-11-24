package com.fauxx.data.crawllist

import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import java.text.Normalizer

/**
 * Shop pages whose purchase intent Fauxx must never plant (#322 item 7, the owner's ruling).
 *
 * Purchase-intent segments are sold, and a decoy visit attaches its segment to the real user's
 * device. So no landing page is about health or medical services, pharmacies and drugs, pregnancy,
 * births and baby products, payday loans and debt repair, bail and attorneys, alcohol, tobacco,
 * gambling, firearms, adult products, dating, religion, funerals or DNA tests (genetic data is
 * health data). The curated landing pages are
 * chosen that way, and this check keeps the visits from wandering there: on a shop's site,
 * browsing on and search clicks skip any page whose address [mentions] one of [WORDS], in any of
 * Fauxx's four languages. A shop's menus link to its baby, pharmacy and wine sections from every
 * page, so without it one click could undo the curation.
 */
object SensitivePurchases {

    /** Whole words, lower case and without accents, as they appear in URL slugs. */
    val WORDS: Set<String> = setOf(
        // English
        "baby", "babies", "infant", "infants", "newborn", "newborns", "toddler", "toddlers", "nursery",
        "maternity", "pregnancy", "pregnant", "prenatal", "diaper", "diapers", "nappy", "nappies",
        "pharmacy", "pharmacies", "prescription", "prescriptions", "medicine", "medicines", "medication",
        "medications", "medical", "health", "healthcare", "wellness", "vitamin", "vitamins", "supplement",
        "supplements", "fertility", "ovulation", "contraception", "contraceptive", "incontinence", "hearing",
        "cbd", "hemp", "cannabis", "vape", "vapes", "vaping", "ecig", "tobacco", "cigar", "cigars", "cigarettes",
        "wine", "wines", "beer", "beers", "spirits", "liquor", "alcohol", "whiskey", "whisky", "vodka", "tequila",
        "guns", "shotgun", "shotguns", "handgun", "handguns", "firearm", "firearms", "ammo", "ammunition", "rifle", "rifles", "pistol", "pistols",
        "casino", "betting", "lottery", "poker", "sexual", "sex", "condom", "condoms", "adult", "intimate",
        "dating", "funeral", "funerals", "religious", "bible", "dna", "genetic",
        // Spanish
        "bebe", "bebes", "recien", "embarazo", "embarazada", "premama", "lactancia", "panal", "panales",
        "farmacia", "parafarmacia", "salud", "medicamento", "medicamentos", "vitaminas", "suplementos",
        "vino", "vinos", "cerveza", "cervezas", "licor", "licores", "tabaco", "arma", "armas", "apuestas",
        "loteria", "citas", "funeraria", "religion", "religiosos", "preservativos", "erotico", "erotica",
        // French
        "puericulture", "grossesse", "enceinte", "maternite", "couche", "couches", "pharmacie",
        "parapharmacie", "sante", "medicaments", "vitamines", "complements", "vin", "biere", "bieres",
        "spiritueux", "alcool", "tabac", "armes", "loterie", "rencontre", "rencontres", "funeraire", "obseques",
        "religieux", "preservatifs", "sexuel", "sexualite", "erotique",
        // Russian, in transliterated slugs and in Cyrillic
        "malysh", "malyshey", "novorozhdennyh", "beremennyh", "beremennost", "podguzniki", "detskoe-pitanie",
        "apteka", "zdorove", "zdorovye", "lekarstva", "vitaminy", "bady", "alkogol", "pivo", "vodka", "tabak",
        "oruzhie", "kazino", "stavki", "znakomstva", "ritualnye", "intim",
        "малыш", "малышей", "новорожденных", "беременных", "беременность", "подгузники", "аптека", "здоровье",
        "лекарства", "витамины", "бады", "алкоголь", "пиво", "вино", "водка", "табак", "оружие", "казино", "ставки",
        "знакомства", "ритуальные", "интим",
    )

    /** True when [url]'s path or query contains one of [WORDS] as a whole word. */
    fun mentions(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val raw = listOfNotNull(uri.rawPath, uri.rawQuery).joinToString(" ")
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        val plain = Normalizer.normalize(decoded.lowercase(), Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
        val words = plain.split(NON_WORD).filter { it.isNotEmpty() }
        if (words.any { it in WORDS }) return true
        // Two-word entries such as "detskoe-pitanie" (baby food), matched across the separator.
        return words.zipWithNext { a, b -> "$a-$b" }.any { it in WORDS }
    }

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
}

/**
 * Names for the IAB Audience Taxonomy 1.1 purchase-intent nodes the landing pages are tagged with
 * (#322 item 7), from `assets/purchase_intents.json`, built by `scripts/build_purchase_intents.py`.
 * Segments are bought and sold by these nodes, so the log names the one each visit targets.
 */
class PurchaseIntents private constructor(private val names: Map<Int, String>) {

    /** "Sporting Goods > Camping and Hiking (IAB 1625)", or "IAB 1625" for an unknown node. */
    fun describe(id: Int): String = names[id]?.let { "$it (IAB $id)" } ?: "IAB $id"

    /** Whether [id] is a known node. */
    operator fun contains(id: Int): Boolean = id in names

    companion object {
        const val ASSET_PATH = "purchase_intents.json"

        /** No names: every node is described by its id. */
        val UNNAMED = PurchaseIntents(emptyMap())

        /** Parse the asset's `nodes`; anything unreadable gives [UNNAMED]. */
        fun parse(json: String): PurchaseIntents = runCatching {
            val nodes = JsonParser.parseString(json).asJsonObject.getAsJsonObject("nodes")
            PurchaseIntents(nodes.entrySet().associate { (id, node) -> id.toInt() to node.asJsonObject.get("name").asString })
        }.getOrDefault(UNNAMED)
    }
}
