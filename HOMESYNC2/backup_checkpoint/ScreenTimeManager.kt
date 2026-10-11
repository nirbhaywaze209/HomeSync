package com.homesync.app.util

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import java.util.Calendar
import java.util.Locale

object ScreenTimeManager {
    private const val TAG = "HomeSyncScreenTime"
    private const val PREFS_NAME = "homesync_screentime_prefs"
    const val DEFAULT_ALLOWANCE_SECONDS = 6 * 3600 // 6 Hours = 21,600 Seconds

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun cleanChildId(childId: String): String {
        return childId.trim().uppercase()
    }

    private fun getRemainingKey(childId: String) = "remaining_seconds_$childId"
    private fun getLockedKey(childId: String) = "is_locked_$childId"
    private fun getTotalKey(childId: String) = "total_allowance_$childId"
    private fun getUsedKey(childId: String) = "used_seconds_$childId"
    private fun getLastCmdIdKey(childId: String) = "last_cmd_id_$childId"
    private fun getLastCmdTsKey(childId: String) = "last_cmd_ts_$childId"
    private fun getRemoteLockedKey(childId: String) = "remote_locked_$childId"

    fun getLastCommandTimestamp(context: Context, childId: String): Long {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return 0L
        return getPrefs(context).getLong(getLastCmdTsKey(cleanId), 0L)
    }

    fun getLastCommandId(context: Context, childId: String): String {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return ""
        return getPrefs(context).getString(getLastCmdIdKey(cleanId), "") ?: ""
    }

    fun isRemoteLocked(context: Context, childId: String): Boolean {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return false
        return getPrefs(context).getBoolean(getRemoteLockedKey(cleanId), false)
    }

    fun saveLastCommand(context: Context, childId: String, cmdId: String, cmdTimestamp: Long, remoteLock: Boolean) {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return
        getPrefs(context).edit()
            .putString(getLastCmdIdKey(cleanId), cmdId)
            .putLong(getLastCmdTsKey(cleanId), cmdTimestamp)
            .putBoolean(getRemoteLockedKey(cleanId), remoteLock)
            .putBoolean(getLockedKey(cleanId), remoteLock)
            .apply()
    }

    fun setLocalLocked(context: Context, childId: String, locked: Boolean) {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return
        getPrefs(context).edit().putBoolean(getLockedKey(cleanId), locked).apply()
    }

