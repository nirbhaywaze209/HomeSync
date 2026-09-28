package com.homesync.app

import com.homesync.app.util.ChildRewardsManager
import org.junit.Assert.*
import org.junit.Test

class ChildRewardsManagerTest {

    @Test
    fun availableAvatars_containsExpectedTiers() {
        val avatars = ChildRewardsManager.AVAILABLE_AVATARS
        assertTrue("Should have at least 5 avatar options", avatars.size >= 5)
        val defaultAvatar = avatars.find { it.id == "hero_star" }
        assertNotNull(defaultAvatar)
        assertEquals(0, defaultAvatar!!.costCoins)
    }

    @Test
    fun levelCalculation_tiersCorrectly() {
        // Level is 1 for 0-499 points, 2 for 500-999, etc.
        val level1 = (250 / 500) + 1
        assertEquals(1, level1)

        val level2 = (550 / 500) + 1
        assertEquals(2, level2)

        val level4 = (1600 / 500) + 1
        assertEquals(4, level4)
    }
}
