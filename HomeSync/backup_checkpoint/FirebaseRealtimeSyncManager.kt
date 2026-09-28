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

    fun getRtdb(): FirebaseDatabase? {
        return try {
            val instance = try {
                FirebaseDatabase.getInstance(RTDB_URL)
            } catch (_: Exception) {
                FirebaseDatabase.getInstance()
            }
            try { instance.setPersistenceEnabled(true) } catch (_: Exception) {}
            instance
        } catch (e: Exception) {
            try {
                FirebaseDatabase.getInstance(RTDB_URL)
            } catch (ex: Exception) {
                Log.e(TAG, "Error obtaining Firebase Realtime Database instance", ex)
                null
            }
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
        usedSeconds: Int = (totalAllowance - remainingSeconds).coerceAtLeast(0)
    ) {
        val cleanCode = childCode.trim().uppercase()
        if (cleanCode.isBlank()) return

        // 1. Sync to Firestore
        FirebaseSyncManager.syncScreenTimeToCloud(cleanCode, remainingSeconds, isLocked, totalAllowance, usedSeconds)

        // 2. Sync to Realtime Database using updateChildren ONLY (never destructive setValue)
        val rtdb = getRtdb() ?: return
        try {
            val payload = mapOf(
                "childCode" to cleanCode,
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
     * Resets screen time at 05:30 AM boundary across cloud backends.
     */
    fun syncScreenTimeReset(childCode: String, boundaryTimestamp: Long) {
        val cleanCode = childCode.trim().uppercase()
        if (cleanCode.isBlank()) return

        // 1. Dual-Sync to Firestore
        FirebaseSyncManager.syncScreenTimeToCloud(cleanCode, 21600, false, 21600, 0)

        // 2. Sync to Realtime Database
        val rtdb = getRtdb() ?: return
        try {
            val payload = mapOf(
                "childCode" to cleanCode,
                "remoteLock" to false,
                "isLocked" to false,
                "remoteAllowance" to 21600,
                "totalAllowance" to 21600,
                "remainingSeconds" to 21600,
                "usedSeconds" to 0,
                "commandId" to "RESET_530_$boundaryTimestamp",
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
     * Subscribes to real-time screen time, remote lock, and command updates for a child from RTDB.
     * Includes automatic re-attachment on network disconnects.
     * Returns a cancellation function to cleanly unregister the listener on dispose.
     */
    fun listenScreenTimeWithCommand(
        childCode: String,
        onUpdate: (remainingSeconds: Int, isLocked: Boolean, totalAllowance: Int, usedSeconds: Int, commandId: String, commandTimestamp: Long, curfewOverride: Boolean) -> Unit
    ): (() -> Unit)? {
        val cleanCode = childCode.trim().uppercase()
        if (cleanCode.isBlank()) return null

        val rtdb = getRtdb() ?: return null
        val rtdbPath = "hs_screentime/$cleanCode"
        Log.i("HomeSyncScreenTime", "SCREEN_TIME_RTDB_PATH path=$rtdbPath")

        var isDisposed = false
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var currentRef: DatabaseReference? = null
        var currentListener: ValueEventListener? = null
        var reconnectRunnable: Runnable? = null

        fun attachListener() {
            if (isDisposed) return
            try {
                val ref = rtdb.getReference("hs_screentime").child(cleanCode)
                currentRef = ref

                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (isDisposed || !snapshot.exists()) return
                        val remoteLock = snapshot.child("remoteLock").getValue(Boolean::class.java)
                            ?: snapshot.child("isLocked").getValue(Boolean::class.java)
                            ?: false
                        val remoteAllowance = (snapshot.child("remoteAllowance").getValue(Long::class.java)
                            ?: snapshot.child("totalAllowance").getValue(Long::class.java)
                            ?: 21600L).toInt()
                        val rem = (snapshot.child("remainingSeconds").getValue(Long::class.java) ?: 21600L).toInt()
                        val used = (snapshot.child("usedSeconds").getValue(Long::class.java) ?: (remoteAllowance - rem).coerceAtLeast(0).toLong()).toInt()

                        val cmdId = snapshot.child("commandId").getValue(String::class.java) ?: "NONE"
                        val cmdTimestamp = snapshot.child("commandTimestamp").getValue(Long::class.java) ?: 0L
                        val curfewOverride = snapshot.child("curfewOverride").getValue(Boolean::class.java) ?: false
                        Log.i("HomeSyncScreenTime", "SCREEN_TIME_COMMAND_RECEIVED childCode=$cleanCode commandId=$cmdId remoteLock=$remoteLock allowance=$remoteAllowance used=$used remaining=$rem curfewOverride=$curfewOverride timestamp=${System.currentTimeMillis()}")
                        Log.i("HomeSyncScreenTime", "SCREEN_TIME_REMOTE_UPDATE childCode=$cleanCode used=$used remaining=$rem allowance=$remoteAllowance locked=$remoteLock")

                        mainHandler.post {
                            if (!isDisposed) {
                                onUpdate(rem, remoteLock, remoteAllowance, used, cmdId, cmdTimestamp, curfewOverride)
                            }
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e("HomeSyncScreenTime", "SCREEN_TIME_RTDB_ERROR code=${error.code} message=${error.message} path=$rtdbPath", error.toException())
                        Log.e(TAG, "Screen time RTDB listener cancelled for $cleanCode", error.toException())
                        if (!isDisposed) {
                            val r = Runnable {
                                if (!isDisposed) {
                                    Log.i("HomeSyncScreenTime", "Re-attaching screen time RTDB listener for $cleanCode after disconnect")
                                    attachListener()
                                }
                            }
                            reconnectRunnable = r
                            mainHandler.postDelayed(r, 1500)
                        }
                    }
                }

                currentListener = listener
                ref.addValueEventListener(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Error attaching screen time RTDB listener for $cleanCode", e)
            }
        }

        attachListener()

        return {
            isDisposed = true
            reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
            try {
                if (currentRef != null && currentListener != null) {
                    currentRef?.removeEventListener(currentListener!!)
                }
            } catch (_: Exception) {}
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
                "targetRole" to notification.targetRole
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
        val listener = object : com.google.firebase.database.ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                parseNotificationSnapshot(snapshot)?.let {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onNotification(it)
                    }
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                parseNotificationSnapshot(snapshot)?.let {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onNotification(it)
                    }
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
            targetRole = targetRole
        )
    }
}