    /**
     * Checks if Usage Access permission is granted in Android Settings.
     * Note: PACKAGE_USAGE_STATS cannot be granted automatically at runtime;
     * the child/user must explicitly grant it via Android Settings.
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        if (appOps == null) {
            Log.i(TAG, "SCREEN_TIME_USAGE_ACCESS permission state=DENIED")
            return false
        }
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        val granted = (mode == AppOpsManager.MODE_ALLOWED)
        Log.i(TAG, "SCREEN_TIME_USAGE_ACCESS permission state=${if (granted) "GRANTED" else "DENIED"}")
        return granted
    }

    /**
     * Opens Android's native Usage Access Settings screen.
     */
    fun openUsageAccessSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch ACTION_USAGE_ACCESS_SETTINGS", e)
        }
    }

    fun get530AmKolkataBoundary(nowMs: Long = System.currentTimeMillis()): Long {
        val zoneId = java.time.ZoneId.of("Asia/Kolkata")
        val zonedDateTime = java.time.Instant.ofEpochMilli(nowMs).atZone(zoneId)
        var target530 = zonedDateTime.withHour(5).withMinute(30).withSecond(0).withNano(0)
        if (zonedDateTime.isBefore(target530)) {
            target530 = target530.minusDays(1)
        }
        return target530.toInstant().toEpochMilli()
    }

    private fun getLastResetBoundaryKey(childId: String) = "last_reset_boundary_$childId"

    fun checkAndApplyDailyReset(context: Context, childId: String) {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return
        val currentBoundary = get530AmKolkataBoundary()
        val prefs = getPrefs(context)
        val lastReset = prefs.getLong(getLastResetBoundaryKey(cleanId), 0L)
        if (currentBoundary > lastReset) {
            Log.i(TAG, "SCREEN_TIME_DAILY_RESET_APPLIED childCode=$cleanId boundary=$currentBoundary")
            resetToSixHours(context, cleanId)
            ParentalControlManager.setCurfewOverride(context, cleanId, false)
            prefs.edit().putLong(getLastResetBoundaryKey(cleanId), currentBoundary).apply()
            FirebaseRealtimeSyncManager.syncScreenTimeReset(cleanId, currentBoundary)
        }
    }

    /**
     * Authoritatively calculates actual device foreground app usage for today
     * using Android's UsageStatsManager.
     *
     * EXCLUSION RULE: HomeSync's own package is excluded from accumulated usage
     * so internal app time does not consume the child's parental screen-time allowance.
     *
     * If Usage Access permission is not granted, returns -1 to indicate unavailable
     * rather than fabricating fake usage.
     */
    fun getRealDeviceUsageTodaySeconds(context: Context): Int {
        if (!hasUsageStatsPermission(context)) return -1

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return -1

        val startTime = get530AmKolkataBoundary()
        val endTime = System.currentTimeMillis()

        var totalForegroundMs = 0L
        val homeSyncPackage = context.packageName

        try {
            val statsMap = usageStatsManager.queryAndAggregateUsageStats(startTime, endTime)
            if (statsMap != null && statsMap.isNotEmpty()) {
                for ((pkg, stats) in statsMap) {
                    if (pkg == homeSyncPackage) continue // Exclude HomeSync itself
                    if (stats.totalTimeInForeground > 0) {
                        totalForegroundMs += stats.totalTimeInForeground
                    }
                }
            } else {
                val statsList = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
                if (statsList != null) {
                    for (stats in statsList) {
                        if (stats.packageName == homeSyncPackage) continue // Exclude HomeSync itself
                        if (stats.totalTimeInForeground > 0) {
                            totalForegroundMs += stats.totalTimeInForeground
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying UsageStatsManager", e)
            return -1
        }

        return (totalForegroundMs / 1000).toInt()
    }

    fun getTotalAllowance(context: Context, childId: String): Int {
        val prefs = getPrefs(context)
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return DEFAULT_ALLOWANCE_SECONDS
        return prefs.getInt(getTotalKey(cleanId), DEFAULT_ALLOWANCE_SECONDS)
    }

    fun getRemainingSeconds(context: Context, childId: String): Int {
        val prefs = getPrefs(context)
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return DEFAULT_ALLOWANCE_SECONDS
        if (isDeviceLocked(context, cleanId)) return 0

        return prefs.getInt(getRemainingKey(cleanId), DEFAULT_ALLOWANCE_SECONDS)
    }

    fun saveTotalAllowance(context: Context, childId: String, total: Int) {
        val prefs = getPrefs(context)
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return
        prefs.edit()
            .putInt(getTotalKey(cleanId), total.coerceAtLeast(0))
            .apply()
    }

    fun saveRemainingSeconds(context: Context, childId: String, seconds: Int) {
        val prefs = getPrefs(context)
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return
        val clamped = seconds.coerceAtLeast(0)
        prefs.edit()
            .putInt(getRemainingKey(cleanId), clamped)
            .apply()
    }

    fun isDeviceLocked(context: Context, childId: String): Boolean {
        val prefs = getPrefs(context)
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return false

        // 1. Authoritative remote lock
        if (isRemoteLocked(context, cleanId)) return true

        // 2. Curfew rule check (honoring cloud curfew override)
        val rules = ParentalControlManager.getRules(context, cleanId)
        if (!ParentalControlManager.isCurfewOverridden(context, cleanId) && ParentalControlManager.isCurfewActiveNow(rules, context)) {
            return true
        }

        // 3. Explicit local locked flag
        val isLocallyLocked = prefs.getBoolean(getLockedKey(cleanId), false)
        if (isLocallyLocked) return true

        // 4. Genuine quota exhaustion (only if usage has actually reached total allowance)
        val total = getTotalAllowance(context, cleanId)
        val remaining = prefs.getInt(getRemainingKey(cleanId), DEFAULT_ALLOWANCE_SECONDS)
        val used = prefs.getInt(getUsedKey(cleanId), 0)
        if (total > 0 && remaining <= 0 && used >= total) {
            return true
        }

        return false
    }

    fun grantExtraTime(context: Context, childId: String, seconds: Int) {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return

        val now = System.currentTimeMillis()
        val cmdId = "GRANT_${seconds}_$now"
        Log.i(TAG, "SCREEN_TIME_COMMAND_SENT childCode=$cleanId commandId=$cmdId command=+${seconds}s timestamp=$now")
        Log.i(TAG, "SCREEN_TIME_GUARDIAN_TARGET childCode=$cleanId")
        Log.i(TAG, "SCREEN_TIME_RTDB_PATH path=hs_screentime/$cleanId")
        Log.i(TAG, "SCREEN_TIME_COMMAND_WRITE childCode=$cleanId command=+${seconds}s")
        Log.i(TAG, "SCREEN_TIME_GRANT_START childCode=$cleanId seconds=$seconds")

        val currentAllowance = getTotalAllowance(context, cleanId)
        val currentUsed = getUsedSeconds(context, cleanId)
        // If current allowance is <= used time, dynamically extend so child gets the full extra time
        val newAllowance = if (currentAllowance <= currentUsed) (currentUsed + seconds) else (currentAllowance + seconds)
        val newRemaining = (newAllowance - currentUsed).coerceAtLeast(seconds)

        // Clear curfew override since guardian granted time
        ParentalControlManager.setCurfewOverride(context, cleanId, true)

        val rtdb = FirebaseRealtimeSyncManager.getRtdb()
        if (rtdb != null) {
            val commandPayload = mapOf<String, Any>(
                "childCode" to cleanId,
                "remoteAllowance" to newAllowance,
                "totalAllowance" to newAllowance,
                "remainingSeconds" to newRemaining,
                "remoteLock" to false,
                "isLocked" to false,
                "commandId" to cmdId,
                "commandTimestamp" to now,
                "curfewOverride" to true,
                "curfewOverrideTimestamp" to now,
                "updatedAt" to now,
                "sourceRole" to "GUARDIAN"
            )
            rtdb.getReference("hs_screentime").child(cleanId).updateChildren(commandPayload)
                .addOnSuccessListener {
                    Log.i(TAG, "SCREEN_TIME_GRANT_SUCCESS childCode=$cleanId seconds=$seconds newAllowance=$newAllowance")
                    Log.i(TAG, "SCREEN_TIME_COMMAND_APPLIED childCode=$cleanId commandId=$cmdId action=GRANT_${seconds}s timestamp=$now")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "SCREEN_TIME_RTDB_ERROR code=WRITE_FAILED message=${e.message} path=hs_screentime/$cleanId", e)
                }
        }

        // Keep local cache updated
        saveLastCommand(context, cleanId, cmdId, now, false)
        getPrefs(context).edit()
            .putInt(getTotalKey(cleanId), newAllowance)
            .putInt(getRemainingKey(cleanId), newRemaining)
            .putBoolean(getLockedKey(cleanId), false)
            .apply()

        // Dual-Sync to Firestore (non-competing cloud backup)
        FirebaseSyncManager.syncScreenTimeToCloud(
            childCode = cleanId,
            remainingSeconds = newRemaining,
            isLocked = false,
            totalAllowance = newAllowance,
            usedSeconds = currentUsed
        )
    }

    fun setDeviceLocked(context: Context, childId: String, locked: Boolean) {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return

        val now = System.currentTimeMillis()
        val cmdId = "${if (locked) "LOCK" else "UNLOCK"}_$now"
        Log.i(TAG, "SCREEN_TIME_COMMAND_SENT childCode=$cleanId commandId=$cmdId command=${if (locked) "LOCK" else "UNLOCK"} timestamp=$now")
        Log.i(TAG, "SCREEN_TIME_GUARDIAN_TARGET childCode=$cleanId")
        Log.i(TAG, "SCREEN_TIME_RTDB_PATH path=hs_screentime/$cleanId")
        Log.i(TAG, "SCREEN_TIME_COMMAND_WRITE childCode=$cleanId command=${if (locked) "LOCK" else "UNLOCK"}")
        Log.i(TAG, "SCREEN_TIME_LOCK_COMMAND childCode=$cleanId locked=$locked")

        val currentAllowance = getTotalAllowance(context, cleanId)
        val currentUsed = getUsedSeconds(context, cleanId)

        val effectiveAllowance: Int
        val effectiveRem: Int

        if (locked) {
            effectiveAllowance = currentAllowance
            effectiveRem = 0
            ParentalControlManager.setCurfewOverride(context, cleanId, false)
        } else {
            ParentalControlManager.setCurfewOverride(context, cleanId, true)
            // If allowance was exhausted or <= used time, ensure child has at least 30m grace (1800s)
            if (currentAllowance <= currentUsed || (currentAllowance - currentUsed) <= 0) {
                effectiveAllowance = (currentUsed + 1800).coerceAtLeast(currentAllowance + 1800)
                effectiveRem = 1800
            } else {
                effectiveAllowance = currentAllowance
                effectiveRem = (currentAllowance - currentUsed).coerceAtLeast(1800)
            }
        }

        val rtdb = FirebaseRealtimeSyncManager.getRtdb()
        if (rtdb != null) {
            val commandPayload = mutableMapOf<String, Any>(
                "childCode" to cleanId,
                "remoteLock" to locked,
                "isLocked" to locked,
                "commandId" to cmdId,
                "commandTimestamp" to now,
                "curfewOverride" to (!locked),
                "curfewOverrideTimestamp" to now,
                "updatedAt" to now,
                "sourceRole" to "GUARDIAN"
            )
            if (!locked) {
                commandPayload["remoteAllowance"] = effectiveAllowance
                commandPayload["totalAllowance"] = effectiveAllowance
                commandPayload["remainingSeconds"] = effectiveRem
            } else {
                commandPayload["remainingSeconds"] = 0
            }
            rtdb.getReference("hs_screentime").child(cleanId).updateChildren(commandPayload)
                .addOnSuccessListener {
                    Log.i(TAG, "SCREEN_TIME_COMMAND_APPLIED childCode=$cleanId commandId=$cmdId action=${if (locked) "LOCK" else "UNLOCK"} timestamp=$now")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "SCREEN_TIME_RTDB_ERROR code=WRITE_FAILED message=${e.message} path=hs_screentime/$cleanId", e)
                }
        }

        // Keep local cache updated
        saveLastCommand(context, cleanId, cmdId, now, locked)
        val prefs = getPrefs(context)
        prefs.edit()
            .putBoolean(getLockedKey(cleanId), locked)
            .putInt(getRemainingKey(cleanId), effectiveRem)
            .putInt(getTotalKey(cleanId), effectiveAllowance)
            .apply()

        // Dual-Sync to Firestore (non-competing cloud backup)
        FirebaseSyncManager.syncScreenTimeToCloud(
            childCode = cleanId,
            remainingSeconds = effectiveRem,
            isLocked = locked,
            totalAllowance = effectiveAllowance,
            usedSeconds = currentUsed
        )
    }

    /**
     * Server-state-safe Child periodic usage reporting.
     * Uses updateChildren() with ONLY telemetry fields (usedSeconds, remainingSeconds, telemetryTimestamp).
     * Guardian command fields (remoteLock, remoteAllowance, commandId, commandTimestamp) are NEVER overwritten.
     */
    fun updateUsageFromChild(context: Context, childId: String, actualUsedSeconds: Int) {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return

        val currentAllowance = getTotalAllowance(context, cleanId)
        val isLocallyLocked = isDeviceLocked(context, cleanId)
        val quotaExhausted = actualUsedSeconds >= currentAllowance
        val remaining = if (isLocallyLocked || quotaExhausted) 0 else (currentAllowance - actualUsedSeconds).coerceAtLeast(0)
        val now = System.currentTimeMillis()

        val rtdb = FirebaseRealtimeSyncManager.getRtdb()
        if (rtdb != null) {
            val telemetryUpdates = mapOf<String, Any>(
                "usedSeconds" to actualUsedSeconds,
                "remainingSeconds" to remaining,
                "telemetryTimestamp" to now
            )
            rtdb.getReference("hs_screentime").child(cleanId).updateChildren(telemetryUpdates)
                .addOnSuccessListener {
                    Log.i(TAG, "SCREEN_TIME_TELEMETRY_UPDATED childCode=$cleanId usedSeconds=$actualUsedSeconds remainingSeconds=$remaining timestamp=$now")
                    Log.i(TAG, "SCREEN_TIME_SYNC childCode=$cleanId used=$actualUsedSeconds remaining=$remaining allowance=$currentAllowance")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "SCREEN_TIME_RTDB_ERROR code=WRITE_FAILED message=${e.message} path=hs_screentime/$cleanId", e)
                }
        }

        val prefs = getPrefs(context)
        prefs.edit()
            .putInt(getRemainingKey(cleanId), remaining)
            .putInt(getUsedKey(cleanId), actualUsedSeconds)
            .apply()

        // Dual-Sync to Firestore (non-competing cloud backup)
        FirebaseSyncManager.syncScreenTimeToCloud(
            childCode = cleanId,
            remainingSeconds = remaining,
            isLocked = isLocallyLocked || quotaExhausted,
            totalAllowance = currentAllowance,
            usedSeconds = actualUsedSeconds
        )
    }

    fun resetToSixHours(context: Context, childId: String) {
        val prefs = getPrefs(context)
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return

        prefs.edit()
            .putInt(getTotalKey(cleanId), DEFAULT_ALLOWANCE_SECONDS)
            .putInt(getRemainingKey(cleanId), DEFAULT_ALLOWANCE_SECONDS)
            .putInt(getUsedKey(cleanId), 0)
            .putBoolean(getLockedKey(cleanId), false)
            .apply()

        Log.i(TAG, "SCREEN_TIME_SYNC childCode=$cleanId used=0 remaining=$DEFAULT_ALLOWANCE_SECONDS allowance=$DEFAULT_ALLOWANCE_SECONDS locked=false")
        FirebaseRealtimeSyncManager.syncScreenTime(
            childCode = cleanId,
            remainingSeconds = DEFAULT_ALLOWANCE_SECONDS,
            isLocked = false,
            totalAllowance = DEFAULT_ALLOWANCE_SECONDS,
            usedSeconds = 0
        )
    }

    /**
     * Guardian-initiated manual reset to 6 hours.
     * Uses the canonical Guardian command pattern (commandId=RESET_<ts>) via updateChildren
     * so the Child's listenScreenTimeWithCommand picks it up instantly as a new command.
     */
    fun resetToSixHoursAsCommand(context: Context, childId: String) {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return

        val now = System.currentTimeMillis()
        val cmdId = "RESET_$now"
        Log.i(TAG, "SCREEN_TIME_COMMAND_SENT childCode=$cleanId commandId=$cmdId command=RESET_6H timestamp=$now")

        val rtdb = FirebaseRealtimeSyncManager.getRtdb()
        if (rtdb != null) {
            val commandPayload = mapOf<String, Any>(
                "childCode" to cleanId,
                "remoteAllowance" to DEFAULT_ALLOWANCE_SECONDS,
                "totalAllowance" to DEFAULT_ALLOWANCE_SECONDS,
                "remainingSeconds" to DEFAULT_ALLOWANCE_SECONDS,
                "usedSeconds" to 0,
                "remoteLock" to false,
                "isLocked" to false,
                "commandId" to cmdId,
                "commandTimestamp" to now,
                "curfewOverride" to true,
                "curfewOverrideTimestamp" to now,
                "updatedAt" to now,
                "sourceRole" to "GUARDIAN"
            )
            rtdb.getReference("hs_screentime").child(cleanId).updateChildren(commandPayload)
                .addOnSuccessListener {
                    Log.i(TAG, "SCREEN_TIME_RESET_COMMAND_SUCCESS childCode=$cleanId commandId=$cmdId")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "SCREEN_TIME_RTDB_ERROR code=WRITE_FAILED message=${e.message} path=hs_screentime/$cleanId", e)
                }
        }

        // Keep local cache updated
        saveLastCommand(context, cleanId, cmdId, now, false)
        ParentalControlManager.setCurfewOverride(context, cleanId, true)
        getPrefs(context).edit()
            .putInt(getTotalKey(cleanId), DEFAULT_ALLOWANCE_SECONDS)
            .putInt(getRemainingKey(cleanId), DEFAULT_ALLOWANCE_SECONDS)
            .putInt(getUsedKey(cleanId), 0)
            .putBoolean(getLockedKey(cleanId), false)
            .apply()

        // Dual-Sync to Firestore backup
        FirebaseSyncManager.syncScreenTimeToCloud(
            childCode = cleanId,
            remainingSeconds = DEFAULT_ALLOWANCE_SECONDS,
            isLocked = false,
            totalAllowance = DEFAULT_ALLOWANCE_SECONDS,
            usedSeconds = 0
        )
    }

    fun applyRemoteUpdate(
        context: Context,
        childId: String,
        remainingSeconds: Int,
        isLocked: Boolean,
        totalAllowance: Int,
        usedSeconds: Int = (totalAllowance - remainingSeconds).coerceAtLeast(0)
    ) {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return
        val prefs = getPrefs(context)
        val rem = if (isLocked) 0 else remainingSeconds
        prefs.edit()
            .putInt(getTotalKey(cleanId), totalAllowance)
            .putInt(getRemainingKey(cleanId), rem)
            .putInt(getUsedKey(cleanId), usedSeconds)
            .putBoolean(getLockedKey(cleanId), isLocked)
            .putBoolean(getRemoteLockedKey(cleanId), isLocked)
            .apply()
    }

    fun syncToCloud(context: Context, childId: String) {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return
        val rem = getRemainingSeconds(context, cleanId)
        val locked = isDeviceLocked(context, cleanId)
        val tot = getTotalAllowance(context, cleanId)
        val realUsed = getRealDeviceUsageTodaySeconds(context)
        val used = if (realUsed >= 0) realUsed else getUsedSeconds(context, cleanId)

        Log.i(TAG, "SCREEN_TIME_SYNC childCode=$cleanId used=$used remaining=$rem allowance=$tot locked=$locked")
        FirebaseRealtimeSyncManager.syncScreenTime(
            childCode = cleanId,
            remainingSeconds = rem,
            isLocked = locked,
            totalAllowance = tot,
            usedSeconds = used
        )
    }

    fun addBonusMinutes(context: Context, childId: String, bonusMinutes: Int) {
        val prefs = getPrefs(context)
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return

        val current = getRemainingSeconds(context, cleanId)
        val added = bonusMinutes * 60
        val newTotal = current + added
        val currentAllowance = getTotalAllowance(context, cleanId)
        val newAllowance = if (newTotal > currentAllowance) newTotal else currentAllowance

        prefs.edit()
            .putInt(getTotalKey(cleanId), newAllowance)
            .putInt(getRemainingKey(cleanId), newTotal)
            .putBoolean(getLockedKey(cleanId), false)
            .apply()
    }

    /**
     * Calculates used screen time seconds for a child.
     */
    fun getUsedSeconds(context: Context, childId: String): Int {
        val cleanId = cleanChildId(childId)
        val prefs = getPrefs(context)
        val storedUsed = prefs.getInt(getUsedKey(cleanId), -1)
        if (storedUsed >= 0) return storedUsed
        val total = getTotalAllowance(context, cleanId)
        val remaining = getRemainingSeconds(context, cleanId)
        return (total - remaining).coerceAtLeast(0)
    }

    /**
     * Formats seconds into human readable format like "1h 30m" or "45m".
     */
    fun formatHoursAndMinutes(seconds: Int): String {
        val sec = seconds.coerceAtLeast(0)
        val hours = sec / 3600
        val minutes = (sec % 3600) / 60
        if (hours > 0) {
            return "${hours}h ${minutes}m"
        }
        return "${minutes}m"
    }

    /**
     * Formats seconds into HH:MM:SS (e.g. 06:00:00, 05:59:58).
     */
    fun formatTime(seconds: Int): String {
        val sec = seconds.coerceAtLeast(0)
        val hours = sec / 3600
        val minutes = (sec % 3600) / 60
        val remainingSec = sec % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, remainingSec)
    }
}
