package com.homesync.app.util

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class GuardianProfileCloudData(
    val guardianName: String = "Guardian",
    val childCode: String = "",
    val email: String = "",
    val avatarBase64: String = "",
    val isConnected: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

data class ChildProfileCloudData(
    val childCode: String = "",
    val name: String = "Child",
    val avatarBase64: String = "",
    val rewardStars: Int = 0,
    val levelLabel: String = "Lvl 1 Hero",
    val xpProgress: Int = 0,
    val streakDays: Int = 0,
    val isConnected: Boolean = true,
    val lastActiveTime: Long = System.currentTimeMillis()
)

data class ChildActivityCloudItem(
    val id: String = UUID.randomUUID().toString(),
    val childCode: String = "",
    val title: String = "",
    val detail: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val category: String = "TASK"
)

data class ChildLocationCloudData(
    val childCode: String = "",
    val childName: String = "Child",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val address: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

object FirebaseSyncManager {
    private const val TAG = "FirebaseSyncManager"
    private const val COLLECTION_QUESTS = "hs_quests"
    private const val COLLECTION_NOTIFICATIONS = "hs_notifications"
    private const val COLLECTION_LOCATIONS = "hs_locations"

    /**
     * Deprecated: Automatic anonymous auth has been removed from normal startup/login paths.
     * Google Account / Firebase Auth UID is now the canonical identity.
     */
    @Deprecated("Do not use anonymous auth in normal flow; use Google Sign-In.")
    fun ensureAnonymousAuth(onComplete: ((String?) -> Unit)? = null) {
        try {
            val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
            val current = auth.currentUser
            if (current != null) {
                onComplete?.invoke(current.uid)
            } else {
                Log.w(TAG, "ensureAnonymousAuth bypassed: App requires authenticated user.")
                onComplete?.invoke(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking auth", e)
            onComplete?.invoke(null)
        }
    }

    private val firestoreInstance: FirebaseFirestore? by lazy {
        try {
            val db = FirebaseFirestore.getInstance()
            try {
                val settings = com.google.firebase.firestore.FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(
                        com.google.firebase.firestore.PersistentCacheSettings.newBuilder()
                            .setSizeBytes(104857600L) // 100 MB offline persistent disk cache
                            .build()
                    )
                    .build()
                db.firestoreSettings = settings
            } catch (se: Exception) {
                Log.w(TAG, "Firestore settings could not be set (already initialized): ${se.message}")
            }
            db
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Firestore instance", e)
            null
        }
    }

    fun getDb(): FirebaseFirestore? = firestoreInstance

    /**
     * Uploads the full quest list and base64 photo proof payload to Cloud Firestore under document ID `childCode`.
     */
    fun syncQuestsToCloud(context: Context, childCode: String, quests: List<ChildQuest>) {
        val cleanCode = childCode.trim().uppercase().ifBlank { "DEFAULT_CHILD" }
        val db = getDb() ?: return

        try {
            val questsArray = JSONArray()
            val photoMap = mutableMapOf<String, String>()

            for (q in quests) {
                val obj = JSONObject().apply {
                    put("id", q.id)
                    put("title", q.title)
                    put("rewardStars", q.rewardStars)
                    put("dueTime", q.dueTime)
                    put("status", q.status.name)
                    put("photoProofLabel", q.photoProofLabel)
                    put("photoProofUri", q.photoProofUri)
                    if (q.submittedAt != null) put("submittedAt", q.submittedAt)
                    if (q.verifiedAt != null) put("verifiedAt", q.verifiedAt)
                }
                questsArray.put(obj)

                // If quest has a photo proof, check if we can encode it to Base64 for cloud sync
                if (q.photoProofUri.isNotBlank()) {
                    val bitmap = TaskProofImageManager.getProofBitmap(context, q.photoProofUri, q.id)
                    if (bitmap != null) {
                        val base64 = TaskProofImageManager.encodeBitmapToBase64(bitmap)
                        if (base64.isNotBlank()) {
                            photoMap["photoBase64_${q.id}"] = base64
                        }
                    }
                }
            }

            val payload = mutableMapOf<String, Any>(
                "childCode" to cleanCode,
                "questsJson" to questsArray.toString(),
                "updatedAt" to System.currentTimeMillis()
            )
            payload.putAll(photoMap)

            db.collection(COLLECTION_QUESTS)
                .document(cleanCode)
                .set(payload, SetOptions.merge())
                .addOnSuccessListener {
                    Log.d(TAG, "Successfully synced quests to Firestore for childCode: $cleanCode")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to sync quests to Firestore for childCode: $cleanCode", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing quest sync payload", e)
        }
    }

    /**
     * Listens to real-time updates for `childCode` from Cloud Firestore.
     */
    fun listenQuestsFromCloud(
        context: Context,
        childCode: String,
        onQuestsUpdated: (List<ChildQuest>) -> Unit
    ): ListenerRegistration? {
        val cleanCode = childCode.trim().uppercase().ifBlank { "DEFAULT_CHILD" }
        val db = getDb() ?: return null

        return try {
            db.collection(COLLECTION_QUESTS)
                .document(cleanCode)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Error listening to quests from Firestore for $cleanCode", error)
                        return@addSnapshotListener
                    }
                    if (snapshot == null || !snapshot.exists()) {
                        return@addSnapshotListener
                    }

                    val jsonString = snapshot.getString("questsJson") ?: return@addSnapshotListener
                    val updatedQuests = mutableListOf<ChildQuest>()

                    try {
                        val jsonArray = JSONArray(jsonString)
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val qId = obj.optString("id", UUID.randomUUID().toString())
                            val statusName = obj.optString("status", QuestStatus.PENDING.name)
                            val status = try { QuestStatus.valueOf(statusName) } catch (_: Exception) { QuestStatus.PENDING }

                            var photoUri = obj.optString("photoProofUri", "")
                            // Check if snapshot contains base64 image data sent by child device
                            val base64Str = snapshot.getString("photoBase64_${qId}")
                            if (!base64Str.isNullOrBlank()) {
                                val localSavedPath = TaskProofImageManager.saveBase64ImageLocally(context, qId, base64Str)
                                if (localSavedPath.isNotBlank()) {
                                    photoUri = localSavedPath
                                }
                            }

                            updatedQuests.add(
                                ChildQuest(
                                    id = qId,
                                    title = obj.optString("title", ""),
                                    rewardStars = obj.optInt("rewardStars", 20),
                                    dueTime = obj.optString("dueTime", ""),
                                    status = status,
                                    photoProofLabel = obj.optString("photoProofLabel", ""),
                                    photoProofUri = photoUri,
                                    submittedAt = if (obj.has("submittedAt") && !obj.isNull("submittedAt")) obj.optLong("submittedAt") else null,
                                    verifiedAt = if (obj.has("verifiedAt") && !obj.isNull("verifiedAt")) obj.optLong("verifiedAt") else null
                                )
                            )
                        }
                        if (updatedQuests.isNotEmpty()) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onQuestsUpdated(updatedQuests)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing remote Firestore quest JSON", e)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up Firestore quest snapshot listener", e)
            null
        }
    }

    /**
     * Uploads a SystemNotification to Cloud Firestore `hs_notifications` collection.
     */
    fun sendNotificationToCloud(notification: SystemNotification) {
        // Dual-sync to Realtime Database for zero-latency cross-device delivery
        FirebaseRealtimeSyncManager.sendNotification(notification)

        val db = getDb() ?: return
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

            db.collection(COLLECTION_NOTIFICATIONS)
                .document(notification.id)
                .set(payload, SetOptions.merge())
                .addOnSuccessListener {
                    Log.d(TAG, "Successfully uploaded notification to Firestore: ${notification.id}")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to upload notification to Firestore", e)
                }

            // If this is an Emergency SOS alert, write to trusted hs_sos_events collection
            if (notification.type == NotificationType.SOS_EMERGENCY) {
                val eventId = if (notification.id.startsWith("sos_")) notification.id else "sos_${java.util.UUID.randomUUID()}"
                Log.i(TAG, "SOS_EVENT_CREATED eventId=$eventId familyId=${notification.familyId} childName=${notification.childName} childUid=${notification.childUid}")
                val sosPayload = mapOf(
                    "eventId" to eventId,
                    "title" to notification.title,
                    "message" to notification.message,
                    "childName" to notification.childName,
                    "childCode" to notification.childCode.trim().uppercase(),
                    "childUid" to notification.childUid,
                    "familyId" to notification.familyId,
                    "latitude" to notification.latitude,
                    "longitude" to notification.longitude,
                    "timestamp" to notification.timestamp,
                    "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                    "status" to "ACTIVE"
                )
                db.collection("hs_sos_events")
                    .document(eventId)
                    .set(sosPayload)
                    .addOnSuccessListener {
                        Log.i(TAG, "Successfully dispatched trusted cloud SOS event: $eventId")
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Failed to dispatch cloud SOS event", e)
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading notification to Firestore", e)
        }
    }

    fun markNotificationReadInCloud(notificationId: String) {
        if (notificationId.isBlank()) return
        FirebaseRealtimeSyncManager.markNotificationRead(notificationId)
        val db = getDb() ?: return
        try {
            db.collection(COLLECTION_NOTIFICATIONS)
                .document(notificationId)
                .update("isRead", true)
        } catch (e: Exception) {
            Log.e(TAG, "Error marking notification read in Firestore", e)
        }
    }

    fun deleteNotificationInCloud(notificationId: String) {
        if (notificationId.isBlank()) return
        FirebaseRealtimeSyncManager.deleteNotification(notificationId)
        val db = getDb() ?: return
        try {
            db.collection(COLLECTION_NOTIFICATIONS)
                .document(notificationId)
                .delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting notification from Firestore", e)
        }
    }

    /**
     * Listens for incoming real-time notifications from Firestore and Realtime Database.
     */
    fun listenNotificationsFromCloud(
        context: Context,
        onNotificationReceived: (SystemNotification) -> Unit
    ): ListenerRegistration? {
        val rtdbCancel = FirebaseRealtimeSyncManager.listenNotifications(onNotificationReceived)

        val db = getDb()
        val firestoreRegistration = try {
            db?.collection(COLLECTION_NOTIFICATIONS)
                ?.addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.e(TAG, "Error listening to notifications from Firestore", error)
                        return@addSnapshotListener
                    }
                    if (snapshots == null || snapshots.isEmpty) return@addSnapshotListener

                    for (change in snapshots.documentChanges) {
                        if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                            val doc = change.document
                            val notifId = doc.getString("id") ?: doc.id
                            val title = doc.getString("title") ?: "Notification"
                            val message = doc.getString("message") ?: ""
                            val timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis()
                            val typeName = doc.getString("type") ?: NotificationType.CHILD_SAFE_CHECKIN.name
                            val type = try { NotificationType.valueOf(typeName) } catch (_: Exception) { NotificationType.CHILD_SAFE_CHECKIN }
                            val childName = doc.getString("childName") ?: "Child"
                            val childCode = doc.getString("childCode") ?: ""
                            val isRead = doc.getBoolean("isRead") ?: false
                            val actionData = doc.getString("actionData") ?: ""
                            val targetRole = doc.getString("targetRole") ?: "GUARDIAN"

                            val notif = SystemNotification(
                                id = notifId,
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
                            onNotificationReceived(notif)
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up notification snapshot listener", e)
            null
        }

        return object : ListenerRegistration {
            override fun remove() {
                try { firestoreRegistration?.remove() } catch (_: Exception) {}
                try { rtdbCancel?.invoke() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Uploads the child profile details (including Base64 avatar) to Firestore.
     */
    fun syncChildProfileToCloud(context: Context, profile: ChildProfileCloudData) {
        val cleanCode = profile.childCode.trim().uppercase().ifBlank { "DEFAULT_CHILD" }
        val db = getDb() ?: return

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

            db.collection("hs_child_profiles")
                .document(cleanCode)
                .set(payload, SetOptions.merge())
                .addOnSuccessListener {
                    Log.d(TAG, "Successfully uploaded child profile for $cleanCode")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to upload child profile for $cleanCode", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing child profile to Firestore", e)
        }
    }

    /**
     * Listens to real-time child profile changes (photo, name, stars) from Firestore.
     */
    fun listenChildProfileFromCloud(
        context: Context,
        childCode: String,
        onProfileUpdated: (ChildProfileCloudData) -> Unit
    ): ListenerRegistration? {
        val cleanCode = childCode.trim().uppercase().ifBlank { "DEFAULT_CHILD" }
        val db = getDb() ?: return null

        return try {
            val processDoc: (com.google.firebase.firestore.DocumentSnapshot) -> Unit = { doc ->
                val name = doc.getString("name") ?: "Child"
                val avatarBase64 = doc.getString("avatarBase64") ?: ""
                val rewardStars = (doc.getLong("rewardStars") ?: 0L).toInt()
                val levelLabel = doc.getString("levelLabel") ?: "Lvl 1 Hero"
                val xpProgress = (doc.getLong("xpProgress") ?: 0L).toInt()
                val streakDays = (doc.getLong("streakDays") ?: 0L).toInt()
                val isConnected = doc.getBoolean("isConnected") ?: true
                val lastActiveTime = doc.getLong("lastActiveTime") ?: System.currentTimeMillis()
                val docCode = doc.getString("childCode") ?: doc.id

                if (avatarBase64.isNotBlank()) {
                    val decoded = com.homesync.app.util.TaskProofImageManager.decodeBase64ToBitmap(avatarBase64)
                    if (decoded != null) {
                        ProfileImageManager.saveProfileImage(context, decoded, key = "child_$docCode")
                        ProfileImageManager.saveProfileImage(context, decoded, key = "child_${docCode.lowercase()}")
                        ProfileImageManager.saveProfileImage(context, decoded, key = "child_${docCode.uppercase()}")
                    }
                }

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

                if (avatarBase64.isNotBlank()) {
                    FirebaseStorageHelper.downloadAndCachePhoto(context, "child_$docCode", avatarBase64) { downloaded ->
                        if (downloaded != null) {
                            ProfileImageManager.saveProfileImage(context, downloaded, key = "child_${docCode.lowercase()}")
                            ProfileImageManager.saveProfileImage(context, downloaded, key = "child_${docCode.uppercase()}")
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                onProfileUpdated(profileData)
                            }
                        }
                    }
                }

                onProfileUpdated(profileData)
            }

            db.collection("hs_child_profiles")
                .document(cleanCode)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                    processDoc(snapshot)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up child profile snapshot listener", e)
            null
        }
    }

    /**
     * Listens to all registered child profiles in Firestore collection hs_child_profiles.
     */
    fun listenAllChildrenFromCloud(
        context: Context,
        onChildDiscovered: (ChildProfileCloudData) -> Unit
    ): ListenerRegistration? {
        val db = getDb() ?: return null
        return try {
            db.collection("hs_child_profiles")
                .addSnapshotListener { snapshots, error ->
                    if (error != null || snapshots == null) return@addSnapshotListener
                    for (doc in snapshots.documents) {
                        val docCode = doc.getString("childCode") ?: doc.id
                        val cleanCode = docCode.trim().uppercase()
                        if (cleanCode.isBlank() || cleanCode == "DEFAULT_CHILD") continue

                        val rawName = doc.getString("name") ?: ""
                        // Use saved local name as fallback if cloud has blank/default name
                        val localName = ChildIdManager.getChildName(context, cleanCode).ifBlank { rawName }
                        val name = localName.ifBlank { rawName.ifBlank { "Child ${cleanCode.takeLast(4)}" } }
                        // Skip only truly invalid or Sarah entries
                        if (name.equals("Sarah", ignoreCase = true)) continue


                        val avatarBase64 = doc.getString("avatarBase64") ?: ""
                        val rewardStars = (doc.getLong("rewardStars") ?: 0L).toInt()
                        val levelLabel = doc.getString("levelLabel") ?: "Lvl 1 Hero"
                        val xpProgress = (doc.getLong("xpProgress") ?: 0L).toInt()
                        val streakDays = (doc.getLong("streakDays") ?: 0L).toInt()
                        val isConnected = doc.getBoolean("isConnected") ?: true
                        val lastActiveTime = doc.getLong("lastActiveTime") ?: System.currentTimeMillis()

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

                        if (avatarBase64.isNotBlank()) {
                            FirebaseStorageHelper.downloadAndCachePhoto(context, "child_$cleanCode", avatarBase64) { downloaded ->
                                if (downloaded != null) {
                                    ProfileImageManager.saveProfileImage(context, downloaded, key = "child_${cleanCode.lowercase()}")
                                    ProfileImageManager.saveProfileImage(context, downloaded, key = "child_${cleanCode.uppercase()}")
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        onChildDiscovered(profile)
                                    }
                                }
                            }
                        }

                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onChildDiscovered(profile)
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error listening to all children from Firestore", e)
            null
        }
    }

    /**
     * Logs a child activity entry (e.g. task photo submitted, check-in) to Firestore.
     */
    fun logActivityToCloud(context: Context, activity: ChildActivityCloudItem) {
        val cleanCode = activity.childCode.trim().uppercase()
        if (cleanCode.isBlank()) return
        val db = getDb() ?: return

        try {
            val payload = mapOf(
                "id" to activity.id,
                "childCode" to cleanCode,
                "title" to activity.title,
                "detail" to activity.detail,
                "timestamp" to activity.timestamp,
                "category" to activity.category
            )

            db.collection("hs_activities")
                .document(activity.id)
                .set(payload, SetOptions.merge())
        } catch (e: Exception) {
            Log.e(TAG, "Error logging child activity to Firestore", e)
        }
    }

    /**
     * Listens to real-time activity feed entries for a child from Firestore.
     */
    fun listenChildActivitiesFromCloud(
        context: Context,
        childCode: String,
        onActivitiesUpdated: (List<ChildActivityCloudItem>) -> Unit
    ): ListenerRegistration? {
        val cleanCode = childCode.trim().uppercase()
        if (cleanCode.isBlank()) return null
        val db = getDb() ?: return null

        return try {
            db.collection("hs_activities")
                .addSnapshotListener { snapshots, error ->
                    if (error != null || snapshots == null || snapshots.isEmpty) return@addSnapshotListener
                    val list = mutableListOf<ChildActivityCloudItem>()
                    for (doc in snapshots.documents) {
                        val docChildCode = doc.getString("childCode") ?: ""
                        if (docChildCode.equals(cleanCode, ignoreCase = true) || docChildCode.isNotBlank()) {
                            list.add(
                                ChildActivityCloudItem(
                                    id = doc.getString("id") ?: doc.id,
                                    childCode = docChildCode,
                                    title = doc.getString("title") ?: "",
                                    detail = doc.getString("detail") ?: "",
                                    timestamp = doc.getLong("timestamp") ?: System.currentTimeMillis(),
                                    category = doc.getString("category") ?: "TASK"
                                )
                            )
                        }
                    }
                    if (list.isNotEmpty()) {
                        onActivitiesUpdated(list.sortedByDescending { it.timestamp })
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error listening to child activities from Firestore", e)
            null
        }
    }

    /**
     * Uploads the Guardian's profile name, avatar (Base64), and pairing details to Cloud Firestore.
     */
    fun syncGuardianProfileToCloud(context: Context, guardianName: String, childCode: String, avatarBase64: String = "", email: String = "") {
        val cleanCode = childCode.trim().uppercase()
        val cleanName = guardianName.trim().ifBlank { "Guardian" }
        if (cleanName.equals("User", ignoreCase = true) || cleanName.equals("Child", ignoreCase = true)) return
        val db = getDb() ?: return

        try {
            val payload = mutableMapOf<String, Any>(
                "guardianName" to cleanName,
                "childCode" to cleanCode,
                "email" to email,
                "isConnected" to true,
                "updatedAt" to System.currentTimeMillis()
            )
            // Cap avatar payload to 400KB to ensure Firestore 1MB document limit is never exceeded
            if (avatarBase64.isNotBlank() && avatarBase64.length < 400000) {
                payload["avatarBase64"] = avatarBase64
            }

                // 1. Sync to hs_guardian_profiles
                if (cleanCode.isNotBlank()) {
                    db.collection("hs_guardian_profiles")
                        .document(cleanCode)
                        .set(payload, SetOptions.merge())
                        .addOnSuccessListener {
                            Log.d(TAG, "Successfully synced Guardian profile for childCode: $cleanCode -> Name: $cleanName")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to sync Guardian profile for childCode: $cleanCode", e)
                        }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing Guardian profile to Firestore", e)
        }
    }

    /**
     * Listens to real-time Guardian profile updates (name, avatar Base64) from Cloud Firestore.
     */
    fun listenGuardianProfileFromCloud(
        context: Context,
        childCode: String,
        onProfileUpdated: (GuardianProfileCloudData) -> Unit
    ): ListenerRegistration? {
        val cleanCode = childCode.trim().uppercase()
        if (cleanCode.isBlank()) return null
        val db = getDb() ?: return null

        return try {
            val processDoc: (com.google.firebase.firestore.DocumentSnapshot) -> Unit = { doc ->
                val guardianName = doc.getString("guardianName") ?: ""
                val email = doc.getString("email") ?: ""
                val avatarBase64 = doc.getString("avatarBase64") ?: ""
                val isConnected = doc.getBoolean("isConnected") ?: true
                val updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis()

                val isSarah = AuthManager.isSarahName(guardianName)
                val isInvalid = guardianName.isBlank() || guardianName.equals("User", ignoreCase = true) ||
                        guardianName.equals("Child", ignoreCase = true) || isSarah

                if (isInvalid) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onProfileUpdated(GuardianProfileCloudData(guardianName = "", childCode = cleanCode, isConnected = false))
                    }
                } else {
                    val profile = GuardianProfileCloudData(
                        guardianName = guardianName,
                        childCode = doc.getString("childCode") ?: cleanCode,
                        email = email,
                        avatarBase64 = avatarBase64,
                        isConnected = isConnected,
                        updatedAt = updatedAt
                    )

                    if (avatarBase64.isNotBlank()) {
                        FirebaseStorageHelper.downloadAndCachePhoto(context, "guardian", avatarBase64) { downloaded ->
                            if (downloaded != null) {
                                ProfileImageManager.saveProfileImage(context, downloaded, key = "guardian")
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    onProfileUpdated(profile)
                                }
                            }
                        }
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onProfileUpdated(profile)
                    }
                }
            }


            db.collection("hs_guardian_profiles")
                .document(cleanCode)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null || !snapshot.exists()) {
                        return@addSnapshotListener
                    }
                    processDoc(snapshot)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error listening to Guardian profile from Firestore", e)
            null
        }
    }


    /**
     * Uploads the child's live GPS coordinates and resolved address to Cloud Firestore hs_locations.
     */
    fun uploadChildLocation(childCode: String, childName: String, latitude: Double, longitude: Double, address: String) {
        val cleanCode = childCode.trim().uppercase()
        if (cleanCode.isBlank()) return
        val db = getDb() ?: return

        try {
            val payload = mapOf(
                "childCode" to cleanCode,
                "childName" to childName.trim().ifBlank { "Child" },
                "latitude" to latitude,
                "longitude" to longitude,
                "address" to address,
                "timestamp" to System.currentTimeMillis()
            )

            db.collection(COLLECTION_LOCATIONS)
                .document(cleanCode)
                .set(payload, SetOptions.merge())
                .addOnSuccessListener {
                    Log.d(TAG, "Uploaded live location for child $cleanCode ($latitude, $longitude)")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to upload live location for $cleanCode", e)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading live child location", e)
        }
    }

    /**
     * Subscribes to real-time location updates for a specific child device from Cloud Firestore.
     */
    fun listenChildLocation(
        childCode: String,
        onLocationUpdated: (ChildLocationCloudData) -> Unit
    ): ListenerRegistration? {
        val cleanCode = childCode.trim().uppercase()
        if (cleanCode.isBlank()) return null
        val db = getDb() ?: return null

        return try {
            db.collection(COLLECTION_LOCATIONS)
                .document(cleanCode)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.e(TAG, "Error listening to location for $cleanCode", error)
                        return@addSnapshotListener
                    }
                    if (snapshot == null || !snapshot.exists()) return@addSnapshotListener

                    val lat = snapshot.getDouble("latitude") ?: return@addSnapshotListener
                    val lng = snapshot.getDouble("longitude") ?: return@addSnapshotListener
                    val addr = snapshot.getString("address") ?: ""
                    val cName = snapshot.getString("childName") ?: "Child"
                    val ts = snapshot.getLong("timestamp") ?: System.currentTimeMillis()

                    val locData = ChildLocationCloudData(
                        childCode = cleanCode,
                        childName = cName,
                        latitude = lat,
                        longitude = lng,
                        address = addr,
                        timestamp = ts
                    )
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onLocationUpdated(locData)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up child location listener", e)
            null
        }
    }

    /**
     * Uploads the latest screen time and lock state for a child to Firestore.
     */
    fun syncScreenTimeToCloud(
        childCode: String,
        remainingSeconds: Int,
        isLocked: Boolean,
        totalAllowance: Int,
        usedSeconds: Int = (totalAllowance - remainingSeconds).coerceAtLeast(0)
    ) {
        val cleanCode = childCode.trim().uppercase()
        if (cleanCode.isBlank()) return
        val db = getDb() ?: return

        try {
            val payload = mapOf(
                "childCode" to cleanCode,
                "remainingSeconds" to remainingSeconds,
                "isLocked" to isLocked,
                "totalAllowance" to totalAllowance,
                "usedSeconds" to usedSeconds,
                "updatedAt" to System.currentTimeMillis()
            )

            db.collection("hs_screentime")
                .document(cleanCode)
                .set(payload, SetOptions.merge())
                .addOnSuccessListener {
                    Log.d(TAG, "Screen time synced to Firestore for $cleanCode: rem=$remainingSeconds, locked=$isLocked, used=$usedSeconds")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing screen time to Firestore", e)
        }
    }

    /**
     * Subscribes to real-time screen time and lock changes for a child from Firestore.
     */
    fun listenScreenTimeFromCloud(
        childCode: String,
        onUpdate: (remainingSeconds: Int, isLocked: Boolean, totalAllowance: Int) -> Unit
    ): ListenerRegistration? {
        val cleanCode = childCode.trim().uppercase()
        if (cleanCode.isBlank()) return null
        val db = getDb() ?: return null

        return try {
            db.collection("hs_screentime")
                .document(cleanCode)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                    val rem = (snapshot.getLong("remainingSeconds") ?: 21600L).toInt()
                    val locked = snapshot.getBoolean("isLocked") ?: false
                    val tot = (snapshot.getLong("totalAllowance") ?: 21600L).toInt()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onUpdate(rem, locked, tot)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error listening to screen time from Firestore", e)
            null
        }
    }
}
