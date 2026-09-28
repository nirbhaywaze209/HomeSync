package com.homesync.app

import com.homesync.app.util.ScreenTimeManager
import org.junit.Assert.*
import org.junit.Test
import java.util.regex.Pattern

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

    @Test
    fun formatHoursAndMinutes_formatsCorrectly() {
        assertEquals("6h 0m", ScreenTimeManager.formatHoursAndMinutes(6 * 3600))
        assertEquals("1h 30m", ScreenTimeManager.formatHoursAndMinutes(5400))
        assertEquals("15m", ScreenTimeManager.formatHoursAndMinutes(900))
        assertEquals("0m", ScreenTimeManager.formatHoursAndMinutes(0))
    }

    @Test
    fun currentScreenTimeDate_matchesCanonicalDateFormat() {
        val date = ScreenTimeManager.getCurrentScreenTimeDate()
        assertNotNull(date)
        assertTrue("Date should match YYYY-MM-DD format", Pattern.matches("\\d{4}-\\d{2}-\\d{2}", date))
    }

    @Test
    fun effectiveUsageCalculation_withResetBase_resetsToZeroAndIncrementsCorrectly() {
        // Simulating UsageStatsManager returning 7200 seconds accumulated usage since midnight
        val rawUsageBeforeReset = 7200
        val resetBaseUsage = rawUsageBeforeReset // resetBase recorded upon reset

        // Immediately after reset: effective usage must be 0
        val effectiveUsageImmediate = (rawUsageBeforeReset - resetBaseUsage).coerceAtLeast(0)
        assertEquals(0, effectiveUsageImmediate)

        // After 10 minutes (600s) of new usage: raw is 7800, effective is 600
        val rawUsageAfter10Mins = 7800
        val effectiveUsageAfter10Mins = (rawUsageAfter10Mins - resetBaseUsage).coerceAtLeast(0)
        assertEquals(600, effectiveUsageAfter10Mins)

        // Edge case: if raw usage is somehow less than reset base, clamp to 0
        val rawUsageCorrupted = 5000
        val effectiveClamped = (rawUsageCorrupted - resetBaseUsage).coerceAtLeast(0)
        assertEquals(0, effectiveClamped)
    }

    @Test
    fun commandTargetFiltering_isolatesPerChildAndAllowsGlobal() {
        val childA = "HS-CH-1234"
        val childB = "HS-CH-5678"

        fun shouldProcessCommand(commandTarget: String, activeChild: String): Boolean {
            val cleanTarget = commandTarget.trim().uppercase()
            val cleanActive = activeChild.trim().uppercase()
            return cleanTarget.isBlank() || cleanTarget == "ALL" || cleanTarget == cleanActive
        }

        // Target Child A only -> Child A processes, Child B ignores
        assertTrue(shouldProcessCommand("HS-CH-1234", childA))
        assertFalse(shouldProcessCommand("HS-CH-1234", childB))

        // Target Child B only -> Child B processes, Child A ignores
        assertTrue(shouldProcessCommand("HS-CH-5678", childB))
        assertFalse(shouldProcessCommand("HS-CH-5678", childA))

        // Target ALL -> both process
        assertTrue(shouldProcessCommand("ALL", childA))
        assertTrue(shouldProcessCommand("ALL", childB))
        assertTrue(shouldProcessCommand("all", childA))

        // Legacy / empty target -> backward compatible, processes
        assertTrue(shouldProcessCommand("", childA))
    }

    @Test
    fun staleCommandFiltering_ignoresOlderCommands() {
        val lastProcessedTimestamp = 1700000500L

        fun isStale(cmdTimestamp: Long, lastTs: Long): Boolean {
            return cmdTimestamp > 0 && cmdTimestamp < lastTs
        }

        // Older command arrives -> stale, ignored
        assertTrue(isStale(1700000400L, lastProcessedTimestamp))

        // Newer command arrives -> valid, processed
        assertFalse(isStale(1700000600L, lastProcessedTimestamp))

        // Equal timestamp -> valid
        assertFalse(isStale(1700000500L, lastProcessedTimestamp))
    }

    @Test
    fun resetCommandMatching_matchesResetAndResetScreenTime() {
        fun isResetCommand(cmdId: String, commandType: String): Boolean {
            return cmdId.startsWith("RESET") || commandType.startsWith("RESET")
        }

        assertTrue(isResetCommand("RESET_HS-K87BEU_1790000000", "RESET_SCREEN_TIME"))
        assertTrue(isResetCommand("RESET_DAILY_HS-K87BEU_1790000000", "RESET_SCREEN_TIME"))
        assertTrue(isResetCommand("CMD_123", "RESET_SCREEN_TIME"))
        assertTrue(isResetCommand("RESET_123", "NONE"))
        assertFalse(isResetCommand("LOCK_123", "LOCK"))
        assertFalse(isResetCommand("UNLOCK_123", "UNLOCK"))
        assertFalse(isResetCommand("GRANT_123", "EXTRA_TIME"))
    }

    @Test
    fun interactiveAndUsageStats_harmonizationTakesMax() {
        val interactiveAccumulated = 450 // 7.5 mins interactive
        val rawUsage = 8000
        val resetBase = 7200
        val rawDelta = (rawUsage - resetBase).coerceAtLeast(0) // 800s (~13.3 mins)

        val effectiveUsed = maxOf(interactiveAccumulated, rawDelta)
        assertEquals(800, effectiveUsed)

        // When rawDelta is smaller than interactive
        val rawUsageLagging = 7300
        val rawDeltaLagging = (rawUsageLagging - resetBase).coerceAtLeast(0) // 100s
        val effectiveUsedLagging = maxOf(interactiveAccumulated, rawDeltaLagging)
        assertEquals(450, effectiveUsedLagging)
    }

    @Test
    fun pastDateDetection_resetsEffectiveUsageToZero() {
        val payloadDate = "2026-09-22"
        val currentDate = "2026-09-23"
        val rawUsedFromPayload = 18000
        val allowance = 21600

        val isPastDate = payloadDate.isNotBlank() && payloadDate != currentDate
        val effectiveUsed = if (isPastDate) 0 else rawUsedFromPayload
        val effectiveRem = if (isPastDate) allowance else (allowance - rawUsedFromPayload)

        assertEquals(0, effectiveUsed)
        assertEquals(21600, effectiveRem)
    }
}
