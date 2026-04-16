package com.fauxx

import android.content.Context
import android.content.res.AssetManager
import com.fauxx.data.querybank.QueryBlocklist
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * CI regression guard: parses every JSON file under `query_banks` against the live
 * `harmful_queries.json` and fails the build if any corpus entry matches a harmful
 * trigger.
 *
 * This is the enforcement point for the "Self-Signal Harm" safety requirement in
 * CLAUDE.md. Without this test, a contributor adding an innocent-looking query like
 * "988 mental health crisis line" to a query bank would silently ship the same
 * vulnerability we are closing here — runtime filtering would still catch it, but
 * the corpus should be clean at the source to prevent silent runtime drops and keep
 * the full query stream available for noise generation.
 *
 * Runs as a standard JVM unit test (not instrumentation). Asset files are read
 * directly from the module's `src/main/assets/` path rather than through Android's
 * AssetManager.
 */
class QueryBankCorpusAuditTest {

    private val assetsRoot = File("src/main/assets")

    @Test
    fun `every query in every bank passes QueryBlocklist`() {
        val harmfulJson = File(assetsRoot, "harmful_queries.json").readText()
        val blocklist = makeBlocklistWithJson(harmfulJson)

        val bankDir = File(assetsRoot, "query_banks")
        assertBankDirExists(bankDir)

        val stringListType = object : TypeToken<List<String>>() {}.type
        val gson = Gson()
        val allViolations = mutableListOf<String>()

        bankDir.listFiles { f -> f.extension == "json" }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                val queries: List<String> = gson.fromJson(file.readText(), stringListType)
                queries.forEachIndexed { idx, q ->
                    if (blocklist.isBlocked(q)) {
                        allViolations += "${file.name}[$idx]: \"$q\""
                    }
                }
            }

        assertEquals(
            buildString {
                append("Query bank entries match harmful-query guard. ")
                append("Remove them from the JSON or re-word to avoid the trigger. ")
                append("If the trigger itself is wrong, adjust harmful_queries.json. ")
                append("See .devloop/spikes/harmful-query-guard.md for context.\n")
                allViolations.forEach { appendLine("  $it") }
            },
            0,
            allViolations.size
        )
    }

    @Test
    fun `harmful_queries_json has non-empty lists`() {
        val harmfulJson = File(assetsRoot, "harmful_queries.json").readText()
        val parsed = Gson().fromJson(harmfulJson, Map::class.java)
        val classA = parsed["class_a_terms"] as? List<*> ?: emptyList<Any>()
        val selfSignal = parsed["self_signal_terms"] as? List<*> ?: emptyList<Any>()
        val regexes = parsed["regex_patterns"] as? List<*> ?: emptyList<Any>()
        assert(classA.isNotEmpty()) { "class_a_terms must not be empty" }
        assert(selfSignal.isNotEmpty()) { "self_signal_terms must not be empty" }
        assert(regexes.isNotEmpty()) { "regex_patterns must not be empty" }
    }

    private fun makeBlocklistWithJson(json: String): QueryBlocklist {
        val context: Context = mockk()
        val assetManager: AssetManager = mockk()
        every { context.assets } returns assetManager
        every { assetManager.open("harmful_queries.json") } returns
            ByteArrayInputStream(json.toByteArray())
        return QueryBlocklist(context)
    }

    private fun assertBankDirExists(bankDir: File) {
        require(bankDir.isDirectory) {
            "Could not find $bankDir (cwd=${File(".").absolutePath}). " +
                "Run from the app module root."
        }
    }
}
