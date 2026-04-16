package com.fauxx

import android.content.Context
import android.content.res.AssetManager
import com.fauxx.data.querybank.QueryBlocklist
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Unit tests for [QueryBlocklist] — the runtime content filter that sits between
 * the query corpus and the dispatch path. See `harmful_queries.json` for the
 * trigger list and `.devloop/spikes/harmful-query-guard.md` for the threat model.
 */
class QueryBlocklistTest {

    private fun makeBlocklistWithJson(json: String): QueryBlocklist {
        val context: Context = mockk()
        val assetManager: AssetManager = mockk()
        every { context.assets } returns assetManager
        every { assetManager.open("harmful_queries.json") } returns
            ByteArrayInputStream(json.toByteArray())
        return QueryBlocklist(context)
    }

    private fun makeBlocklistWithMissingAsset(): QueryBlocklist {
        val context: Context = mockk()
        val assetManager: AssetManager = mockk()
        every { context.assets } returns assetManager
        every { assetManager.open("harmful_queries.json") } throws IOException("missing")
        return QueryBlocklist(context)
    }

    private val sampleJson = """
        {
          "class_a_terms": ["bomb making instructions", "how to make ricin"],
          "self_signal_terms": [
            "988 mental health",
            "crisis text line",
            "suicide hotline",
            "get a restraining order",
            "wage garnishment"
          ],
          "regex_patterns": ["\\b988\\b", "\\bsuicid(e|al|ality)\\b", "\\bself[- ]?harm\\b"]
        }
    """.trimIndent()

    @Test
    fun `blocks exact-phrase self-signal match`() {
        val bl = makeBlocklistWithJson(sampleJson)
        assertTrue(bl.isBlocked("988 mental health crisis line when to call"))
        assertTrue(bl.isBlocked("need crisis text line right now"))
        assertTrue(bl.isBlocked("national suicide hotline number"))
    }

    @Test
    fun `blocks regex word-boundary matches`() {
        val bl = makeBlocklistWithJson(sampleJson)
        assertTrue(bl.isBlocked("call 988 now"))
        assertTrue(bl.isBlocked("having suicidal thoughts"))
        assertTrue(bl.isBlocked("how to respond to a teen who talks about suicide"))
        assertTrue(bl.isBlocked("self-harm in teenagers what to do as parent"))
        assertTrue(bl.isBlocked("self harm recovery"))
    }

    @Test
    fun `does not block legitimate research or history queries`() {
        val bl = makeBlocklistWithJson(sampleJson)
        assertFalse(bl.isBlocked("Cuban Missile Crisis what happened"))
        assertFalse(bl.isBlocked("Iran hostage crisis 1979 history"))
        assertFalse(bl.isBlocked("16th Street Baptist Church bombing"))
        assertFalse(bl.isBlocked("love bombing red flags early relationship"))
        assertFalse(bl.isBlocked("bath bomb recipe DIY tips"))
        assertFalse(bl.isBlocked("best weed killer that won't kill grass"))
        assertFalse(bl.isBlocked("penalty kill percentage hockey stat"))
    }

    @Test
    fun `is case-insensitive`() {
        val bl = makeBlocklistWithJson(sampleJson)
        assertTrue(bl.isBlocked("CALL 988"))
        assertTrue(bl.isBlocked("Suicide Hotline"))
        assertTrue(bl.isBlocked("BoMb MaKiNg InStRuCtIoNs"))
    }

    @Test
    fun `blocks class-a illegal content phrases`() {
        val bl = makeBlocklistWithJson(sampleJson)
        assertTrue(bl.isBlocked("bomb making instructions please"))
        assertTrue(bl.isBlocked("how to make ricin at home"))
    }

    @Test
    fun `fails closed when asset is missing`() {
        val bl = makeBlocklistWithMissingAsset()
        assertTrue("loadFailed must be set", bl.loadFailed)
        // Every query is blocked when the safety list failed to load — a missing
        // safety list must NEVER silently downgrade to "allow everything".
        assertTrue(bl.isBlocked("best pasta recipes"))
        assertTrue(bl.isBlocked("anything at all"))
        assertTrue(bl.isBlocked(""))
    }

    @Test
    fun `fails closed when asset is empty lists`() {
        val emptyJson = """{"class_a_terms":[],"self_signal_terms":[],"regex_patterns":[]}"""
        val bl = makeBlocklistWithJson(emptyJson)
        assertTrue("Empty safety list must fail closed", bl.loadFailed)
        assertTrue(bl.isBlocked("anything"))
    }

    @Test
    fun `malformed regex is dropped silently without crashing isBlocked`() {
        val json = """
            {
              "class_a_terms": ["ok phrase"],
              "self_signal_terms": [],
              "regex_patterns": ["[unclosed-bracket", "\\b988\\b"]
            }
        """.trimIndent()
        val bl = makeBlocklistWithJson(json)
        assertFalse(bl.loadFailed)
        // The valid regex still works
        assertTrue(bl.isBlocked("call 988 now"))
        // And normal queries don't crash on the dropped regex
        assertFalse(bl.isBlocked("best pasta recipes"))
        assertTrue(bl.isBlocked("try this ok phrase here"))
    }

    @Test
    fun `substring match is anchored on phrase boundaries`() {
        val bl = makeBlocklistWithJson(sampleJson)
        // Positive: exact phrase appears as substring
        assertTrue(bl.isBlocked("i need to get a restraining order today"))
        // Negative: phrase doesn't appear (regardless of related wording)
        assertFalse(bl.isBlocked("studying public order policy"))
    }

    @Test
    fun `real shipped harmful_queries_json catches known first-person distress phrasings`() {
        // Smoke test — does not mock assets, just validates the trigger shape against
        // the phrasings a future contributor might accidentally add.
        // (The comprehensive corpus-audit test lives in QueryBankCorpusAuditTest.)
        val json = java.io.File("src/main/assets/harmful_queries.json")
            .readText()
        val bl = makeBlocklistWithJson(json)
        val mustBlock = listOf(
            "crisis text line for teens parents should know",
            "self-harm in teenagers what to do as parent",
            "how to respond if a teen talks about suicide",
            "988 mental health crisis line when to call",
            "suicidal thoughts resources and what to do",
            "opioid withdrawal timeline and symptoms",
            "how to fight an eviction notice",
            "how to get a restraining order",
            "how to file for Chapter 7 bankruptcy",
            "how to file for bankruptcy without an attorney",
            "wage garnishment how to fight it",
            "domestic violence restraining order how to get",
            "deportation defense options",
            "veteran crisis line phone number",
            "veteran suicide prevention resources"
        )
        val escaped = mustBlock.filterNot { bl.isBlocked(it) }
        assertEquals(
            "Queries that should be blocked escaped the guard: $escaped",
            emptyList<String>(),
            escaped
        )
    }
}
