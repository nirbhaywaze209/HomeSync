package com.homesync.app.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.firestore.SetOptions
import java.util.Locale

object QuickActionCooldownManager {
    private const val TAG = "QuickActionCooldown"
    private const val PREFS_NAME = "homesync_quick_actions_cooldown_prefs"
    const val COOLDOWN_DURATION_MS = 2 * 60 * 60 * 1000L // 2 Hours in milliseconds

    const val ACTION_SAFE_CHECKIN = "SAFE_CHECKIN"
    const val ACTION_SHARE_LOCATION = "SHARE_LOCATION"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun buildKey(childUid: String, actionKey: String): String {
        val cleanUid = childUid.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_")
        val cleanAction = actionKey.trim().uppercase()
        return "cd_${cleanUid}_${cleanAction}"
    }

    fun getRemainingCooldownMs(context: Context, childUid: String, actionKey: String): Long {
        if (childUid.isBlank()) return 0L
        val prefs = getPrefs(context)
        val key = buildKey(childUid, actionKey)
        val lastUsed = prefs.getLong(key, 0L)
        if (lastUsed <= 0L) return 0L

        val now = System.currentTimeMillis()
        val elapsed = now - lastUsed
        val remaining = COOLDOWN_DURATION_MS - elapsed
        return if (remaining > 0L) remaining else 0L
    }

    private val inMemoryLastUsed = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun canPerformAction(childUid: String, actionKey: String): Boolean {
        val lastUsed = inMemoryLastUsed[buildKey(childUid, actionKey)] ?: 0L
        if (lastUsed <= 0L) return true
        val elapsed = System.currentTimeMillis() - lastUsed
        return elapsed >= COOLDOWN_DURATION_MS
    }

    fun canPerformAction(context: Context, childUid: String, actionKey: String): Boolean {
        return !isActionInCooldown(context, childUid, actionKey)
    }

    fun getRemainingCooldownMinutes(childUid: String, actionKey: String): Long {
        val lastUsed = inMemoryLastUsed[buildKey(childUid, actionKey)] ?: return 0L
        val elapsed = System.currentTimeMillis() - lastUsed
        val remaining = COOLDOWN_DURATION_MS - elapsed
        return if (remaining > 0L) ((remaining + 59999L) / 60000L).coerceAtLeast(1L) else 0L
    }

    fun getRemainingCooldownMinutes(context: Context, childUid: String, actionKey: String): Long {
        val remaining = getRemainingCooldownMs(context, childUid, actionKey)
        return if (remaining > 0L) ((remaining + 59999L) / 60000L).coerceAtLeast(1L) else 0L
    }

    fun recordActionNow(context: Context, familyId: String, childUid: String, actionKey: String) {
        inMemoryLastUsed[buildKey(childUid, actionKey)] = System.currentTimeMillis()
        recordActionUsage(context, familyId, childUid, actionKey)
    }

    fun isActionInCooldown(context: Context, childUid: String, actionKey: String): Boolean {
        val inMem = inMemoryLastUsed[buildKey(childUid, actionKey)] ?: 0L
        if (inMem > 0L && (System.currentTimeMillis() - inMem) < COOLDOWN_DURATION_MS) return true
        return getRemainingCooldownMs(context, childUid, actionKey) > 0L
    }

    fun formatRemainingCooldown(remainingMs: Long): String {
        if (remainingMs <= 0L) return ""
        val totalSeconds = (remainingMs / 1000).coerceAtLeast(1)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }

    fun recordActionUsage(context: Context, familyId: String, childUid: String, actionKey: String) {
        val cleanChildUid = childUid.trim()
        val cleanFamilyId = familyId.trim().uppercase()
        val cleanAction = actionKey.trim().uppercase()

        if (cleanChildUid.isBlank() || cleanAction.isBlank()) return

        val now = System.currentTimeMillis()
        val key = buildKey(cleanChildUid, cleanAction)

        // 1. Local persist
        getPrefs(context).edit().putLong(key, now).apply()
        Log.i(TAG, "COOLDOWN_RECORDED childUid=$cleanChildUid action=$cleanAction timestamp=$now")

        // 2. Realtime Database persist
        val rtdb = FirebaseRealtimeSyncManager.getRtdb()
        rtdb?.getReference("hs_cooldowns")?.child("${cleanChildUid}_${cleanAction}")
            ?.setValue(
                mapOf(
                    "childUid" to cleanChildUid,
                    "action" to cleanAction,
                    "familyId" to cleanFamilyId,
                    "lastUsedTimestamp" to now
                )
            )

        // 3. Firestore persist if familyId exists
        if (cleanFamilyId.isNotBlank()) {
            val db = FirebaseSyncManager.getDb()
            db?.collection("hs_families")?.document(cleanFamilyId)
                ?.collection("cooldowns")?.document("${cleanChildUid}_${cleanAction}")
                ?.set(
                    mapOf(
                        "childUid" to cleanChildUid,
                        "action" to cleanAction,
                        "lastUsedTimestamp" to now,
                        "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
        }
    }

    fun syncCooldownFromCloud(
        context: Context,
        childUid: String,
        actionKey: String,
        onSynced: (Long) -> Unit = {}
    ) {
        val cleanChildUid = childUid.trim()
        val cleanAction = actionKey.trim().uppercase()
        if (cleanChildUid.isBlank() || cleanAction.isBlank()) return

        val rtdb = FirebaseRealtimeSyncManager.getRtdb()
        rtdb?.getReference("hs_cooldowns")?.child("${cleanChildUid}_${cleanAction}")
            ?.get()?.addOnSuccessListener { snapshot ->
                val cloudTimestamp = snapshot.child("lastUsedTimestamp").getValue(Long::class.java) ?: 0L
                if (cloudTimestamp > 0L) {
                    val key = buildKey(cleanChildUid, cleanAction)
                    val localTimestamp = getPrefs(context).getLong(key, 0L)
                    if (cloudTimestamp > localTimestamp) {
                        getPrefs(context).edit().putLong(key, cloudTimestamp).apply()
                    }
                    val rem = getRemainingCooldownMs(context, cleanChildUid, cleanAction)
                    onSynced(rem)
                }
            }
    }
}
