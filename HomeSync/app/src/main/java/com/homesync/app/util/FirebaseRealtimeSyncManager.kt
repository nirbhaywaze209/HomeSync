package com.homesync.app.util

import android.content.Context
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

object FirebaseRealtimeSyncManager {
    private const val TAG = "FirebaseRealtimeSync"

    private const val RTDB_URL = "https://homesync-app-4cee2-default-rtdb.firebaseio.com"

    private val rtdbInstance: FirebaseDatabase? by lazy {
        try {
            val app = com.google.firebase.FirebaseApp.getInstance()
            val db = try {
                FirebaseDatabase.getInstance(app, RTDB_URL)
            } catch (_: Exception) {
                FirebaseDatabase.getInstance(RTDB_URL)
            }
            try { db.goOnline() } catch (_: Exception) {}
            try {
                db.getReference(".info/connected").addValueEventListener(object : com.google.firebase.database.ValueEventListener {
                    override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                        val connected = snapshot.getValue(Boolean::class.java) ?: false
                        val now = System.currentTimeMillis()
                        android.util.Log.i("HomeSyncLatency", "RTDB_CONNECTION_STATE commandId=NONE commandType=NONE childCode=ALL path=.info/connected connected=$connected timestamp=$now")
                    }
                    override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                        val now = System.currentTimeMillis()
                        android.util.Log.w("HomeSyncLatency", "RTDB_CONNECTION_STATE commandId=NONE commandType=NONE childCode=ALL path=.info/connected error=${error.message} timestamp=$now")
                    }
                })
            } catch (_: Exception) {}
            db
        } catch (e: Exception) {
            Log.e(TAG, "Error obtaining Firebase Realtime Database instance", e)
            null
        }
    }

    fun getRtdb(): FirebaseDatabase? = rtdbInstance

    fun getLongSafe(snapshot: DataSnapshot, key: String, default: Long = 0L): Long {
        val child = snapshot.child(key)
        if (!child.exists()) return default
        return when (val v = child.value) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: default
            else -> default
        }
    }

    /**
     * Dual-Sync Guardian profile to Realtime Database & Firestore with HD Photo URL support.
     */
    fun syncGuardianProfile(
        context: Context,
        guardianName: String,
        childCode: String,
        photoPayload: String = "",
        email: String = ""
    ) {
        val cleanCode = childCode.trim().uppercase().ifBlank { "DEFAULT_CHILD" }
        val cleanName = guardianName.trim().ifBlank { "Guardian" }
        if (cleanName.equals("User", ignoreCase = true) || cleanName.equals("Child", ignoreCase = true)) return

        // 1. Sync to Firestore
        FirebaseSyncManager.syncGuardianProfileToCloud(context, cleanName, cleanCode, photoPayload, email)

        // 2. Sync to Realtime Database
        val rtdb = getRtdb() ?: return
        try {
            val payload = mapOf(
                "guardianName" to cleanName,
                "childCode" to cleanCode,
                "email" to email,
                "avatarBase64" to photoPayload,
                "isConnected" to true,
                "updatedAt" to System.currentTimeMillis()
            )

            rtdb.getReference("hs_guardian_profiles").child(cleanCode).setValue(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing Guardian profile to Realtime Database", e)
        }
    }

    /**
     * Listens for Guardian profile updates for a specific childCode from Realtime Database.
     * Returns a cancellation function to cleanly unregister the listener on dispose.
     */
    fun listenGuardianProfile(
        context: Context,
        childCode: String,
        onProfileUpdated: (GuardianProfileCloudData) -> Unit
    ): (() -> Unit)? {
        val cleanCode = childCode.trim().uppercase().ifBlank { "DEFAULT_CHILD" }
        val rtdb = getRtdb() ?: return null

        return try {
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) return
                    val guardianName = snapshot.child("guardianName").getValue(String::class.java) ?: ""
                    val email = snapshot.child("email").getValue(String::class.java) ?: ""
                    val avatarBase64 = snapshot.child("avatarBase64").getValue(String::class.java) ?: ""
                    val isConnected = snapshot.child("isConnected").getValue(Boolean::class.java) ?: true
                    val updatedAt = snapshot.child("updatedAt").getValue(Long::class.java) ?: System.currentTimeMillis()

                    val isSarah = AuthManager.isSarahName(guardianName)
                    if (isSarah) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onProfileUpdated(GuardianProfileCloudData(guardianName = "", childCode = cleanCode, isConnected = false))
                        }
                        return
                    }
                    val isInvalid = guardianName.isBlank() || guardianName.equals("User", ignoreCase = true) ||
                            guardianName.equals("Child", ignoreCase = true)

                    if (isInvalid) return

                    val profile = GuardianProfileCloudData(
                        guardianName = guardianName,
                        childCode = snapshot.key ?: cleanCode,
                        email = email,
                        avatarBase64 = avatarBase64,
                        isConnected = isConnected,
                        updatedAt = updatedAt
                    )
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onProfileUpdated(profile)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Realtime Database Guardian listener cancelled for $cleanCode", error.toException())
                }
            }

            val ref = rtdb.getReference("hs_guardian_profiles").child(cleanCode)
            ref.addValueEventListener(listener)
            return {
                try {
                    ref.removeEventListener(listener)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error listening to Realtime Database Guardian profiles for $cleanCode", e)
            null
        }
    }

    /**
     * Dual-Sync Child profile to Realtime Database & Firestore with HD Photo URL support.
     */
    fun syncChildProfile(context: Context, profile: ChildProfileCloudData) {
        val cleanCode = profile.childCode.trim().uppercase().ifBlank { "DEFAULT_CHILD" }

        // 1. Sync to Firestore
        FirebaseSyncManager.syncChildProfileToCloud(context, profile)

        // 2. Sync to Realtime Database
        val rtdb = getRtdb() ?: return
        try {
            val payload = mapOf(
                "childCode" to cleanCode,
                "name" to profile.name,
                "avatarBase64" to profile.avatarBase64,
                "rewardStars" to profile.rewardStars,
                "levelLabel" to profile.levelLabel,
                "xpProgress" to profile.xpProgress,
                "streakDays" to profile.streakDays,
                "isConnected" to true,
                "lastActiveTime" to System.currentTimeMillis()
            )

            rtdb.getReference("hs_child_profiles").child(cleanCode).setValue(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing Child profile to Realtime Database for $cleanCode", e)
        }
    }

    /**
     * Listens for Child profile updates for a specific childCode from Realtime Database.
     * Returns a cancellation function to cleanly unregister the listener on dispose.
     */
    fun listenChildProfile(
        context: Context,
        childCode: String,
        onProfileUpdated: (ChildProfileCloudData) -> Unit
    ): (() -> Unit)? {
        val cleanCode = childCode.trim().uppercase().ifBlank { "DEFAULT_CHILD" }
        val rtdb = getRtdb() ?: return null

        return try {
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) return
                    val docCode = snapshot.child("childCode").getValue(String::class.java) ?: snapshot.key ?: cleanCode
                    val name = snapshot.child("name").getValue(String::class.java) ?: "Child"
                    val avatarBase64 = snapshot.child("avatarBase64").getValue(String::class.java) ?: ""
                    val rewardStars = (snapshot.child("rewardStars").getValue(Long::class.java) ?: 120L).toInt()
                    val levelLabel = snapshot.child("levelLabel").getValue(String::class.java) ?: "Lvl 3 Hero"
                    val xpProgress = (snapshot.child("xpProgress").getValue(Long::class.java) ?: 450L).toInt()
                    val streakDays = (snapshot.child("streakDays").getValue(Long::class.java) ?: 5L).toInt()
                    val isConnected = snapshot.child("isConnected").getValue(Boolean::class.java) ?: true
                    val lastActiveTime = snapshot.child("lastActiveTime").getValue(Long::class.java) ?: System.currentTimeMillis()

                    val profileData = ChildProfileCloudData(
                        childCode = docCode,
                        name = name,
                        avatarBase64 = avatarBase64,
                        rewardStars = rewardStars,
                        levelLabel = levelLabel,
                        xpProgress = xpProgress,
                        streakDays = streakDays,
                        isConnected = isConnected,
                        lastActiveTime = lastActiveTime
                    )

                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onProfileUpdated(profileData)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Realtime Database Child listener cancelled for $cleanCode", error.toException())
                }
            }

            val ref = rtdb.getReference("hs_child_profiles").child(cleanCode)
            ref.addValueEventListener(listener)
            return {
                try {
                    ref.removeEventListener(listener)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up Realtime Database Child profiles listener for $cleanCode", e)
            null
        }
    }

    /**
     * Listens for all children registered/online across devices in Realtime Database.
     * Returns a cancellation function to cleanly unregister the listener on dispose.
     */
    fun listenAllChildren(
        context: Context,
        onChildDiscovered: (ChildProfileCloudData) -> Unit
    ): (() -> Unit)? {
        val rtdb = getRtdb() ?: return null

        return try {
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) return
                    for (childSnap in snapshot.children) {
                        val docCode = childSnap.child("childCode").getValue(String::class.java)
                            ?: childSnap.key
                            ?: continue
                        val cleanCode = docCode.trim().uppercase()
                        if (cleanCode.isBlank() || cleanCode == "DEFAULT_CHILD") continue

                        val rawName = childSnap.child("name").getValue(String::class.java) ?: ""
                        val localName = ChildIdManager.getChildName(context, cleanCode).ifBlank { rawName }
                        val name = localName.ifBlank { rawName.ifBlank { "Child ${cleanCode.takeLast(4)}" } }
                        if (name.equals("Sarah", ignoreCase = true)) continue

                        val avatarBase64 = childSnap.child("avatarBase64").getValue(String::class.java) ?: ""
                        val rewardStars = (childSnap.child("rewardStars").getValue(Long::class.java) ?: 0L).toInt()
                        val levelLabel = childSnap.child("levelLabel").getValue(String::class.java) ?: "Lvl 1 Hero"
                        val xpProgress = (childSnap.child("xpProgress").getValue(Long::class.java) ?: 0L).toInt()
                        val streakDays = (childSnap.child("streakDays").getValue(Long::class.java) ?: 0L).toInt()
                        val isConnected = childSnap.child("isConnected").getValue(Boolean::class.java) ?: true
                        val lastActiveTime = childSnap.child("lastActiveTime").getValue(Long::class.java) ?: System.currentTimeMillis()

                        val profile = ChildProfileCloudData(
                            childCode = cleanCode,
                            name = name,
                            avatarBase64 = avatarBase64,
                            rewardStars = rewardStars,
                            levelLabel = levelLabel,
                            xpProgress = xpProgress,
                            streakDays = streakDays,
                            isConnected = isConnected,
                            lastActiveTime = lastActiveTime
                        )

                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onChildDiscovered(profile)
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "listenAllChildren cancelled", error.toException())
                }
            }

            val ref = rtdb.getReference("hs_child_profiles")
            ref.addValueEventListener(listener)
            return {
                try {
                    ref.removeEventListener(listener)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up Realtime Database listenAllChildren", e)
            null
        }
    }

    /**
     * Dual-Syncs screen time remaining seconds and remote lock state to RTDB & Firestore.
     */
    fun syncScreenTime(
        childCode: String,
        remainingSeconds: Int,
        isLocked: Boolean,
        totalAllowance: Int,
        usedSeconds: Int = (totalAllowance - remainingSeconds).coerceAtLeast(0),
        date: String = ScreenTimeManager.getCurrentScreenTimeDate()
    ) {
        val cleanCode = childCode.trim().uppercase()
        if (cleanCode.isBlank()) return

        // 1. Sync to Firestore
        FirebaseSyncManager.syncScreenTimeToCloud(cleanCode, remainingSeconds, isLocked, totalAllowance, usedSeconds, date)

        // 2. Sync to Realtime Database using updateChildren ONLY (never destructive setValue)
        val rtdb = getRtdb() ?: return
        try {
            val payload = mapOf(
                "childCode" to cleanCode,
                "targetChildId" to cleanCode,
                "date" to date,
                "remainingSeconds" to remainingSeconds,
                "usedSeconds" to usedSeconds,
                "telemetryTimestamp" to System.currentTimeMillis(),
                "updatedAt" to System.currentTimeMillis(),
                "sourceRole" to "CHILD"
            )
            val rtdbPath = "hs_screentime/$cleanCode"
            Log.i("HomeSyncScreenTime", "SCREEN_TIME_RTDB_PATH path=$rtdbPath")
            rtdb.getReference("hs_screentime").child(cleanCode).updateChildren(payload)
                .addOnSuccessListener {
                    Log.i("HomeSyncScreenTime", "SCREEN_TIME_SYNC childCode=$cleanCode used=$usedSeconds remaining=$remainingSeconds allowance=$totalAllowance locked=$isLocked")
                }
                .addOnFailureListener { e ->
                    Log.e("HomeSyncScreenTime", "SCREEN_TIME_RTDB_ERROR code=WRITE_FAILED message=${e.message} path=$rtdbPath", e)
                }
        } catch (e: Exception) {
            Log.e("HomeSyncScreenTime", "SCREEN_TIME_RTDB_ERROR code=EXCEPTION message=${e.message} path=hs_screentime/$cleanCode", e)
            Log.e(TAG, "Error syncing screen time to RTDB", e)
        }
    }

    /**
     * Resets screen time across cloud backends for the current date.
     */
    fun syncScreenTimeReset(childCode: String, boundaryTimestamp: Long) {
        val cleanCode = childCode.trim().uppercase()
        if (cleanCode.isBlank()) return
        val today = ScreenTimeManager.getCurrentScreenTimeDate()

        // 1. Dual-Sync to Firestore
        FirebaseSyncManager.syncScreenTimeToCloud(cleanCode, 21600, false, 21600, 0, today, boundaryTimestamp)

        // 2. Sync to Realtime Database
        val rtdb = getRtdb() ?: return
        try {
            val payload = mapOf(
                "childCode" to cleanCode,
                "targetChildId" to cleanCode,
                "commandType" to "RESET_SCREEN_TIME",
                "date" to today,
                "remoteLock" to false,
                "isLocked" to false,
                "remoteAllowance" to 21600,
                "totalAllowance" to 21600,
                "remainingSeconds" to 21600,
                "usedSeconds" to 0,
                "resetVersion" to boundaryTimestamp,
                "commandId" to "RESET_DAILY_${cleanCode}_$boundaryTimestamp",
                "commandTimestamp" to boundaryTimestamp,
                "telemetryTimestamp" to System.currentTimeMillis(),
                "updatedAt" to System.currentTimeMillis()
            )
            rtdb.getReference("hs_screentime").child(cleanCode).updateChildren(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing screen time reset to RTDB", e)
        }
    }

    /**
     * Subscribes to real-time screen time, remote lock, and command updates with full command details.
     */
    data class ScreenTimeStateSnapshot(
        val remainingSeconds: Int,
        val isLocked: Boolean,
        val totalAllowance: Int,
        val usedSeconds: Int,
        val commandId: String,
        val commandTimestamp: Long,
        val curfewOverride: Boolean,
        val targetChildId: String,
        val commandType: String,
        val date: String,
        val resetVersion: Long
    )

    private val activeCommandListeners = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<(Int, Boolean, Int, Int, String, Long, Boolean, String, String, String, Long) -> Unit>>()
    private val activeRtdbRefs = java.util.concurrent.ConcurrentHashMap<String, DatabaseReference>()
    private val activeRtdbListeners = java.util.concurrent.ConcurrentHashMap<String, ValueEventListener>()
    private val lastKnownScreenTimeState = java.util.concurrent.ConcurrentHashMap<String, ScreenTimeStateSnapshot>()

    /**
     * Subscribes to real-time screen time, remote lock, and command updates with full command details including resetVersion.
     * Uses an authoritative persistent WebSocket listener that never unregisters across screen navigation.
     */
    fun listenScreenTimeWithCommandDetails(
        childCode: String,
        onUpdate: (
            remainingSeconds: Int,
            isLocked: Boolean,
            totalAllowance: Int,
            usedSeconds: Int,
            commandId: String,
            commandTimestamp: Long,
            curfewOverride: Boolean,
            targetChildId: String,
            commandType: String,
            date: String,
            resetVersion: Long
        ) -> Unit
    ): (() -> Unit)? {
        val cleanCode = childCode.trim().uppercase()
        if (cleanCode.isBlank()) return null

        val rtdb = getRtdb() ?: return null
        val rtdbPath = "hs_screentime/$cleanCode"

        val listenersList = activeCommandListeners.getOrPut(cleanCode) { java.util.concurrent.CopyOnWriteArrayList() }
        listenersList.add(onUpdate)

        val regTs = System.currentTimeMillis()
        android.util.Log.i("HomeSyncLatency", "LISTENER_REGISTERED commandId=NONE commandType=NONE childCode=$cleanCode path=$rtdbPath timestamp=$regTs")

        // Deliver cached snapshot immediately if available to eliminate screen transition delay
        lastKnownScreenTimeState[cleanCode]?.let { cached ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onUpdate(
                    cached.remainingSeconds,
                    cached.isLocked,
                    cached.totalAllowance,
                    cached.usedSeconds,
                    cached.commandId,
                    cached.commandTimestamp,
                    cached.curfewOverride,
                    cached.targetChildId,
                    cached.commandType,
                    cached.date,
                    cached.resetVersion
                )
            }
        }

        // Maintain persistent underlying RTDB WebSocket listener
        if (!activeRtdbListeners.containsKey(cleanCode)) {
            val rtdbRef = rtdb.getReference("hs_screentime").child(cleanCode)
            try { rtdbRef.keepSynced(true) } catch (_: Exception) {}
            try { rtdb.goOnline() } catch (_: Exception) {}
            activeRtdbRefs[cleanCode] = rtdbRef

            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        if (!snapshot.exists()) return

                        val remoteLock = snapshot.child("remoteLock").getValue(Boolean::class.java)
                            ?: snapshot.child("isLocked").getValue(Boolean::class.java)
                            ?: false
                        val isLockedVal = snapshot.child("isLocked").getValue(Boolean::class.java) ?: false
                        val cmdId = snapshot.child("commandId").getValue(String::class.java) ?: "NONE"
                        val cmdTimestamp = snapshot.child("commandTimestamp").getValue(Long::class.java) ?: 0L
                        val commandType = snapshot.child("commandType").getValue(String::class.java) ?: "NONE"
                        val targetChildId = snapshot.child("targetChildId").getValue(String::class.java) ?: cleanCode
                        val resetVersion = snapshot.child("resetVersion").getValue(Long::class.java) ?: 0L

                        val isLockCmd = commandType.equals("LOCK", ignoreCase = true) ||
                                commandType.equals("LOCK_DEVICE", ignoreCase = true) ||
                                cmdId.startsWith("LOCK")
                        val isUnlockCmd = commandType.equals("UNLOCK", ignoreCase = true) ||
                                commandType.equals("UNLOCK_DEVICE", ignoreCase = true) ||
                                cmdId.startsWith("UNLOCK")

                        val hasExplicitLockBool = snapshot.child("remoteLock").exists() || snapshot.child("isLocked").exists()
                        val effectiveLocked = if (hasExplicitLockBool) {
                            remoteLock || isLockedVal
                        } else {
                            when {
                                isLockCmd -> true
                                isUnlockCmd -> false
                                else -> false
                            }
                        }

                        val remoteAllowance = (getLongSafe(snapshot, "totalAllowance", -1L).takeIf { it >= 0 }
                            ?: getLongSafe(snapshot, "remoteAllowance", 21600L)).toInt()
                        val rawRem = getLongSafe(snapshot, "remainingSeconds", 21600L).toInt()
                        val rawUsed = (getLongSafe(snapshot, "usedSeconds", -1L).takeIf { it >= 0 }
                            ?: (remoteAllowance - rawRem).coerceAtLeast(0).toLong()).toInt()

                        val curfewOverride = snapshot.child("curfewOverride").getValue(Boolean::class.java) ?: false
                        val date = snapshot.child("date").getValue(String::class.java) ?: ScreenTimeManager.getCurrentScreenTimeDate()
                        val today = ScreenTimeManager.getCurrentScreenTimeDate()
                        val telemetryTimestamp = getLongSafe(snapshot, "telemetryTimestamp", 0L)
                        val now = System.currentTimeMillis()
                        val isRecentTelemetry = telemetryTimestamp > 0 && (now - telemetryTimestamp) < 24 * 3600 * 1000L

                        val isPastDate = !isRecentTelemetry && date.isNotBlank() && date < today
                        val used = if (isPastDate) 0 else rawUsed
                        val rem = if (effectiveLocked) 0 else (if (isPastDate) remoteAllowance else rawRem)
                        val recvTs = System.currentTimeMillis()

                        android.util.Log.i("HomeSyncLatency", "RTDB_COMMAND_RECEIVED commandId=$cmdId commandType=$commandType childCode=$cleanCode path=$rtdbPath latencyMs=${if (cmdTimestamp > 0) recvTs - cmdTimestamp else -1} timestamp=$recvTs")
                        android.util.Log.i("HomeSyncLatency", "COMMAND_RECEIVED commandId=$cmdId commandType=$commandType childCode=$cleanCode path=$rtdbPath latencyMs=${if (cmdTimestamp > 0) recvTs - cmdTimestamp else -1} timestamp=$recvTs")
                        android.util.Log.i("HomeSyncScreenTime", "SCREEN_TIME_RTDB_UPDATE childCode=$cleanCode locked=$effectiveLocked rem=$rem used=$used allowance=$remoteAllowance cmdId=$cmdId resetVersion=$resetVersion")

                        val stateSnapshot = ScreenTimeStateSnapshot(
                            remainingSeconds = rem,
                            isLocked = effectiveLocked,
                            totalAllowance = remoteAllowance,
                            usedSeconds = used,
                            commandId = cmdId,
                            commandTimestamp = cmdTimestamp,
                            curfewOverride = curfewOverride,
                            targetChildId = targetChildId,
                            commandType = commandType,
                            date = date,
                            resetVersion = resetVersion
                        )
                        lastKnownScreenTimeState[cleanCode] = stateSnapshot

                        val currentCallbacks = activeCommandListeners[cleanCode] ?: return
                        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        for (cb in currentCallbacks) {
                            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                                cb(rem, effectiveLocked, remoteAllowance, used, cmdId, cmdTimestamp, curfewOverride, targetChildId, commandType, date, resetVersion)
                            } else {
                                mainHandler.post {
                                    cb(rem, effectiveLocked, remoteAllowance, used, cmdId, cmdTimestamp, curfewOverride, targetChildId, commandType, date, resetVersion)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Error handling screen time onDataChange for $cleanCode", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    android.util.Log.w("HomeSyncLatency", "RTDB_LISTENER_CANCELLED commandId=NONE commandType=NONE childCode=$cleanCode path=$rtdbPath error=${error.message} timestamp=${System.currentTimeMillis()}")
                }
            }

            activeRtdbListeners[cleanCode] = listener
            rtdbRef.addValueEventListener(listener)
        }

        return {
            listenersList.remove(onUpdate)
            val remTs = System.currentTimeMillis()
            android.util.Log.i("HomeSyncLatency", "LISTENER_REMOVED commandId=NONE commandType=NONE childCode=$cleanCode path=$rtdbPath timestamp=$remTs")
        }
    }

    /**
     * Backward-compatible 10-parameter overload of listenScreenTimeWithCommandDetails.
     */
    fun listenScreenTimeWithCommandDetails(
        childCode: String,
        onUpdate: (
            remainingSeconds: Int,
            isLocked: Boolean,
            totalAllowance: Int,
            usedSeconds: Int,
            commandId: String,
            commandTimestamp: Long,
            curfewOverride: Boolean,
            targetChildId: String,
            commandType: String,
            date: String
        ) -> Unit
    ): (() -> Unit)? {
        return listenScreenTimeWithCommandDetails(childCode) { rem, locked, tot, used, cmdId, cmdTs, curfew, target, type, date, _ ->
            onUpdate(rem, locked, tot, used, cmdId, cmdTs, curfew, target, type, date)
        }
    }

    fun listenScreenTimeWithCommand(
        childCode: String,
        onUpdate: (remainingSeconds: Int, isLocked: Boolean, totalAllowance: Int, usedSeconds: Int, commandId: String, commandTimestamp: Long, curfewOverride: Boolean) -> Unit
    ): (() -> Unit)? {
        return listenScreenTimeWithCommandDetails(childCode) { rem, locked, tot, used, cmdId, cmdTs, curfewOverride, _, _, _, _ ->
            onUpdate(rem, locked, tot, used, cmdId, cmdTs, curfewOverride)
        }
    }

    /**
     * Overload of listenScreenTimeWithCommand for 6 parameters (omitting curfewOverride).
     */
    fun listenScreenTimeWithCommand(
        childCode: String,
        onUpdate: (remainingSeconds: Int, isLocked: Boolean, totalAllowance: Int, usedSeconds: Int, commandId: String, commandTimestamp: Long) -> Unit
    ): (() -> Unit)? {
        return listenScreenTimeWithCommand(childCode) { rem, locked, tot, used, cmdId, cmdTs, _ ->
            onUpdate(rem, locked, tot, used, cmdId, cmdTs)
        }
    }

    /**
     * Subscribes to real-time screen time and remote lock updates for a child from RTDB.
     */
    fun listenScreenTime(
        childCode: String,
        onUpdate: (remainingSeconds: Int, isLocked: Boolean, totalAllowance: Int, usedSeconds: Int) -> Unit
    ): (() -> Unit)? {
        return listenScreenTimeWithCommand(childCode) { rem, locked, tot, used, _, _ ->
            onUpdate(rem, locked, tot, used)
        }
    }

    /**
     * Overload of listenScreenTime for callers expecting 3 parameters.
     */
    fun listenScreenTime(
        childCode: String,
        onUpdate: (remainingSeconds: Int, isLocked: Boolean, totalAllowance: Int) -> Unit
    ): (() -> Unit)? {
        return listenScreenTime(childCode) { rem, locked, tot, _ ->
            onUpdate(rem, locked, tot)
        }
    }

    /**
     * Updates real-time online presence and heartbeat for a child device.
     */
    fun updatePresence(childCode: String, isOnline: Boolean) {
        val cleanCode = childCode.trim().uppercase()
        if (cleanCode.isBlank()) return
        val rtdb = getRtdb() ?: return
        try {
            val updates = mapOf(
                "isConnected" to isOnline,
                "lastActiveTime" to System.currentTimeMillis()
            )
            rtdb.getReference("hs_child_profiles").child(cleanCode).updateChildren(updates)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating child presence", e)
        }
    }

    /**
     * Dual-Syncs live GPS coordinates and address to Realtime Database `hs_locations` for instantaneous map rendering.
     */
    fun uploadChildLocation(
        childCode: String,
        childName: String,
        latitude: Double,
        longitude: Double,
        address: String
    ) {
        val cleanCode = childCode.trim().uppercase()
        if (cleanCode.isBlank()) return

        // 1. Sync to Firestore
        FirebaseSyncManager.uploadChildLocation(cleanCode, childName, latitude, longitude, address)

        // 2. Sync to Realtime Database
        val rtdb = getRtdb() ?: return
        try {
            val payload = mapOf(
                "childCode" to cleanCode,
                "childName" to childName.trim().ifBlank { "Child" },
                "latitude" to latitude,
                "longitude" to longitude,
                "address" to address,
                "timestamp" to System.currentTimeMillis()
            )
            rtdb.getReference("hs_locations").child(cleanCode).setValue(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading child location to RTDB", e)
        }
    }

    /**
     * Alias for uploadChildLocation with safe zone parameter.
     */
    fun syncLocation(
        childCode: String,
        latitude: Double,
        longitude: Double,
        isInsideSafeZone: Boolean = true,
        address: String = "",
        childName: String = "Child"
    ) {
        uploadChildLocation(childCode, childName, latitude, longitude, address)
    }

    /**
     * Subscribes to real-time location updates from Realtime Database for lowest possible latency.
     * Returns a ValueEventListener that can be removed on dispose.
     */
    fun listenChildLocation(
        childCode: String,
        onLocationUpdated: (ChildLocationCloudData) -> Unit
    ): ValueEventListener? {
        val cleanCode = childCode.trim().uppercase()
        if (cleanCode.isBlank()) return null
        val rtdb = getRtdb() ?: return null

        return try {
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) return
                    val lat = snapshot.child("latitude").getValue(Double::class.java) ?: return
                    val lng = snapshot.child("longitude").getValue(Double::class.java) ?: return
                    val name = snapshot.child("childName").getValue(String::class.java) ?: "Child"
                    val address = snapshot.child("address").getValue(String::class.java) ?: ""
                    val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: System.currentTimeMillis()

                    if (lat != 0.0 || lng != 0.0) {
                        onLocationUpdated(
                            ChildLocationCloudData(
                                childCode = cleanCode,
                                childName = name,
                                latitude = lat,
                                longitude = lng,
                                address = address,
                                timestamp = timestamp
                            )
                        )
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Location RTDB listener cancelled for $cleanCode", error.toException())
                }
            }
            rtdb.getReference("hs_locations").child(cleanCode).addValueEventListener(listener)
            listener
        } catch (e: Exception) {
            Log.e(TAG, "Error registering RTDB location listener for $cleanCode", e)
            null
        }
    }

    fun removeLocationListener(childCode: String, listener: ValueEventListener?) {
        if (listener == null) return
        val cleanCode = childCode.trim().uppercase()
        val rtdb = getRtdb() ?: return
        try {
            rtdb.getReference("hs_locations").child(cleanCode).removeEventListener(listener)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing location listener for $cleanCode", e)
        }
    }

    /**
     * Profile syncing is authoritatively handled by Cloud Firestore (FamilyManager).
     * Realtime Database is strictly reserved for live telemetry (GPS, screentime, presence).
     */
    fun syncFamilyMemberProfile(
        familyId: String,
        userId: String,
        name: String,
        role: String,
        photoUrl: String
    ) {
        if (photoUrl.isNotBlank()) {
            FamilyManager.updateMemberProfilePicture(familyId, userId, photoUrl)
        }
    }

    /**
     * Dual-Sync system notifications via Realtime Database (`hs_notifications`).
     */
    fun sendNotification(notification: SystemNotification) {
        val rtdb = getRtdb() ?: return
        try {
            val payload = mapOf(
                "id" to notification.id,
                "title" to notification.title,
                "message" to notification.message,
                "timestamp" to notification.timestamp,
                "type" to notification.type.name,
                "childName" to notification.childName,
                "childCode" to notification.childCode.trim().uppercase(),
                "isRead" to notification.isRead,
                "actionData" to notification.actionData,
                "targetRole" to notification.targetRole,
                "familyId" to notification.familyId,
                "childUid" to notification.childUid,
                "latitude" to notification.latitude,
                "longitude" to notification.longitude
            )
            rtdb.getReference("hs_notifications").child(notification.id).setValue(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing notification to RTDB", e)
        }
    }

    fun markNotificationRead(notificationId: String) {
        if (notificationId.isBlank()) return
        val rtdb = getRtdb() ?: return
        try {
            rtdb.getReference("hs_notifications").child(notificationId).child("isRead").setValue(true)
        } catch (_: Exception) {}
    }

    fun deleteNotification(notificationId: String) {
        if (notificationId.isBlank()) return
        val rtdb = getRtdb() ?: return
        try {
            rtdb.getReference("hs_notifications").child(notificationId).removeValue()
        } catch (_: Exception) {}
    }

    fun listenNotifications(onNotification: (SystemNotification) -> Unit): (() -> Unit)? {
        val rtdb = getRtdb() ?: return null
        val ref = rtdb.getReference("hs_notifications")
        val processedNotifIds = java.util.Collections.synchronizedSet(java.util.LinkedHashSet<String>())
        val listener = object : com.google.firebase.database.ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val notif = parseNotificationSnapshot(snapshot) ?: return
                if (!processedNotifIds.add(notif.id)) return
                if (processedNotifIds.size > 200) {
                    val it = processedNotifIds.iterator()
                    if (it.hasNext()) { it.next(); it.remove() }
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onNotification(notif)
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val notif = parseNotificationSnapshot(snapshot) ?: return
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onNotification(notif)
                }
            }
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "RTDB notification listener cancelled: ${error.message}")
            }
        }
        ref.addChildEventListener(listener)
        return {
            try { ref.removeEventListener(listener) } catch (_: Exception) {}
        }
    }

    private fun parseNotificationSnapshot(snapshot: DataSnapshot): SystemNotification? {
        if (!snapshot.exists()) return null
        val id = snapshot.child("id").getValue(String::class.java) ?: snapshot.key ?: return null
        val title = snapshot.child("title").getValue(String::class.java) ?: "Notification"
        val message = snapshot.child("message").getValue(String::class.java) ?: ""
        val timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: System.currentTimeMillis()
        val typeName = snapshot.child("type").getValue(String::class.java) ?: NotificationType.CHILD_SAFE_CHECKIN.name
        val type = try { NotificationType.valueOf(typeName) } catch (_: Exception) { NotificationType.CHILD_SAFE_CHECKIN }
        val childName = snapshot.child("childName").getValue(String::class.java) ?: "Child"
        val childCode = snapshot.child("childCode").getValue(String::class.java) ?: ""
        val isRead = snapshot.child("isRead").getValue(Boolean::class.java) ?: false
        val actionData = snapshot.child("actionData").getValue(String::class.java) ?: ""
        val targetRole = snapshot.child("targetRole").getValue(String::class.java) ?: "GUARDIAN"
        val familyId = snapshot.child("familyId").getValue(String::class.java) ?: ""
        val childUid = snapshot.child("childUid").getValue(String::class.java) ?: ""
        val latitude = snapshot.child("latitude").getValue(Double::class.java) ?: 0.0
        val longitude = snapshot.child("longitude").getValue(Double::class.java) ?: 0.0

        return SystemNotification(
            id = id,
            title = title,
            message = message,
            timestamp = timestamp,
            type = type,
            childName = childName,
            childCode = childCode,
            isRead = isRead,
            actionData = actionData,
            targetRole = targetRole,
            familyId = familyId,
            childUid = childUid,
            latitude = latitude,
            longitude = longitude
        )
    }

    fun sendEmergencySos(familyId: String, childCode: String, notification: SystemNotification) {
        val rtdb = getRtdb() ?: return
        val cleanFamily = familyId.trim().uppercase()
        val cleanCode = childCode.trim().uppercase()
        val payload = mapOf(
            "id" to notification.id,
            "eventId" to notification.id,
            "active" to true,
            "title" to notification.title,
            "message" to notification.message,
            "timestamp" to notification.timestamp,
            "childName" to notification.childName,
            "childCode" to cleanCode,
            "childUid" to notification.childUid,
            "familyId" to cleanFamily,
            "latitude" to notification.latitude,
            "longitude" to notification.longitude,
            "status" to "ACTIVE"
        )
        try {
            if (cleanFamily.isNotBlank() && cleanCode.isNotBlank()) {
                rtdb.getReference("hs_sos").child(cleanFamily).child(cleanCode).setValue(payload)
                Log.i(TAG, "SOS_RTDB_FAST_DISPATCH family=$cleanFamily child=$cleanCode id=${notification.id}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending emergency SOS to RTDB", e)
        }
    }

    fun acknowledgeEmergencySos(familyId: String, childCode: String) {
        val rtdb = getRtdb() ?: return
        val cleanFamily = familyId.trim().uppercase()
        val cleanCode = childCode.trim().uppercase()
        if (cleanFamily.isNotBlank() && cleanCode.isNotBlank()) {
            val updates = mapOf(
                "active" to false,
                "status" to "ACKNOWLEDGED",
                "acknowledgedAt" to System.currentTimeMillis()
            )
            rtdb.getReference("hs_sos").child(cleanFamily).child(cleanCode).updateChildren(updates)
                .addOnSuccessListener {
                    Log.i(TAG, "SOS_ACKNOWLEDGED_RTDB family=$cleanFamily child=$cleanCode")
                }
        }
    }

    fun listenEmergencySos(familyId: String, onSos: (SystemNotification) -> Unit): (() -> Unit)? {
        val rtdb = getRtdb() ?: return null
        val cleanFamily = familyId.trim().uppercase()
        if (cleanFamily.isBlank()) return null
        val ref = rtdb.getReference("hs_sos").child(cleanFamily)
        val emittedIds = java.util.Collections.synchronizedSet(java.util.HashSet<String>())
        val listener = object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                for (childSnap in snapshot.children) {
                    val active = childSnap.child("active").getValue(Boolean::class.java) ?: true
                    val status = childSnap.child("status").getValue(String::class.java) ?: "ACTIVE"
                    val timestamp = childSnap.child("timestamp").getValue(Long::class.java) ?: 0L
                    val ageMs = System.currentTimeMillis() - timestamp
                    // Process active recent alerts (within last 15 minutes)
                    if (active && status.equals("ACTIVE", ignoreCase = true) && ageMs < 900000L) {
                        val id = childSnap.child("id").getValue(String::class.java)
                            ?: childSnap.child("eventId").getValue(String::class.java)
                            ?: childSnap.key ?: java.util.UUID.randomUUID().toString()

                        if (!emittedIds.add(id)) {
                            // Already emitted to this listener
                            continue
                        }

                        val childName = childSnap.child("childName").getValue(String::class.java) ?: "Child"
                        val childCode = childSnap.child("childCode").getValue(String::class.java) ?: childSnap.key ?: ""
                        val childUid = childSnap.child("childUid").getValue(String::class.java) ?: ""
                        val title = childSnap.child("title").getValue(String::class.java) ?: "EMERGENCY SOS ALERT"
                        val message = childSnap.child("message").getValue(String::class.java) ?: "Emergency alert from $childName!"
                        val lat = childSnap.child("latitude").getValue(Double::class.java) ?: 0.0
                        val lng = childSnap.child("longitude").getValue(Double::class.java) ?: 0.0

                        val notif = SystemNotification(
                            id = id,
                            title = title,
                            message = message,
                            timestamp = timestamp,
                            type = NotificationType.SOS_EMERGENCY,
                            childName = childName,
                            childCode = childCode,
                            targetRole = "GUARDIAN",
                            familyId = cleanFamily,
                            childUid = childUid,
                            latitude = lat,
                            longitude = lng
                        )
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onSos(notif)
                        }
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "RTDB hs_sos listener cancelled: ${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        return {
            try { ref.removeEventListener(listener) } catch (_: Exception) {}
        }
    }
}

