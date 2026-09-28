package com.homesync.app.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class ParentalControlRules(
    val childId: String,
    val curfewEnabled: Boolean = true,
    val curfewStartTime: String = "10:00 PM",
    val curfewEndTime: String = "07:00 AM",
    val schoolModeEnabled: Boolean = true,
    val blockSocialMedia: Boolean = true,
    val blockGames: Boolean = false,
    val blockBrowser: Boolean = true,
    val webFilterLevel: String = "Strict Kid-Safe",
    val maxDailyAllowanceHours: Float = 4.0f,
    val maxDailyAllowanceMinutes: Int = 240
)

object ParentalControlManager {
    private const val TAG = "ParentalControlManager"
    private const val PREFS_NAME = "homesync_parental_control_prefs"
    private const val RTDB_PATH = "hs_parental_controls"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun cleanId(id: String) = id.trim().uppercase().ifBlank { "DEFAULT_CHILD" }

    fun getRules(context: Context, childId: String): ParentalControlRules {
        val prefs = getPrefs(context)
        val cid = cleanId(childId)
        val minutes = prefs.getInt("daily_allowance_minutes_$cid", -1)
        val hours = if (minutes > 0) {
            minutes / 60f
        } else {
            val hFromFloat = prefs.getFloat("daily_allowance_hours_$cid", -1f)
            if (hFromFloat > 0f) hFromFloat else prefs.getInt("daily_allowance_$cid", 4).toFloat()
        }
        val effMinutes = if (minutes > 0) minutes else (hours * 60).toInt().coerceAtLeast(30)
        return ParentalControlRules(
            childId = cid,
            curfewEnabled = prefs.getBoolean("curfew_enabled_$cid", true),
            curfewStartTime = prefs.getString("curfew_start_$cid", "10:00 PM") ?: "10:00 PM",
            curfewEndTime = prefs.getString("curfew_end_$cid", "07:00 AM") ?: "07:00 AM",
            schoolModeEnabled = prefs.getBoolean("school_mode_$cid", true),
            blockSocialMedia = prefs.getBoolean("block_social_$cid", true),
            blockGames = prefs.getBoolean("block_games_$cid", false),
            blockBrowser = prefs.getBoolean("block_browser_$cid", true),
            webFilterLevel = prefs.getString("web_filter_$cid", "Strict Kid-Safe") ?: "Strict Kid-Safe",
            maxDailyAllowanceHours = hours,
            maxDailyAllowanceMinutes = effMinutes
        )
    }

    fun saveRules(context: Context, rules: ParentalControlRules) {
        val prefs = getPrefs(context)
        val cid = cleanId(rules.childId)
        prefs.edit()
            .putBoolean("curfew_enabled_$cid", rules.curfewEnabled)
            .putString("curfew_start_$cid", rules.curfewStartTime)
            .putString("curfew_end_$cid", rules.curfewEndTime)
            .putBoolean("school_mode_$cid", rules.schoolModeEnabled)
            .putBoolean("block_social_$cid", rules.blockSocialMedia)
            .putBoolean("block_games_$cid", rules.blockGames)
            .putBoolean("block_browser_$cid", rules.blockBrowser)
            .putString("web_filter_$cid", rules.webFilterLevel)
            .putInt("daily_allowance_$cid", rules.maxDailyAllowanceHours.toInt())
            .putFloat("daily_allowance_hours_$cid", rules.maxDailyAllowanceHours)
            .putInt("daily_allowance_minutes_$cid", rules.maxDailyAllowanceMinutes)
            .apply()
    }

