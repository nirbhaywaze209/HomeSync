package com.homesync.app

import com.homesync.app.util.ScreenTimeManager
import org.junit.Assert.*
import org.junit.Test

class ScreenTimeManagerTest {

    @Test
    fun defaultAllowance_isSixHours() {
        assertEquals(6 * 3600, ScreenTimeManager.DEFAULT_ALLOWANCE_SECONDS)
    }

    @Test
    fun formatTime_formatsCorrectly() {
        assertEquals("06:00:00", ScreenTimeManager.formatTime(6 * 3600))
        assertEquals("05:59:58", ScreenTimeManager.formatTime((5 * 3600) + (59 * 60) + 58))
        assertEquals("00:45:00", ScreenTimeManager.formatTime(45 * 60))
        assertEquals("00:00:00", ScreenTimeManager.formatTime(0))
        assertEquals("00:00:00", ScreenTimeManager.formatTime(-10))
    }
}
