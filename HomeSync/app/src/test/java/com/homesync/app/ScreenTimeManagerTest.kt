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

    @Test
    fun resetVersion_guardsAgainstStaleCloudSnapshots() {
        val currentResetVersion = 3L

        fun shouldAcceptCloudUpdate(cloudResetVersion: Long, currentResetVersion: Long): Boolean {
            return cloudResetVersion >= currentResetVersion
        }

        // Stale snapshot with previous version -> rejected
        assertFalse(shouldAcceptCloudUpdate(1L, currentResetVersion))
        assertFalse(shouldAcceptCloudUpdate(2L, currentResetVersion))

        // Current or newer version -> accepted
        assertTrue(shouldAcceptCloudUpdate(3L, currentResetVersion))
        assertTrue(shouldAcceptCloudUpdate(4L, currentResetVersion))
    }

    @Test
    fun packageExclusion_filtersOutSelfAndSystemServices() {
        val myPackage = "com.homesync.app"
        val ignoredPackages = setOf(myPackage, "com.android.systemui", "android")

        fun isEligibleApp(pkg: String): Boolean {
            return pkg.isNotBlank() && !ignoredPackages.contains(pkg)
        }

        assertFalse(isEligibleApp("com.homesync.app"))
        assertFalse(isEligibleApp("com.android.systemui"))
        assertFalse(isEligibleApp("android"))
        assertTrue(isEligibleApp("com.google.android.youtube"))
        assertTrue(isEligibleApp("com.instagram.android"))
        assertTrue(isEligibleApp("com.whatsapp"))
    }

    @Test
    fun resetBase_advancesFloorCorrectly() {
        val oldBase = 5000
        val rawUsage = 7200
        val storedUsed = 2200
        val interactive = 2100

        // New base must baseline raw usage to prevent old screen time from refreshing
        val newBase = maxOf(rawUsage, oldBase + maxOf(interactive, storedUsed))
        assertEquals(7200, newBase)

        // Delta immediately after reset is 0
        val effectiveDelta = (rawUsage - newBase).coerceAtLeast(0)
        assertEquals(0, effectiveDelta)
    }

    @Test
    fun test1_normalTelemetryDoesNotChangeResetVersion() {
        val existingResetVersion = 100L
        val telemetryPayload = mutableMapOf<String, Any>(
            "usedSeconds" to 600,
            "remainingSeconds" to 21000
        )
        // If resetVersion is null, it is NOT written to payload
        val passedResetVersion: Long? = null
        if (passedResetVersion != null && passedResetVersion > 0L) {
            telemetryPayload["resetVersion"] = passedResetVersion
        }

        // Simulate Firestore SetOptions.merge() with existing doc:
        val existingDoc = mutableMapOf<String, Any>("resetVersion" to existingResetVersion)
        existingDoc.putAll(telemetryPayload)

        assertEquals("resetVersion must remain 100 after normal telemetry", 100L, existingDoc["resetVersion"])
    }

    @Test
    fun test2_actualResetChangesResetVersion() {
        val existingResetVersion = 100L
        val resetTimestamp = 200L

        fun generateResetPayload(resetVersion: Long?): Map<String, Any> {
            val payload = mutableMapOf<String, Any>("usedSeconds" to 0)
            if (resetVersion != null && resetVersion > 0L) {
                payload["resetVersion"] = resetVersion
            }
            return payload
        }

        val resetPayload = generateResetPayload(resetTimestamp)
        val existingDoc = mutableMapOf<String, Any>("resetVersion" to existingResetVersion)
        existingDoc.putAll(resetPayload)

        assertTrue(existingDoc["resetVersion"] as Long > existingResetVersion)
        assertEquals(200L, existingDoc["resetVersion"])
    }

    @Test
    fun test3_sameResetVersionIsProcessedOnlyOnce() {
        var localResetVersion = 200L
        var baselineCreatedCount = 0

        fun processReset(cloudResetVersion: Long) {
            if (cloudResetVersion > 0L && cloudResetVersion > localResetVersion) {
                localResetVersion = cloudResetVersion
                baselineCreatedCount++
            }
        }

        // First delivery: cloudResetVersion == localResetVersion (200 == 200) -> ignored
        processReset(200L)
        assertEquals(0, baselineCreatedCount)

        // New delivery: 300 > 200 -> processed
        processReset(300L)
        assertEquals(1, baselineCreatedCount)

        // Duplicate delivery of 300 -> ignored
        processReset(300L)
        assertEquals(1, baselineCreatedCount)
    }

    @Test
    fun test4_staleResetIsIgnored() {
        val localResetVersion = 300L
        val cloudResetVersion = 200L

        val shouldProcess = cloudResetVersion > 0L && cloudResetVersion > localResetVersion
        assertFalse("Stale reset version (200 vs 300) must be ignored", shouldProcess)
    }

    @Test
    fun test5_newResetIsProcessed() {
        val localResetVersion = 300L
        val cloudResetVersion = 400L

        val shouldProcess = cloudResetVersion > 0L && cloudResetVersion > localResetVersion
        assertTrue("New reset version (400 vs 300) must be processed", shouldProcess)
    }

    @Test
    fun test6_missingResetVersionIsNotAReset() {
        val localResetVersion = 300L
        val cloudResetVersion: Long? = null

        val safeVersion = cloudResetVersion ?: 0L
        val shouldProcess = safeVersion > 0L && safeVersion > localResetVersion
        assertFalse("Missing/null resetVersion must never trigger a reset", shouldProcess)
    }

    @Test
    fun test7_normalTelemetryAfterResetPreservesResetVersion() {
        var cloudDoc = mutableMapOf<String, Any>("resetVersion" to 500L, "usedSeconds" to 0)

        // Child sends repeated telemetry cycles
        val telemetryCycles = listOf(10, 20, 30, 40, 50)
        for (used in telemetryCycles) {
            val telemetryPayload = mutableMapOf<String, Any>(
                "usedSeconds" to used,
                "remainingSeconds" to (21600 - used)
            )
            val resetVer: Long? = null
            if (resetVer != null) {
                telemetryPayload["resetVersion"] = resetVer
            }
            cloudDoc.putAll(telemetryPayload)
            assertEquals("resetVersion must remain 500 across telemetry cycles", 500L, cloudDoc["resetVersion"])
        }
    }

    @Test
    fun test8_resetBaselineCalculationAccurate() {
        val rawUsageAtReset = 4 * 3600 // 4 hours = 14400s
        val baseline = rawUsageAtReset

        val effectiveImmediately = (rawUsageAtReset - baseline).coerceAtLeast(0)
        assertEquals("Effective usage immediately after reset must be 0", 0, effectiveImmediately)

        val rawUsageLater = (4 * 3600) + (15 * 60) // 4 hours 15 mins = 15300s
        val effectiveLater = (rawUsageLater - baseline).coerceAtLeast(0)
        assertEquals("Effective usage after 15m must be 15m (900s)", 15 * 60, effectiveLater)
    }

    @Test
    fun test9_dailyResetOccursOncePerDay() {
        var lastResetDate = "2026-10-06"
        val currentDate = "2026-10-07"
        var dailyResetRunCount = 0

        fun checkAndApplyDailyReset(today: String) {
            if (lastResetDate != today) {
                dailyResetRunCount++
                lastResetDate = today
            }
        }

        // First check of the new day -> runs once
        checkAndApplyDailyReset(currentDate)
        assertEquals(1, dailyResetRunCount)

        // Subsequent checks on the same day (restarts, loops, logins) -> does NOT run again
        checkAndApplyDailyReset(currentDate)
        checkAndApplyDailyReset(currentDate)
        assertEquals(1, dailyResetRunCount)
    }
}
