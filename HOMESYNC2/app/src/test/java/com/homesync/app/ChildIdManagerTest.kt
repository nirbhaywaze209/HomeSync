package com.homesync.app

import com.homesync.app.util.ChildIdManager
import org.junit.Assert.*
import org.junit.Test

class ChildIdManagerTest {

    @Test
    fun generatedId_hasCorrectFormat() {
        val id = ChildIdManager.generateUniqueId()
        assertNotNull(id)
        assertTrue(id.startsWith("HS-"))
        assertEquals(9, id.length) // "HS-" (3 chars) + 6 chars = 9
        assertTrue(id.matches(Regex("^HS-[0-9A-Z]{6}$")))
    }

    @Test
    fun generatedIds_areUnique() {
        val generatedIds = mutableSetOf<String>()
        val count = 1000
        for (i in 0 until count) {
            val id = ChildIdManager.generateUniqueId()
            generatedIds.add(id)
        }
        assertEquals("All 1000 generated child IDs should be unique", count, generatedIds.size)
    }
}
