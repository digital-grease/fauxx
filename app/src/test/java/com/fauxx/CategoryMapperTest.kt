package com.fauxx

import android.content.Context
import android.content.res.AssetManager
import com.fauxx.data.querybank.CategoryPool
import com.fauxx.targeting.layer2.CategoryMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class CategoryMapperTest {

    private val context: Context = mockk()
    private val assetManager: AssetManager = mockk()

    private fun createMapper(jsonMap: String = "{}"): CategoryMapper {
        every { context.assets } returns assetManager
        every { assetManager.open("platform_category_map.json") } returns
            ByteArrayInputStream(jsonMap.toByteArray())
        return CategoryMapper(context)
    }

    @Test
    fun `known platform strings map correctly from JSON`() {
        val json = """{"Video Games": "GAMING", "Travel": "TRAVEL"}"""
        val mapper = createMapper(json)
        assertEquals(CategoryPool.GAMING, mapper.map("Video Games"))
        assertEquals(CategoryPool.TRAVEL, mapper.map("Travel"))
    }

    @Test
    fun `case insensitive exact match works`() {
        val json = """{"Video Games": "GAMING"}"""
        val mapper = createMapper(json)
        assertEquals(CategoryPool.GAMING, mapper.map("video games"))
    }

    @Test
    fun `unknown string falls back gracefully without crash`() {
        val mapper = createMapper()
        // Should return null without throwing
        val result = mapper.map("!!??completely_unknown_XYZ_!!??")
        assertNull(result)
    }

    @Test
    fun `heuristic matching works for common patterns`() {
        val mapper = createMapper()
        assertEquals(CategoryPool.GAMING, mapper.map("PC Gaming enthusiasts"))
        assertEquals(CategoryPool.MEDICAL, mapper.map("health and wellness"))
        assertEquals(CategoryPool.TECHNOLOGY, mapper.map("software development tools"))
    }

    @Test
    fun `mapAll returns correct set and skips unknowns`() {
        val json = """{"Sports": "SPORTS"}"""
        val mapper = createMapper(json)
        val result = mapper.mapAll(listOf("Sports", "totally_unknown_xyz"))
        assertEquals(1, result.size)
        assertEquals(CategoryPool.SPORTS, result.first())
    }

    @Test
    fun `empty input returns empty set`() {
        val mapper = createMapper()
        val result = mapper.mapAll(emptyList())
        assertEquals(0, result.size)
    }
}
