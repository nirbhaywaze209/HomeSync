package com.homesync.app

import com.homesync.app.util.QuickActionCooldownManager
import org.junit.Assert.*
import org.junit.Test

class QuickActionCooldownManagerTest {

    @Test
    fun testCooldownDurationIsTwoHours() {
        assertEquals(2 * 60 * 60 * 1000L, QuickActionCooldownManager.COOLDOWN_DURATION_MS)
    }

    @Test
    fun testFormatRemainingCooldown() {
        // Less than or equal to 0
        assertEquals("", QuickActionCooldownManager.formatRemainingCooldown(0L))
        assertEquals("", QuickActionCooldownManager.formatRemainingCooldown(-500L))

        // Seconds range
        assertEquals("45s", QuickActionCooldownManager.formatRemainingCooldown(45 * 1000L))

        // Minutes range
        assertEquals("15m", QuickActionCooldownManager.formatRemainingCooldown(15 * 60 * 1000L))

        // Hours and minutes range
        assertEquals("1h 59m", QuickActionCooldownManager.formatRemainingCooldown((1 * 3600 + 59 * 60) * 1000L))
        assertEquals("2h 0m", QuickActionCooldownManager.formatRemainingCooldown(2 * 3600 * 1000L))
    }

    @Test
    fun testCanPerformActionInitially() {
        val testChildUid = "test_child_uid_${System.currentTimeMillis()}"
        assertTrue(QuickActionCooldownManager.canPerformAction(testChildUid, QuickActionCooldownManager.ACTION_SAFE_CHECKIN))
        assertEquals(0L, QuickActionCooldownManager.getRemainingCooldownMinutes(testChildUid, QuickActionCooldownManager.ACTION_SAFE_CHECKIN))
    }
}