    /**
     * Syncs parental control and curfew rules to Firebase Realtime Database.
     */
    fun syncRulesToCloud(context: Context, rules: ParentalControlRules, familyId: String = "") {
        saveRules(context, rules)
        val cid = cleanId(rules.childId)
        val rtdb = FirebaseRealtimeSyncManager.getRtdb() ?: return

        try {
            val payload = mapOf(
                "childId" to cid,
                "curfewEnabled" to rules.curfewEnabled,
                "curfewStartTime" to rules.curfewStartTime,
                "curfewEndTime" to rules.curfewEndTime,
                "schoolModeEnabled" to rules.schoolModeEnabled,
                "blockSocialMedia" to rules.blockSocialMedia,
                "blockGames" to rules.blockGames,
                "blockBrowser" to rules.blockBrowser,
                "webFilterLevel" to rules.webFilterLevel,
                "maxDailyAllowanceHours" to rules.maxDailyAllowanceHours,
                "maxDailyAllowanceMinutes" to rules.maxDailyAllowanceMinutes,
                "familyId" to familyId,
                "updatedAt" to System.currentTimeMillis()
            )
            rtdb.getReference(RTDB_PATH).child(cid).setValue(payload)
                .addOnSuccessListener {
                    Log.i(TAG, "PARENTAL_RULES_SYNC_SUCCESS childId=$cid curfew=${rules.curfewEnabled} limitMinutes=${rules.maxDailyAllowanceMinutes}")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "PARENTAL_RULES_SYNC_FAILED childId=$cid error=${e.message}", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing parental rules to RTDB", e)
        }
    }

    /**
     * Listens in real-time for updated parental controls and curfew settings from RTDB.
     */
    fun listenRulesFromCloud(
        context: Context,
        childId: String,
        onRulesUpdated: (ParentalControlRules) -> Unit
    ): (() -> Unit)? {
        val cid = cleanId(childId)
        val rtdb = FirebaseRealtimeSyncManager.getRtdb() ?: return null

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                val curfewEnabled = snapshot.child("curfewEnabled").getValue(Boolean::class.java) ?: true
                val curfewStart = snapshot.child("curfewStartTime").getValue(String::class.java) ?: "10:00 PM"
                val curfewEnd = snapshot.child("curfewEndTime").getValue(String::class.java) ?: "07:00 AM"
                val schoolMode = snapshot.child("schoolModeEnabled").getValue(Boolean::class.java) ?: true
                val blockSocial = snapshot.child("blockSocialMedia").getValue(Boolean::class.java) ?: true
                val blockGames = snapshot.child("blockGames").getValue(Boolean::class.java) ?: false
                val blockBrowser = snapshot.child("blockBrowser").getValue(Boolean::class.java) ?: true
                val webFilter = snapshot.child("webFilterLevel").getValue(String::class.java) ?: "Strict Kid-Safe"
                val allowanceHours = (snapshot.child("maxDailyAllowanceHours").value as? Number)?.toFloat()
                    ?: (snapshot.child("maxDailyAllowanceMinutes").value as? Number)?.let { it.toFloat() / 60f }
                    ?: 4.0f
                val allowanceMinutes = (snapshot.child("maxDailyAllowanceMinutes").value as? Number)?.toInt()
                    ?: (allowanceHours * 60).toInt().coerceAtLeast(30)

                val rules = ParentalControlRules(
                    childId = cid,
                    curfewEnabled = curfewEnabled,
                    curfewStartTime = curfewStart,
                    curfewEndTime = curfewEnd,
                    schoolModeEnabled = schoolMode,
                    blockSocialMedia = blockSocial,
                    blockGames = blockGames,
                    blockBrowser = blockBrowser,
                    webFilterLevel = webFilter,
                    maxDailyAllowanceHours = allowanceHours,
                    maxDailyAllowanceMinutes = allowanceMinutes
                )
                saveRules(context, rules)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onRulesUpdated(rules)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Rules listener cancelled: ${error.message}")
            }
        }

        val ref = rtdb.getReference(RTDB_PATH).child(cid)
        ref.addValueEventListener(listener)
        return {
            try {
                ref.removeEventListener(listener)
            } catch (_: Exception) {}
        }
    }

    fun setCurfewOverride(context: Context, childId: String, overridden: Boolean) {
        val cid = cleanId(childId)
        getPrefs(context).edit().putBoolean("curfew_override_$cid", overridden).apply()
        Log.i(TAG, "CURFEW_OVERRIDE_SET childId=$cid overridden=$overridden")
    }

    fun isCurfewOverridden(context: Context, childId: String): Boolean {
        val cid = cleanId(childId)
        return getPrefs(context).getBoolean("curfew_override_$cid", false)
    }

    /**
     * Checks if curfew is currently active according to local clock.
     */
    fun isCurfewActiveNow(rules: ParentalControlRules, context: Context? = null): Boolean {
        if (!rules.curfewEnabled) return false
        if (context != null && isCurfewOverridden(context, rules.childId)) {
            Log.i(TAG, "CURFEW_SUPPRESSED childId=${rules.childId} reason=GUARDIAN_OVERRIDE")
            return false
        }
        try {
            val format = SimpleDateFormat("hh:mm a", Locale.US)
            val now = Calendar.getInstance()
            val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

            val startParsed = format.parse(rules.curfewStartTime) ?: return false
            val endParsed = format.parse(rules.curfewEndTime) ?: return false

            val startCal = Calendar.getInstance().apply { time = startParsed }
            val endCal = Calendar.getInstance().apply { time = endParsed }

            val startMinutes = startCal.get(Calendar.HOUR_OF_DAY) * 60 + startCal.get(Calendar.MINUTE)
            val endMinutes = endCal.get(Calendar.HOUR_OF_DAY) * 60 + endCal.get(Calendar.MINUTE)

            return if (startMinutes > endMinutes) {
                // Overnight curfew (e.g. 10:00 PM to 07:00 AM)
                currentMinutes >= startMinutes || currentMinutes < endMinutes
            } else {
                currentMinutes in startMinutes until endMinutes
            }
        } catch (_: Exception) {
            return false
        }
    }
}
