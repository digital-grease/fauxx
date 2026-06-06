package com.fauxx.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the [LogMetadata] serializer underpinning richer action-log detail (#73). */
class LogMetadataTest {

    @Test
    fun `toJson preserves insertion order and parse round-trips`() {
        val json = LogMetadata.toJson(
            LogMetadata.PAGE_TITLE to "Cart",
            LogMetadata.COOKIES_IN_JAR to "7",
            LogMetadata.RESOURCES_LOADED to "34",
        )
        assertEquals(
            listOf(
                LogMetadata.PAGE_TITLE to "Cart",
                LogMetadata.COOKIES_IN_JAR to "7",
                LogMetadata.RESOURCES_LOADED to "34",
            ),
            LogMetadata.parse(json),
        )
    }

    @Test
    fun `toJson drops null and blank values`() {
        val json = LogMetadata.toJson(
            LogMetadata.PAGE_TITLE to "Cart",
            LogMetadata.COOKIES_IN_JAR to null,
            LogMetadata.RESOURCES_LOADED to "   ",
        )
        assertEquals(listOf(LogMetadata.PAGE_TITLE to "Cart"), LogMetadata.parse(json))
    }

    @Test
    fun `toJson returns null when nothing remains`() {
        assertNull(LogMetadata.toJson(LogMetadata.PAGE_TITLE to null))
        assertNull(LogMetadata.toJson())
    }

    @Test
    fun `parse tolerates null, blank and malformed json`() {
        assertTrue(LogMetadata.parse(null).isEmpty())
        assertTrue(LogMetadata.parse("").isEmpty())
        assertTrue(LogMetadata.parse("not valid json {{{").isEmpty())
    }
}
