package com.fauxx.data.querybank

import java.util.Calendar

/**
 * Resolution of the `$YEAR$` placeholder used by recency-style queries (issue #256).
 *
 * A slice of the corpus is recency-shaped: "best smartphones 2025", "best password
 * managers 2024". Authored with a literal year, those go stale as the calendar advances
 * and start reading as dated, which is the kind of separable signal synthetic traffic
 * cannot afford. Those entries now carry [YEAR_TOKEN] instead, and it resolves to the
 * real current year whenever a bank is loaded.
 *
 * The token is deliberate rather than inferred. Most years in the corpus are intentional
 * (the 1969 moon landing, older albums and films, "Cyberpunk 2077"), so a heuristic that
 * detected and bumped trailing years would corrupt them. Only entries explicitly marked
 * with [YEAR_TOKEN] are ever freshened, and event-anchored years that do not recur
 * annually (US midterms, the World Cup) were deliberately left as literals.
 */
const val YEAR_TOKEN = "\$YEAR\$"

/** The current calendar year in the device's local time zone. */
fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

/**
 * Replaces every [YEAR_TOKEN] in [query] with [year]. Returns [query] unchanged when it
 * carries no token, which is the overwhelmingly common case.
 */
fun freshenRecencyYear(query: String, year: Int): String =
    if (query.contains(YEAR_TOKEN)) query.replace(YEAR_TOKEN, year.toString()) else query
