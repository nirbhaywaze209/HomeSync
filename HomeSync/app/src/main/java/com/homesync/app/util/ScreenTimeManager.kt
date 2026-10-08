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
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object ScreenTimeManager {
    private const val TAG = "HomeSyncScreenTime"
    private const val PREFS_NAME = "homesync_screentime_prefs"
    const val DEFAULT_ALLOWANCE_SECONDS = 6 * 3600 // 6 Hours = 21,600 Seconds

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun cleanChildId(childId: String): String {
        return childId.trim().uppercase()
    }

    /**
     * Authoritative method to determine the current screen-time date (YYYY-MM-DD).
     * Never relies on elapsed time since app launch.
     */
    fun getCurrentScreenTimeDate(): String {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        
        // If time is before 5:30 AM, we consider it part of the previous day
        if (hour < 5 || (hour == 5 && minute < 30)) {
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(cal.time)
    }

    // Storage key generators
    private fun getDateKey(childId: String) = "screen_time_date_$childId"
    private fun getDailyUsedKey(childId: String, date: String) = "used_seconds_${childId}_$date"
    private fun getDailyRemainingKey(childId: String, date: String) = "remaining_seconds_${childId}_$date"
    private fun getDailyTotalKey(childId: String, date: String) = "total_allowance_${childId}_$date"
    private fun getDailyResetBaseKey(childId: String, date: String) = "reset_base_usage_${childId}_$date"
    private fun getDailyResetVersionKey(childId: String, date: String) = "reset_version_${childId}_$date"
    private fun getDailyInteractiveKey(childId: String, date: String) = "interactive_seconds_${childId}_$date"

    // Backward-compatible keys
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
        getPrefs(context).edit()
            .putBoolean(getLockedKey(cleanId), locked)
            .putBoolean(getRemoteLockedKey(cleanId), locked)
            .apply()
    }

    fun setRemoteLocked(context: Context, childId: String, locked: Boolean) {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return
        getPrefs(context).edit()
            .putBoolean(getRemoteLockedKey(cleanId), locked)
            .putBoolean(getLockedKey(cleanId), locked)
            .apply()
    }

    /**
     * Checks if Usage Access permission is granted in Android Settings.
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

    fun getDailyResetVersion(context: Context, childId: String, date: String = getCurrentScreenTimeDate()): Long {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return 0L
        return getPrefs(context).getLong(getDailyResetVersionKey(cleanId, date), 0L)
    }

    /**
     * Retrieves the reset base usage recorded for the given date.
     * When Guardian resets screen time or a new day begins, resetBaseUsage stores the
     * raw device usage accumulated up to that point.
     */
    fun getResetBaseUsage(context: Context, childId: String, date: String = getCurrentScreenTimeDate()): Int {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return 0
        return getPrefs(context).getInt(getDailyResetBaseKey(cleanId, date), 0)
    }

    /**
     * Records a reset baseline for a child on the current date.
     * Effective usage from this moment forward will be (rawDeviceUsage - resetBaseUsage).
     */
    fun recordResetBaseForChild(context: Context, childId: String, resetTimestamp: Long = System.currentTimeMillis()) {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return
        val today = getCurrentScreenTimeDate()
        val prefs = getPrefs(context)
        
        var rawUsage = getRawDeviceUsageTodaySeconds(context)
        val oldBase = getResetBaseUsage(context, cleanId, today)
        val interactive = prefs.getInt(getDailyInteractiveKey(cleanId, today), 0)
        val storedUsed = prefs.getInt(getDailyUsedKey(cleanId, today), 0)
        val prevEffective = maxOf(interactive, storedUsed)

        if (rawUsage < 0) {
            rawUsage = (oldBase + prevEffective).coerceAtLeast(0)
        } else {
            rawUsage = rawUsage.coerceAtLeast(0)
        }

        prefs.edit()
            .putString(getDateKey(cleanId), today)
            .putInt(getDailyResetBaseKey(cleanId, today), rawUsage)
            .putInt(getDailyInteractiveKey(cleanId, today), 0)
            .putInt(getDailyUsedKey(cleanId, today), 0)
            .putInt(getDailyRemainingKey(cleanId, today), DEFAULT_ALLOWANCE_SECONDS)
            .putInt(getDailyTotalKey(cleanId, today), DEFAULT_ALLOWANCE_SECONDS)
            .putLong(getDailyResetVersionKey(cleanId, today), resetTimestamp)
            .putInt(getUsedKey(cleanId), 0)
            .putInt(getRemainingKey(cleanId), DEFAULT_ALLOWANCE_SECONDS)
            .putInt(getTotalKey(cleanId), DEFAULT_ALLOWANCE_SECONDS)
            .putBoolean(getLockedKey(cleanId), false)
            .putBoolean(getRemoteLockedKey(cleanId), false)
            .apply()
        Log.i(TAG, "BASELINE childCode=$cleanId date=$today baseline=$rawUsage resetVersion=$resetTimestamp")
        Log.i(TAG, "SCREEN_TIME_RESET_BASE_RECORDED childCode=$cleanId date=$today rawBase=$rawUsage resetTs=$resetTimestamp")
    }

    /**
     * Checks if a new calendar day has started for the given child.
     * If yesterday's date is stored (or none exists), resets today's usage to 0
     * and initializes today's record.
     */
    fun checkAndApplyDailyReset(context: Context, childId: String) {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return
        val today = getCurrentScreenTimeDate()
        val prefs = getPrefs(context)
        val lastDate = prefs.getString(getDateKey(cleanId), "")
        if (lastDate != today) {
            Log.i(TAG, "SCREEN_TIME_DAILY_RESET_APPLIED childCode=$cleanId date=$today previousDate=$lastDate")
            
            var rawUsage = getRawDeviceUsageTodaySeconds(context)
            if (rawUsage < 0) {
                // For daily reset, if we can't get raw usage, we just use 0 because it's a new day anyway!
                // Wait, if we use 0, and later it returns real raw usage (e.g. 5400), it'll look like 5400 used!
                // Actually, if it's a new day, UsageStatsManager resets its daily stats to 0 anyway!
                // So if we can't read it, 0 is a very safe assumption for a new day.
                rawUsage = 0
            } else {
                rawUsage = rawUsage.coerceAtLeast(0)
            }
            
            val now = System.currentTimeMillis()
            val rules = ParentalControlManager.getRules(context, cleanId)
            val configuredDailyAllowance = if (rules.maxDailyAllowanceMinutes > 0) {
                rules.maxDailyAllowanceMinutes * 60
            } else if (rules.maxDailyAllowanceHours > 0) {
                (rules.maxDailyAllowanceHours * 3600).toInt()
            } else {
                DEFAULT_ALLOWANCE_SECONDS
            }
            prefs.edit()
                .putString(getDateKey(cleanId), today)
                .putInt(getDailyUsedKey(cleanId, today), 0)
                .putInt(getDailyInteractiveKey(cleanId, today), 0)
                .putInt(getDailyRemainingKey(cleanId, today), configuredDailyAllowance)
                .putInt(getDailyTotalKey(cleanId, today), configuredDailyAllowance)
                .putInt(getDailyResetBaseKey(cleanId, today), rawUsage)
                .putLong(getDailyResetVersionKey(cleanId, today), now)
                .putInt(getUsedKey(cleanId), 0)
                .putInt(getRemainingKey(cleanId), configuredDailyAllowance)
                .putInt(getTotalKey(cleanId), configuredDailyAllowance)
                .putBoolean(getLockedKey(cleanId), false)
                .putBoolean(getRemoteLockedKey(cleanId), false)
                .apply()

            ParentalControlManager.setCurfewOverride(context, cleanId, false)
            FirebaseRealtimeSyncManager.syncScreenTimeReset(cleanId, now)
        }
    }

    /**
     * Records an incremental usage tick when the child's screen is active and interactive.
     * Takes the maximum of accumulated interactive time and Android UsageStatsManager delta
     * to guarantee real-time counting while also capturing background third-party app usage.
     */
    fun recordUsageTick(context: Context, childId: String, deltaSeconds: Int): Int {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return 0
        val today = getCurrentScreenTimeDate()
        val prefs = getPrefs(context)

        val hasPerm = hasUsageStatsPermission(context)
        val raw = if (hasPerm) getRawDeviceUsageTodaySeconds(context) else -1
        val base = getResetBaseUsage(context, cleanId, today)
        val rawDelta = if (raw >= base && base > 0) (raw - base) else if (raw >= 0 && base == 0) raw else 0

        val currentInteractive = prefs.getInt(getDailyInteractiveKey(cleanId, today), 0)
        val syncedInteractive = if (hasPerm && raw >= 0 && rawDelta > currentInteractive) {
            rawDelta
        } else {
            currentInteractive
        }
        val newInteractive = (syncedInteractive + deltaSeconds).coerceAtLeast(0)
        prefs.edit().putInt(getDailyInteractiveKey(cleanId, today), newInteractive).apply()

        val effectiveUsed = if (hasPerm && raw >= 0) {
            maxOf(newInteractive, rawDelta)
        } else {
            newInteractive
        }

        prefs.edit()
            .putString(getDateKey(cleanId), today)
            .putInt(getDailyUsedKey(cleanId, today), effectiveUsed)
            .putInt(getUsedKey(cleanId), effectiveUsed)
            .apply()

        Log.d(TAG, "RAW_USAGE raw=$raw hasPerm=$hasPerm")
        Log.i(TAG, "EFFECTIVE_USAGE childCode=$cleanId effective=$effectiveUsed base=$base rawDelta=$rawDelta interactive=$newInteractive")

        return effectiveUsed
    }

    /**
     * Authoritatively calculates actual device foreground app usage for today
     * using Android's UsageStatsManager starting from midnight today (00:00:00).
     *
     * EXCLUSION RULE: HomeSync's own package is excluded.
     */
    fun getRawDeviceUsageTodaySeconds(context: Context): Int {
        if (!hasUsageStatsPermission(context)) return -1

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return -1

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        
        // If time is before 5:30 AM, we consider it part of the previous day
        if (hour < 5 || (hour == 5 && minute < 30)) {
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        cal.set(Calendar.HOUR_OF_DAY, 5)
        cal.set(Calendar.MINUTE, 30)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startTime = cal.timeInMillis
        val endTime = System.currentTimeMillis()

        var totalForegroundMs = 0L

        try {
            val statsMap = usageStatsManager.queryAndAggregateUsageStats(startTime, endTime)
            if (statsMap != null && statsMap.isNotEmpty()) {
                for ((pkg, stats) in statsMap) {
                    // EXCLUSION RULE: HomeSync's own package, Android System UI, and core OS are excluded
                    if (pkg == context.packageName || stats.packageName == context.packageName) continue
                    if (pkg == "com.android.systemui" || pkg == "android") continue
                    if (stats.totalTimeInForeground > 0) {
                        totalForegroundMs += stats.totalTimeInForeground
                    }
                }
            } else {
                val statsList = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
                if (statsList != null) {
                    val maxPerPackage = mutableMapOf<String, Long>()
                    for (stats in statsList) {
                        val pkg = stats.packageName ?: continue
                        if (pkg == context.packageName || pkg == "com.android.systemui" || pkg == "android") continue
                        if (stats.totalTimeInForeground > 0) {
                            val curr = maxPerPackage[pkg] ?: 0L
                            if (stats.totalTimeInForeground > curr) {
                                maxPerPackage[pkg] = stats.totalTimeInForeground
                            }
                        }
                    }
                    totalForegroundMs = maxPerPackage.values.sum()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying UsageStatsManager", e)
            return -1
        }

        return (totalForegroundMs / 1000).toInt()
    }

    /**
     * Returns the effective foreground app usage for today, taking into account
     * interactive screen time and any resetBaseUsage recorded for this child on today's date.
     */
    fun getRealDeviceUsageTodaySeconds(context: Context, childId: String = ""): Int {
        val raw = getRawDeviceUsageTodaySeconds(context)
        if (raw < 0 && !hasUsageStatsPermission(context)) {
            val cleanId = cleanChildId(childId)
            if (cleanId.isNotBlank()) {
                val today = getCurrentScreenTimeDate()
                return getPrefs(context).getInt(getDailyUsedKey(cleanId, today), 0)
            }
            return -1
        }
        if (childId.isBlank()) return raw
        val cleanId = cleanChildId(childId)
        val today = getCurrentScreenTimeDate()
        val prefs = getPrefs(context)
        val base = getResetBaseUsage(context, cleanId, today)
        val rawDelta = if (raw >= base && base > 0) (raw - base) else if (raw >= 0 && base == 0) raw else 0
        val interactive = prefs.getInt(getDailyInteractiveKey(cleanId, today), 0)
        val storedUsed = prefs.getInt(getDailyUsedKey(cleanId, today), 0)
        return maxOf(interactive, rawDelta, storedUsed)
    }

    fun getTotalAllowance(context: Context, childId: String): Int {
        val prefs = getPrefs(context)
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return DEFAULT_ALLOWANCE_SECONDS
        val today = getCurrentScreenTimeDate()
        val dailyTotal = prefs.getInt(getDailyTotalKey(cleanId, today), -1)
        if (dailyTotal >= 0) return dailyTotal
        return prefs.getInt(getTotalKey(cleanId), DEFAULT_ALLOWANCE_SECONDS)
    }

    fun getRemainingSeconds(context: Context, childId: String): Int {
        val prefs = getPrefs(context)
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return DEFAULT_ALLOWANCE_SECONDS
        if (isDeviceLocked(context, cleanId)) return 0

        val today = getCurrentScreenTimeDate()
        val dailyRem = prefs.getInt(getDailyRemainingKey(cleanId, today), -1)
        if (dailyRem >= 0) return dailyRem
        return prefs.getInt(getRemainingKey(cleanId), DEFAULT_ALLOWANCE_SECONDS)
    }

    fun saveTotalAllowance(context: Context, childId: String, total: Int) {
        val prefs = getPrefs(context)
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return
        val clamped = total.coerceAtLeast(0)
        val today = getCurrentScreenTimeDate()
        prefs.edit()
            .putInt(getTotalKey(cleanId), clamped)
            .putInt(getDailyTotalKey(cleanId, today), clamped)
            .apply()
    }

    fun saveRemainingSeconds(context: Context, childId: String, seconds: Int) {
        val prefs = getPrefs(context)
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return
        val clamped = seconds.coerceAtLeast(0)
        val today = getCurrentScreenTimeDate()
        prefs.edit()
            .putInt(getRemainingKey(cleanId), clamped)
            .putInt(getDailyRemainingKey(cleanId, today), clamped)
            .apply()
    }

    fun saveUsedSeconds(context: Context, childId: String, usedSeconds: Int) {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return
        val today = getCurrentScreenTimeDate()
        val clamped = usedSeconds.coerceAtLeast(0)
        getPrefs(context).edit()
            .putString(getDateKey(cleanId), today)
            .putInt(getUsedKey(cleanId), clamped)
            .putInt(getDailyUsedKey(cleanId, today), clamped)
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

    fun grantExtraTime(context: Context, childId: String, seconds: Int, customCommandId: String = "") {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return

        val now = System.currentTimeMillis()
        val today = getCurrentScreenTimeDate()
        val cmdId = customCommandId.ifBlank { "GRANT_${cleanId}_${seconds}_$now" }
        Log.i(TAG, "SCREEN_TIME_COMMAND_SENT childCode=$cleanId targetChildId=$cleanId commandId=$cmdId command=+${seconds}s timestamp=$now")
        Log.i("HomeSyncLatency", "COMMAND_PREPARED commandId=$cmdId commandType=GRANT_EXTRA_TIME childCode=$cleanId timestamp=$now")

        val currentAllowance = getTotalAllowance(context, cleanId)
        val currentUsed = getUsedSeconds(context, cleanId)
        val newAllowance = if (currentAllowance <= currentUsed) (currentUsed + seconds) else (currentAllowance + seconds)
        val newRemaining = (newAllowance - currentUsed).coerceAtLeast(seconds)

        ParentalControlManager.setCurfewOverride(context, cleanId, true)

        val rtdb = FirebaseRealtimeSyncManager.getRtdb()
        if (rtdb != null) {
            try { rtdb.goOnline() } catch (_: Exception) {}
            val commandPayload = mapOf<String, Any>(
                "childCode" to cleanId,
                "targetChildId" to cleanId,
                "commandType" to "GRANT_EXTRA_TIME",
                "date" to today,
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
                "sourceRole" to "GUARDIAN",
                "extraSeconds" to seconds
            )
            rtdb.getReference("hs_screentime").child(cleanId).updateChildren(commandPayload)
                .addOnSuccessListener {
                    val finishNow = System.currentTimeMillis()
                    Log.i(TAG, "SCREEN_TIME_GRANT_SUCCESS childCode=$cleanId seconds=$seconds newAllowance=$newAllowance")
                    Log.i("HomeSyncLatency", "COMMAND_WRITTEN commandId=$cmdId commandType=GRANT_EXTRA_TIME childCode=$cleanId latencyMs=${finishNow - now} timestamp=$finishNow")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "SCREEN_TIME_RTDB_ERROR code=WRITE_FAILED message=${e.message} path=hs_screentime/$cleanId", e)
                }
        }

        saveLastCommand(context, cleanId, cmdId, now, false)
        getPrefs(context).edit()
            .putInt(getTotalKey(cleanId), newAllowance)
            .putInt(getDailyTotalKey(cleanId, today), newAllowance)
            .putInt(getRemainingKey(cleanId), newRemaining)
            .putInt(getDailyRemainingKey(cleanId, today), newRemaining)
            .putBoolean(getLockedKey(cleanId), false)
            .putBoolean(getRemoteLockedKey(cleanId), false)
            .apply()

        CoroutineScope(Dispatchers.IO).launch {
            FirebaseSyncManager.syncScreenTimeToCloud(
                childCode = cleanId,
                remainingSeconds = newRemaining,
                isLocked = false,
                totalAllowance = newAllowance,
                usedSeconds = currentUsed,
                date = today,
                commandId = cmdId,
                commandTimestamp = now,
                curfewOverride = true,
                targetChildId = cleanId,
                commandType = "GRANT_EXTRA_TIME",
                sourceRole = "GUARDIAN"
            )
        }
    }

    fun setDeviceLocked(context: Context, childId: String, locked: Boolean, customCommandId: String = "") {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return

        val now = System.currentTimeMillis()
        val today = getCurrentScreenTimeDate()
        val cmdType = if (locked) "LOCK" else "UNLOCK"
        val standardCmdType = if (locked) "LOCK_DEVICE" else "UNLOCK_DEVICE"
        val cmdId = customCommandId.ifBlank { "${cmdType}_${cleanId}_$now" }
        Log.i(TAG, "SCREEN_TIME_COMMAND_SENT childCode=$cleanId targetChildId=$cleanId commandId=$cmdId command=$cmdType timestamp=$now")
        Log.i("HomeSyncLatency", "COMMAND_PREPARED commandId=$cmdId commandType=$standardCmdType childCode=$cleanId path=hs_screentime/$cleanId timestamp=$now")

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
            try { rtdb.goOnline() } catch (_: Exception) {}
            val commandPayload = mutableMapOf<String, Any>(
                "childCode" to cleanId,
                "targetChildId" to cleanId,
                "commandType" to standardCmdType,
                "date" to today,
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
                    val finishNow = System.currentTimeMillis()
                    Log.i(TAG, "SCREEN_TIME_COMMAND_APPLIED childCode=$cleanId commandId=$cmdId action=$cmdType timestamp=$now")
                    Log.i("HomeSyncLatency", "COMMAND_WRITTEN commandId=$cmdId commandType=$standardCmdType childCode=$cleanId path=hs_screentime/$cleanId latencyMs=${finishNow - now} timestamp=$finishNow")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "SCREEN_TIME_RTDB_ERROR code=WRITE_FAILED message=${e.message} path=hs_screentime/$cleanId", e)
                }
        }

        saveLastCommand(context, cleanId, cmdId, now, locked)
        val prefs = getPrefs(context)
        prefs.edit()
            .putBoolean(getLockedKey(cleanId), locked)
            .putBoolean(getRemoteLockedKey(cleanId), locked)
            .putInt(getRemainingKey(cleanId), effectiveRem)
            .putInt(getDailyRemainingKey(cleanId, today), effectiveRem)
            .putInt(getTotalKey(cleanId), effectiveAllowance)
            .putInt(getDailyTotalKey(cleanId, today), effectiveAllowance)
            .apply()

        CoroutineScope(Dispatchers.IO).launch {
            FirebaseSyncManager.syncScreenTimeToCloud(
                childCode = cleanId,
                remainingSeconds = effectiveRem,
                isLocked = locked,
                totalAllowance = effectiveAllowance,
                usedSeconds = currentUsed,
                date = today,
                commandId = cmdId,
                commandTimestamp = now,
                curfewOverride = false,
                targetChildId = cleanId,
                commandType = if (locked) "LOCK_DEVICE" else "UNLOCK_DEVICE",
                sourceRole = "GUARDIAN"
            )
        }
    }

    /**
     * Locks or unlocks all children simultaneously while maintaining strict per-child command isolation.
     */
    fun setAllChildrenLocked(context: Context, childrenIds: List<String>, locked: Boolean) {
        val distinctIds = childrenIds.map { cleanChildId(it) }.filter { it.isNotBlank() }.distinct()
        for (cId in distinctIds) {
            setDeviceLocked(context, cId, locked)
        }
    }

    /**
     * Resets screen time for all children simultaneously while targeting each child independently.
     */
    fun resetAllChildrenScreenTime(context: Context, childrenIds: List<String>) {
        val distinctIds = childrenIds.map { cleanChildId(it) }.filter { it.isNotBlank() }.distinct()
        for (cId in distinctIds) {
            resetToSixHoursAsCommand(context, cId)
        }
    }

    private var lastChildTelemetrySyncTime = 0L

    /**
     * Server-state-safe Child periodic usage reporting.
     * Uses updateChildren() with ONLY telemetry fields (usedSeconds, remainingSeconds, telemetryTimestamp).
     * Guardian command fields (remoteLock, remoteAllowance, commandId, commandTimestamp) are NEVER overwritten.
     */
    fun updateUsageFromChild(context: Context, childId: String, actualUsedSeconds: Int) {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return

        val today = getCurrentScreenTimeDate()
        val currentAllowance = getTotalAllowance(context, cleanId)
        val isLocallyLocked = isDeviceLocked(context, cleanId) || isRemoteLocked(context, cleanId)
        val quotaExhausted = actualUsedSeconds >= currentAllowance
        val remaining = if (isLocallyLocked || quotaExhausted) 0 else (currentAllowance - actualUsedSeconds).coerceAtLeast(0)
        val now = System.currentTimeMillis()

        // Always update local SharedPreferences instantly
        val prefs = getPrefs(context)
        prefs.edit()
            .putString(getDateKey(cleanId), today)
            .putInt(getRemainingKey(cleanId), remaining)
            .putInt(getDailyRemainingKey(cleanId, today), remaining)
            .putInt(getUsedKey(cleanId), actualUsedSeconds)
            .putInt(getDailyUsedKey(cleanId, today), actualUsedSeconds)
            .apply()

        // Throttle remote cloud network writes to once every 30 seconds unless locking state changes
        val stateChangedToLocked = (quotaExhausted || isLocallyLocked)
        val intervalElapsed = (now - lastChildTelemetrySyncTime >= 30_000L)
        if (!stateChangedToLocked && !intervalElapsed) {
            return
        }
        lastChildTelemetrySyncTime = now

        android.util.Log.i("HomeSyncScreenTime", "UPDATING_USAGE_FROM_CHILD childCode=$cleanId used=$actualUsedSeconds")

        val rtdb = FirebaseRealtimeSyncManager.getRtdb()
        if (rtdb != null) {
            val telemetryUpdates = mapOf<String, Any>(
                "usedSeconds" to actualUsedSeconds,
                "remainingSeconds" to remaining,
                "totalAllowance" to currentAllowance,
                "date" to today,
                "telemetryTimestamp" to now
            )
            Log.i("HomeSyncLatency", "ATTEMPTING_RTDB_UPDATE childCode=$cleanId used=$actualUsedSeconds")
            rtdb.getReference("hs_screentime").child(cleanId).updateChildren(telemetryUpdates)
                .addOnSuccessListener {
                    Log.i(TAG, "TELEMETRY_SYNC childCode=$cleanId used=$actualUsedSeconds rem=$remaining allowance=$currentAllowance")
                    Log.i(TAG, "SCREEN_TIME_TELEMETRY_UPDATED childCode=$cleanId usedSeconds=$actualUsedSeconds remainingSeconds=$remaining timestamp=$now")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "SCREEN_TIME_RTDB_ERROR code=WRITE_FAILED message=${e.message} path=hs_screentime/$cleanId", e)
                }
        }

        CoroutineScope(Dispatchers.IO).launch {
            FirebaseSyncManager.syncScreenTimeToCloud(
                childCode = cleanId,
                remainingSeconds = remaining,
                isLocked = isLocallyLocked || quotaExhausted,
                totalAllowance = currentAllowance,
                usedSeconds = actualUsedSeconds,
                date = today,
                sourceRole = "CHILD"
            )
        }
    }

    fun resetToSixHours(context: Context, childId: String) {
        val prefs = getPrefs(context)
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return

        val today = getCurrentScreenTimeDate()
        var rawUsage = getRawDeviceUsageTodaySeconds(context)
        if (rawUsage < 0) {
            val oldBase = getResetBaseUsage(context, cleanId, today)
            val interactive = prefs.getInt(getDailyInteractiveKey(cleanId, today), 0)
            val storedUsed = prefs.getInt(getDailyUsedKey(cleanId, today), 0)
            rawUsage = oldBase + maxOf(interactive, storedUsed)
        }
        rawUsage = rawUsage.coerceAtLeast(0)
        val now = System.currentTimeMillis()

        prefs.edit()
            .putString(getDateKey(cleanId), today)
            .putInt(getDailyResetBaseKey(cleanId, today), rawUsage)
            .putInt(getDailyUsedKey(cleanId, today), 0)
            .putInt(getDailyRemainingKey(cleanId, today), DEFAULT_ALLOWANCE_SECONDS)
            .putInt(getDailyTotalKey(cleanId, today), DEFAULT_ALLOWANCE_SECONDS)
            .putLong(getDailyResetVersionKey(cleanId, today), now)
            .putInt(getTotalKey(cleanId), DEFAULT_ALLOWANCE_SECONDS)
            .putInt(getRemainingKey(cleanId), DEFAULT_ALLOWANCE_SECONDS)
            .putInt(getUsedKey(cleanId), 0)
            .putBoolean(getLockedKey(cleanId), false)
            .putBoolean(getRemoteLockedKey(cleanId), false)
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
     * Guardian-initiated manual reset to 6 hours for a specific child.
     * Records resetBaseUsage to prevent Android UsageStatsManager from reverting to old usage,
     * updates RTDB and Firestore, and marks targetChildId and commandType explicitly.
     */
    fun resetToSixHoursAsCommand(context: Context, childId: String, customCommandId: String = "") {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return

        val now = System.currentTimeMillis()
        val today = getCurrentScreenTimeDate()
        val cmdId = customCommandId.ifBlank { "RESET_${cleanId}_$now" }
        Log.i(TAG, "RESET_CREATED childCode=$cleanId resetVersion=$now cmdId=$cmdId")
        Log.i(TAG, "SCREEN_TIME_COMMAND_SENT childCode=$cleanId targetChildId=$cleanId commandId=$cmdId command=RESET_6H timestamp=$now")
        Log.i("HomeSyncLatency", "COMMAND_PREPARED commandId=$cmdId commandType=REMOTE_RESET childCode=$cleanId timestamp=$now")

        // 1. Authoritatively record reset baseline and zero usage locally
        recordResetBaseForChild(context, cleanId, now)
        saveLastCommand(context, cleanId, cmdId, now, false)
        ParentalControlManager.setCurfewOverride(context, cleanId, true)

        val rtdb = FirebaseRealtimeSyncManager.getRtdb()
        if (rtdb != null) {
            try { rtdb.goOnline() } catch (_: Exception) {}
            val commandPayload = mapOf<String, Any>(
                "childCode" to cleanId,
                "targetChildId" to cleanId,
                "commandType" to "RESET_SCREEN_TIME",
                "date" to today,
                "remoteAllowance" to DEFAULT_ALLOWANCE_SECONDS,
                "totalAllowance" to DEFAULT_ALLOWANCE_SECONDS,
                "remainingSeconds" to DEFAULT_ALLOWANCE_SECONDS,
                "usedSeconds" to 0,
                "resetVersion" to now,
                "remoteLock" to false,
                "isLocked" to false,
                "commandId" to cmdId,
                "commandTimestamp" to now,
                "curfewOverride" to true,
                "curfewOverrideTimestamp" to now,
                "telemetryTimestamp" to now,
                "updatedAt" to now,
                "sourceRole" to "GUARDIAN"
            )
            rtdb.getReference("hs_screentime").child(cleanId).updateChildren(commandPayload)
                .addOnSuccessListener {
                    val finishNow = System.currentTimeMillis()
                    Log.i(TAG, "SCREEN_TIME_RESET_COMMAND_SUCCESS childCode=$cleanId targetChildId=$cleanId commandId=$cmdId")
                    Log.i("HomeSyncLatency", "COMMAND_WRITTEN commandId=$cmdId commandType=REMOTE_RESET childCode=$cleanId latencyMs=${finishNow - now} timestamp=$finishNow")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "SCREEN_TIME_RTDB_ERROR code=WRITE_FAILED message=${e.message} path=hs_screentime/$cleanId", e)
                }
        }

        CoroutineScope(Dispatchers.IO).launch {
            FirebaseSyncManager.syncScreenTimeToCloud(
                childCode = cleanId,
                remainingSeconds = DEFAULT_ALLOWANCE_SECONDS,
                isLocked = false,
                totalAllowance = DEFAULT_ALLOWANCE_SECONDS,
                usedSeconds = 0,
                date = today,
                resetVersion = now,
                commandId = cmdId,
                commandTimestamp = now,
                curfewOverride = true,
                targetChildId = cleanId,
                commandType = "RESET_SCREEN_TIME",
                sourceRole = "GUARDIAN"
            )
        }
    }

    fun setDailyAllowanceAsCommand(context: Context, childId: String, allowanceSeconds: Int, customCommandId: String = "") {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return

        val now = System.currentTimeMillis()
        val today = getCurrentScreenTimeDate()
        val cmdId = customCommandId.ifBlank { "SET_LIMIT_${cleanId}_${allowanceSeconds}_$now" }
        Log.i(TAG, "SCREEN_TIME_COMMAND_SENT childCode=$cleanId targetChildId=$cleanId commandId=$cmdId command=SET_LIMIT allowance=${allowanceSeconds}s timestamp=$now")
        Log.i("HomeSyncLatency", "COMMAND_PREPARED commandId=$cmdId commandType=SET_DAILY_LIMIT childCode=$cleanId timestamp=$now")

        val currentUsed = getUsedSeconds(context, cleanId)
        val newRemaining = (allowanceSeconds - currentUsed).coerceAtLeast(0)
        val shouldLock = (newRemaining <= 0)

        val prefs = getPrefs(context)
        prefs.edit()
            .putString(getDateKey(cleanId), today)
            .putInt(getTotalKey(cleanId), allowanceSeconds)
            .putInt(getDailyTotalKey(cleanId, today), allowanceSeconds)
            .putInt(getRemainingKey(cleanId), newRemaining)
            .putInt(getDailyRemainingKey(cleanId, today), newRemaining)
            .putBoolean(getLockedKey(cleanId), shouldLock)
            .putBoolean(getRemoteLockedKey(cleanId), shouldLock)
            .apply()

        val rtdb = FirebaseRealtimeSyncManager.getRtdb()
        if (rtdb != null) {
            try { rtdb.goOnline() } catch (_: Exception) {}
            val commandPayload = mapOf<String, Any>(
                "childCode" to cleanId,
                "targetChildId" to cleanId,
                "commandType" to "SET_DAILY_LIMIT",
                "date" to today,
                "remoteAllowance" to allowanceSeconds,
                "totalAllowance" to allowanceSeconds,
                "remainingSeconds" to newRemaining,
                "remoteLock" to shouldLock,
                "isLocked" to shouldLock,
                "commandId" to cmdId,
                "commandTimestamp" to now,
                "updatedAt" to now,
                "sourceRole" to "GUARDIAN"
            )
            rtdb.getReference("hs_screentime").child(cleanId).updateChildren(commandPayload)
                .addOnSuccessListener {
                    val finishNow = System.currentTimeMillis()
                    Log.i(TAG, "SCREEN_TIME_SET_LIMIT_SUCCESS childCode=$cleanId allowance=$allowanceSeconds")
                    Log.i("HomeSyncLatency", "COMMAND_WRITTEN commandId=$cmdId commandType=SET_DAILY_LIMIT childCode=$cleanId latencyMs=${finishNow - now} timestamp=$finishNow")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "SCREEN_TIME_RTDB_ERROR code=WRITE_FAILED message=${e.message} path=hs_screentime/$cleanId", e)
                }
        }

        saveLastCommand(context, cleanId, cmdId, now, shouldLock)

        CoroutineScope(Dispatchers.IO).launch {
            FirebaseSyncManager.syncScreenTimeToCloud(
                childCode = cleanId,
                remainingSeconds = newRemaining,
                isLocked = shouldLock,
                totalAllowance = allowanceSeconds,
                usedSeconds = currentUsed,
                date = today,
                commandId = cmdId,
                commandTimestamp = now,
                targetChildId = cleanId,
                commandType = "SET_DAILY_LIMIT",
                sourceRole = "GUARDIAN"
            )
        }
    }

    fun applyRemoteUpdate(
        context: Context,
        childId: String,
        remainingSeconds: Int,
        isLocked: Boolean,
        totalAllowance: Int,
        usedSeconds: Int = (totalAllowance - remainingSeconds).coerceAtLeast(0),
        updateResetVersion: Long = 0L
    ) {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return
        val today = getCurrentScreenTimeDate()
        val prefs = getPrefs(context)
        val currentResetVersion = getDailyResetVersion(context, cleanId, today)
        if (updateResetVersion > 0L && updateResetVersion < currentResetVersion) {
            Log.i(TAG, "SCREEN_TIME_REMOTE_UPDATE_IGNORED_STALE_RESET child=$cleanId updateVersion=$updateResetVersion currentVersion=$currentResetVersion")
            return
        }
        val rem = if (isLocked) 0 else remainingSeconds
        prefs.edit()
            .putString(getDateKey(cleanId), today)
            .putInt(getTotalKey(cleanId), totalAllowance)
            .putInt(getDailyTotalKey(cleanId, today), totalAllowance)
            .putInt(getRemainingKey(cleanId), rem)
            .putInt(getDailyRemainingKey(cleanId, today), rem)
            .putInt(getUsedKey(cleanId), usedSeconds)
            .putInt(getDailyUsedKey(cleanId, today), usedSeconds)
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
        val realUsed = getRealDeviceUsageTodaySeconds(context, cleanId)
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
        val today = getCurrentScreenTimeDate()

        prefs.edit()
            .putString(getDateKey(cleanId), today)
            .putInt(getTotalKey(cleanId), newAllowance)
            .putInt(getDailyTotalKey(cleanId, today), newAllowance)
            .putInt(getRemainingKey(cleanId), newTotal)
            .putInt(getDailyRemainingKey(cleanId, today), newTotal)
            .putBoolean(getLockedKey(cleanId), false)
            .apply()
    }

    /**
     * Calculates used screen time seconds for a child.
     * Checks today's date-specific record first.
     */
    fun getUsedSeconds(context: Context, childId: String): Int {
        val cleanId = cleanChildId(childId)
        if (cleanId.isBlank()) return 0
        val today = getCurrentScreenTimeDate()
        val prefs = getPrefs(context)
        val storedDaily = prefs.getInt(getDailyUsedKey(cleanId, today), -1)
        if (storedDaily >= 0) return storedDaily

        val lastActiveDate = prefs.getString(getDateKey(cleanId), "")
        if (lastActiveDate == today) {
            val storedLegacy = prefs.getInt(getUsedKey(cleanId), -1)
            if (storedLegacy >= 0) return storedLegacy
        }

        if (lastActiveDate?.isNotBlank() == true && lastActiveDate != today) {
            return 0
        }

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
