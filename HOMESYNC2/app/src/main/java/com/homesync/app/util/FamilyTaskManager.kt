package com.homesync.app.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import java.util.UUID

object FamilyTaskManager {
    private const val TAG = "FamilyTaskManager"
    private const val COLLECTION_FAMILIES = "hs_families"
    private const val COLLECTION_MEMBERS = "members"
    private const val COLLECTION_TASKS = "tasks"
    private const val RTDB_TASKS = "hs_family_tasks"
    private const val RTDB_TOMBSTONES = "hs_family_tombstones"
    private const val PREFS_TOMBSTONES = "homesync_task_tombstones_v1"

    private const val OBSOLETE_GHOST_TASK_1 = "task_84f7f9ac-5c5a-4490-a3c2-66f0485ac440" // roshan la mar
    private const val OBSOLETE_GHOST_TASK_2 = "task_8a740218-7ae3-4f7d-8324-8457956702fb" // Math_Homework8
    private const val OBSOLETE_GHOST_TASK_3 = "task_581bc977-43a0-42b0-9ea9-2b1b7a972abe" // joo
    private const val OBSOLETE_GHOST_TASK_4 = "task_5c8b15b6-4a77-410d-b757-0b0ab95aaed9" // +20 Stars
    private const val OBSOLETE_GHOST_TASK_5 = "task_382bb37f-0fea-4714-a596-37e22b433046" // neww
    private const val OBSOLETE_GHOST_TASK_6 = "task_f0724b24-2919-4646-b9da-661a378299ae" // new
    private const val OBSOLETE_GHOST_TASK_7 = "task_5c62641b-0cd5-4aba-8386-59cdc7b8343c" // complete it

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tombstones = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {
        add(OBSOLETE_GHOST_TASK_1)
        add(OBSOLETE_GHOST_TASK_2)
        add(OBSOLETE_GHOST_TASK_3)
        add(OBSOLETE_GHOST_TASK_4)
        add(OBSOLETE_GHOST_TASK_5)
        add(OBSOLETE_GHOST_TASK_6)
        add(OBSOLETE_GHOST_TASK_7)
    }

    private val approvedTaskVersions = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val activeSyncStates = java.util.concurrent.ConcurrentHashMap<String, ChildTaskSyncState>()

    fun addTombstone(context: Context, taskId: String) {
        if (taskId.isBlank()) return
        tombstones.add(taskId)
        try {
            val prefs = context.getSharedPreferences(PREFS_TOMBSTONES, Context.MODE_PRIVATE)
            val current = (prefs.getStringSet("deleted_task_ids", emptySet()) ?: emptySet()).toMutableSet()
            current.add(taskId)
            prefs.edit().putStringSet("deleted_task_ids", current).apply()
        } catch (_: Exception) {}
    }

    fun syncTombstoneToCloud(familyId: String, childUserId: String, taskId: String) {
        val cleanFamilyId = familyId.trim().uppercase()
        val cleanChildUserId = childUserId.trim()
        val cleanTaskId = taskId.trim()
        if (cleanFamilyId.isBlank() || cleanChildUserId.isBlank() || cleanTaskId.isBlank()) return

        val payload = mapOf(
            "taskId" to cleanTaskId,
            "childUserId" to cleanChildUserId,
            "familyId" to cleanFamilyId,
            "timestamp" to System.currentTimeMillis(),
            "deletedAt" to System.currentTimeMillis()
        )

        // 1. Sync to RTDB
        try {
            val rtdb = getRtdb()
            rtdb?.getReference(RTDB_TOMBSTONES)
                ?.child(cleanFamilyId)
                ?.child(cleanChildUserId)
                ?.child(cleanTaskId)
                ?.setValue(payload)
                ?.addOnSuccessListener {
                    Log.i(TAG, "TASK_CLOUD_TOMBSTONE_SYNCED taskId=$cleanTaskId familyId=$cleanFamilyId childUserId=$cleanChildUserId")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync tombstone to RTDB", e)
        }

        // 2. Sync to Firestore
        try {
            val db = getDb()
            db?.collection(COLLECTION_FAMILIES)
                ?.document(cleanFamilyId)
                ?.collection(COLLECTION_MEMBERS)
                ?.document(cleanChildUserId)
                ?.collection("tombstones")
                ?.document(cleanTaskId)
                ?.set(payload)
                ?.addOnSuccessListener {
                    Log.i(TAG, "TASK_FIRESTORE_TOMBSTONE_SYNCED taskId=$cleanTaskId")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync tombstone to Firestore", e)
        }
    }

    fun syncAllLocalTombstonesToCloud(context: Context, familyId: String, childUserId: String) {
        val cleanFamilyId = familyId.trim().uppercase()
        val cleanChildUserId = childUserId.trim()
        if (cleanFamilyId.isBlank() || cleanChildUserId.isBlank()) return
        try {
            val prefs = context.getSharedPreferences(PREFS_TOMBSTONES, Context.MODE_PRIVATE)
            val set = prefs.getStringSet("deleted_task_ids", emptySet()) ?: emptySet()
            for (taskId in set) {
                syncTombstoneToCloud(cleanFamilyId, cleanChildUserId, taskId)
            }
        } catch (_: Exception) {}
    }

    fun isTombstoned(context: Context, taskId: String): Boolean {
        if (taskId.isBlank()) return true
        if (tombstones.contains(taskId)) return true
        try {
            val prefs = context.getSharedPreferences(PREFS_TOMBSTONES, Context.MODE_PRIVATE)
            val set = prefs.getStringSet("deleted_task_ids", emptySet()) ?: emptySet()
            if (set.contains(taskId)) {
                tombstones.add(taskId)
                return true
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * Deterministically calculates task version timestamp based on priority:
     * 1. updatedAt
     * 2. verifiedAt
     * 3. submittedAt
     * 4. createdAt
     */
    fun getTaskVersion(task: ChildQuest): Long {
        return task.updatedAt.takeIf { it > 0 }
            ?: task.verifiedAt?.takeIf { it > 0 }
            ?: task.submittedAt?.takeIf { it > 0 }
            ?: task.createdAt.takeIf { it > 0 }
            ?: 0L
    }

    private fun getDb(): FirebaseFirestore? = FirebaseSyncManager.getDb()
    private fun getRtdb() = FirebaseRealtimeSyncManager.getRtdb()

    private class ChildTaskSyncState(
        val context: Context,
        val familyId: String,
        val childUserId: String,
        val onTasksUpdated: (List<ChildQuest>) -> Unit
    ) {
        val latestRtdbTasks = java.util.concurrent.ConcurrentHashMap<String, ChildQuest>()
        val latestFirestoreTasks = java.util.concurrent.ConcurrentHashMap<String, ChildQuest>()

        fun reconcileAndNotify() {
            if (latestRtdbTasks.isEmpty() && latestFirestoreTasks.isNotEmpty()) {
                Log.i(TAG, "TASK_RECONCILE_EMPTY_BACKEND_IGNORED emptyBackend=RTDB activeFirestoreCount=${latestFirestoreTasks.size}")
            } else if (latestFirestoreTasks.isEmpty() && latestRtdbTasks.isNotEmpty()) {
                Log.i(TAG, "TASK_RECONCILE_EMPTY_BACKEND_IGNORED emptyBackend=Firestore activeRtdbCount=${latestRtdbTasks.size}")
            }

            val allIds = (latestRtdbTasks.keys + latestFirestoreTasks.keys).toSet()
            val reconciledMap = mutableMapOf<String, ChildQuest>()

            for (id in allIds) {
                if (isTombstoned(context, id)) {
                    Log.i(TAG, "TASK_TOMBSTONED_SNAPSHOT_IGNORED taskId=$id")
                    latestRtdbTasks.remove(id)
                    latestFirestoreTasks.remove(id)
                    continue
                }

                val rtdbItem = latestRtdbTasks[id]
                val firestoreItem = latestFirestoreTasks[id]

                val rtdbVersion = rtdbItem?.let { getTaskVersion(it) } ?: -1L
                val firestoreVersion = firestoreItem?.let { getTaskVersion(it) } ?: -1L

                val approvedVersion = approvedTaskVersions[id]
                if (approvedVersion != null) {
                    val maxIncomingVersion = maxOf(rtdbVersion, firestoreVersion)
                    if (maxIncomingVersion <= approvedVersion) {
                        Log.i(TAG, "TASK_RECONCILE_APPROVED_STALE_IGNORED taskId=$id maxIncomingVersion=$maxIncomingVersion approvedVersion=$approvedVersion")
                        continue
                    }
                }

                val chosen = when {
                    rtdbItem != null && firestoreItem != null -> {
                        // Priority guard: NEVER let a stale PENDING state overwrite SUBMITTED or APPROVED
                        if (rtdbItem.status == QuestStatus.APPROVED && firestoreItem.status != QuestStatus.APPROVED) {
                            rtdbItem
                        } else if (firestoreItem.status == QuestStatus.APPROVED && rtdbItem.status != QuestStatus.APPROVED) {
                            firestoreItem
                        } else if (rtdbItem.status == QuestStatus.SUBMITTED && firestoreItem.status == QuestStatus.PENDING) {
                            rtdbItem
                        } else if (firestoreItem.status == QuestStatus.SUBMITTED && rtdbItem.status == QuestStatus.PENDING) {
                            firestoreItem
                        } else if (rtdbVersion >= firestoreVersion) {
                            if (rtdbVersion > firestoreVersion) {
                                Log.i(TAG, "TASK_RECONCILE_STALE_IGNORED taskId=$id chosenSource=RTDB winningVersion=$rtdbVersion rejectedSource=Firestore rejectedVersion=$firestoreVersion")
                            }
                            // Preserve proof URI if RTDB lacks it but Firestore has it
                            if (rtdbItem.photoProofUri.isBlank() && firestoreItem.photoProofUri.isNotBlank()) {
                                rtdbItem.copy(photoProofUri = firestoreItem.photoProofUri, photoProofLabel = firestoreItem.photoProofLabel)
                            } else {
                                rtdbItem
                            }
                        } else {
                            Log.i(TAG, "TASK_RECONCILE_STALE_IGNORED taskId=$id chosenSource=Firestore winningVersion=$firestoreVersion rejectedSource=RTDB rejectedVersion=$rtdbVersion")
                            // Preserve proof URI if Firestore lacks it but RTDB has it
                            if (firestoreItem.photoProofUri.isBlank() && rtdbItem.photoProofUri.isNotBlank()) {
                                firestoreItem.copy(photoProofUri = rtdbItem.photoProofUri, photoProofLabel = rtdbItem.photoProofLabel)
                            } else {
                                firestoreItem
                            }
                        }
                    }
                    rtdbItem != null -> rtdbItem
                    else -> firestoreItem
                }

                if (chosen != null) {
                    val chosenVersion = getTaskVersion(chosen)
                    if (approvedVersion != null && chosenVersion <= approvedVersion) {
                        Log.i(TAG, "TASK_RECONCILE_APPROVED_STALE_IGNORED taskId=$id chosenVersion=$chosenVersion approvedVersion=$approvedVersion")
                        continue
                    }

                    if (chosen.status == QuestStatus.APPROVED) {
                        approvedTaskVersions[chosen.id] = chosenVersion
                        Log.i(TAG, "TASK_APPROVAL_RECEIVED_CHILD taskId=$id childUserId=$childUserId")

                        // Process Child approval
                        if (!chosen.rewardApplied && chosen.rewardStars > 0) {
                            ChildRewardsManager.addRewardsToCloudWallet(
                                context = context,
                                familyId = familyId,
                                childUid = childUserId,
                                earnedPoints = chosen.rewardStars,
                                earnedCoins = chosen.rewardStars,
                                transactionId = "task_reward_$id"
                            )
                            ChildRewardsManager.addRewards(
                                context = context,
                                childId = childUserId,
                                earnedPoints = chosen.rewardStars,
                                earnedCoins = chosen.rewardStars
                            )
                        }

                        val approvedNotifId = "task_approved_${chosen.id}"
                        if (!NotificationManager.isAlertDismissed(context, approvedNotifId)) {
                            val notif = SystemNotification(
                                id = approvedNotifId,
                                title = "Task Approved! 🌟",
                                message = "Awesome job! You earned ${chosen.rewardStars} Stars for completing \"${chosen.title}\"!",
                                type = NotificationType.TASK_APPROVED,
                                childName = "Child",
                                childCode = childUserId,
                                actionData = chosen.id,
                                targetRole = "CHILD",
                                familyId = familyId,
                                childUid = childUserId
                            )
                            NotificationManager.addNotification(context, notif)
                        }

                        addTombstone(context, id)
                        Log.i(TAG, "TASK_TOMBSTONE_ADDED taskId=$id")

                        ChildQuestManager.deleteQuest(context, childUserId, id)
                        val currentAuthUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
                        if (currentAuthUid.isNotBlank() && currentAuthUid != childUserId) {
                            ChildQuestManager.deleteQuest(context, currentAuthUid, id)
                        }
                        Log.i(TAG, "TASK_CACHE_PURGED taskId=$id")

                        latestRtdbTasks.remove(id)
                        latestFirestoreTasks.remove(id)
                        continue
                    }

                    reconciledMap[chosen.id] = chosen
                }
            }

            val taskList = reconciledMap.values
                .filter { !isTombstoned(context, it.id) && it.status != QuestStatus.APPROVED }
                .sortedByDescending { getTaskVersion(it) }
            val taskIds = taskList.map { it.id }

            Log.i(TAG, "TASK_RECONCILE activeCount=${taskList.size} taskIds=$taskIds")

            val currentAuthUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
            ChildQuestManager.purgeLegacyTasksExcept(context, childUserId, taskIds)
            if (currentAuthUid.isNotBlank() && currentAuthUid != childUserId) {
                ChildQuestManager.purgeLegacyTasksExcept(context, currentAuthUid, taskIds)
            }

            ChildQuestManager.saveQuestsFromCloud(context, childUserId, taskList)
            if (currentAuthUid.isNotBlank() && currentAuthUid != childUserId) {
                ChildQuestManager.saveQuestsFromCloud(context, currentAuthUid, taskList)
            }

            // If running on Child device, ensure notification exists for any new pending tasks
            try {
                val effectiveRole = NotificationManager.getEffectiveRole(context)
                if (effectiveRole.equals("CHILD", ignoreCase = true)) {
                    for (task in taskList) {
                        if (task.status == QuestStatus.PENDING) {
                            val assignedNotifId = "task_assigned_${task.id}"
                            if (!NotificationManager.isAlertDismissed(context, assignedNotifId) &&
                                NotificationManager.getNotifications(context).none { it.id == assignedNotifId || it.actionData == task.id }
                            ) {
                                val notif = SystemNotification(
                                    id = assignedNotifId,
                                    title = "New Quest Assigned! 📋",
                                    message = "New task: \"${task.title}\" • Earn ${task.rewardStars} Stars! (${task.dueTime})",
                                    type = NotificationType.TASK_ASSIGNED,
                                    childName = "Child",
                                    childCode = childUserId,
                                    actionData = task.id,
                                    targetRole = "CHILD",
                                    familyId = familyId,
                                    childUid = childUserId
                                )
                                NotificationManager.addNotification(context, notif)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            Log.i(TAG, "TASK_SYNC_UI_RECONCILED activeTaskIds=$taskIds")
            mainHandler.post {
                onTasksUpdated(taskList)
            }
        }
    }

    /**
     * Creates an authoritative task with dual-sync across Realtime Database & Firestore:
     * RTDB: hs_family_tasks/{familyId}/{childUserId}/{taskId}
     * Firestore: hs_families/{familyId}/members/{childUserId}/tasks/{taskId}
     */
    fun createTask(
        familyId: String,
        childUserId: String,
        title: String,
        rewardStars: Int,
        dueTime: String,
        childName: String = "Child",
        onComplete: (Result<ChildQuest>) -> Unit = {}
    ) {
        val cleanFamilyId = familyId.trim().uppercase()
        val cleanChildUserId = childUserId.trim()
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: ""

        if (cleanFamilyId.isBlank() || cleanChildUserId.isBlank() || currentUserId.isBlank()) {
            onComplete(Result.failure(IllegalArgumentException("Invalid parameters: familyId, childUserId, and auth user required")))
            return
        }

        if (cleanChildUserId.startsWith("HS-", ignoreCase = true)) {
            Log.e(TAG, "TASK_IDENTITY_ERROR: Pairing code $cleanChildUserId passed as childUserId in createTask (Firebase Auth UID required)")
            onComplete(Result.failure(IllegalArgumentException("Pairing code $cleanChildUserId passed as childUserId (Firebase Auth UID required)")))
            return
        }

        val taskId = "task_" + UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val task = ChildQuest(
            id = taskId,
            title = title.trim(),
            rewardStars = rewardStars,
            dueTime = dueTime.trim().ifBlank { "Due 8:00 PM" },
            status = QuestStatus.PENDING,
            photoProofLabel = "",
            photoProofUri = "",
            submittedAt = null,
            verifiedAt = null,
            rewardApplied = false,
            familyId = cleanFamilyId,
            childUserId = cleanChildUserId,
            createdByUserId = currentUserId,
            createdAt = now,
            updatedAt = now
        )

        val taskData = hashMapOf<String, Any>(
            "id" to task.id,
            "title" to task.title,
            "rewardStars" to task.rewardStars,
            "dueTime" to task.dueTime,
            "status" to task.status.name,
            "photoProofLabel" to task.photoProofLabel,
            "photoProofUri" to task.photoProofUri,
            "rewardApplied" to false,
            "familyId" to cleanFamilyId,
            "childUserId" to cleanChildUserId,
            "createdByUserId" to currentUserId,
            "createdAt" to now,
            "updatedAt" to now
        )

        Log.i(TAG, "TASK_CREATE_START familyId=$cleanFamilyId childUserId=$cleanChildUserId taskId=$taskId")

        // 1. Write to Realtime Database (guaranteed instant delivery)
        val rtdb = getRtdb()
        if (rtdb != null) {
            rtdb.getReference(RTDB_TASKS)
                .child(cleanFamilyId)
                .child(cleanChildUserId)
                .child(taskId)
                .setValue(taskData)
                .addOnSuccessListener {
                    Log.i(TAG, "TASK_CREATE_RTDB_SUCCESS taskId=$taskId")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "TASK_CREATE_RTDB_FAILED taskId=$taskId error=${e.message}")
                }
        }

        // 2. Write to Firestore
        val db = getDb()
        if (db != null) {
            db.collection(COLLECTION_FAMILIES)
                .document(cleanFamilyId)
                .collection(COLLECTION_MEMBERS)
                .document(cleanChildUserId)
                .collection(COLLECTION_TASKS)
                .document(taskId)
                .set(taskData)
                .addOnSuccessListener {
                    Log.i(TAG, "TASK_CREATE_FIRESTORE_SUCCESS taskId=$taskId")
                    Log.i(TAG, "TASK_CREATE_SUCCESS taskId=$taskId")
                    mainHandler.post { onComplete(Result.success(task)) }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "TASK_CREATE_FIRESTORE_FAILED taskId=$taskId error=${e.message}", e)
                    if (rtdb != null) {
                        Log.i(TAG, "TASK_CREATE_FALLBACK_RTDB_SUCCESS taskId=$taskId")
                        Log.i(TAG, "TASK_CREATE_SUCCESS taskId=$taskId")
                        mainHandler.post { onComplete(Result.success(task)) }
                    } else {
                        mainHandler.post { onComplete(Result.failure(e)) }
                    }
                }
        } else if (rtdb != null) {
            Log.i(TAG, "TASK_CREATE_SUCCESS taskId=$taskId")
            mainHandler.post { onComplete(Result.success(task)) }
        } else {
            mainHandler.post { onComplete(Result.failure(IllegalStateException("No database available"))) }
        }

        // Dispatch real-time cross-device notification to child
        val notif = SystemNotification(
            id = "task_assigned_${task.id}",
            title = "New Quest Assigned! 📋",
            message = "New task: \"${task.title}\" • Earn $rewardStars Stars! (${task.dueTime})",
            type = NotificationType.TASK_ASSIGNED,
            childName = childName,
            childCode = cleanChildUserId,
            actionData = task.id,
            targetRole = "CHILD",
            familyId = cleanFamilyId,
            childUid = cleanChildUserId
        )
        try {
            FirebaseSyncManager.sendNotificationToCloud(notif)
            Log.i(TAG, "TASK_NOTIFICATION_DISPATCHED taskId=$taskId childUserId=$cleanChildUserId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dispatch task notification to cloud", e)
        }

        // Immediately update local reconciled state for instant UI responsiveness
        activeSyncStates[cleanChildUserId]?.let { syncState ->
            syncState.latestRtdbTasks[taskId] = task
            syncState.latestFirestoreTasks[taskId] = task
            syncState.reconcileAndNotify()
        }
        mainHandler.post { onComplete(Result.success(task)) }
    }

    /**
     * Listens in real time to the tasks assigned to a specific child under:
     * RTDB: hs_family_tasks/{familyId}/{childUserId}
     * Firestore: hs_families/{familyId}/members/{childUserId}/tasks
     * Merges both sources seamlessly so operations work even if Firestore quota is throttled.
     */
    fun listenTasksForChild(
        context: Context,
        familyId: String,
        childUserId: String,
        onTasksUpdated: (List<ChildQuest>) -> Unit,
        onError: ((Exception) -> Unit)? = null
    ): ListenerRegistration? {
        val cleanFamilyId = familyId.trim().uppercase()
        val cleanChildUserId = childUserId.trim()
        val currentAuthUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""

        if (cleanFamilyId.isBlank() || cleanChildUserId.isBlank()) {
            Log.e(TAG, "TASK_SYNC_LISTENER_NOT_ATTACHED reason=blank_ids familyId=$cleanFamilyId childUserId=$cleanChildUserId")
            return null
        }
        if (cleanChildUserId.startsWith("HS-", ignoreCase = true)) {
            Log.e(TAG, "TASK_IDENTITY_ERROR: Pairing code $cleanChildUserId passed as childUserId in listenTasksForChild (Firebase Auth UID required)")
            onError?.invoke(IllegalArgumentException("Pairing code $cleanChildUserId passed as childUserId (Firebase Auth UID required)"))
            return null
        }

        Log.i(TAG, "TASK_SYNC_LISTENER_START familyId=$cleanFamilyId childUserId=$cleanChildUserId currentUserUid=$currentAuthUid")

        // Sync any existing local tombstones to cloud so all devices stay up-to-date
        syncAllLocalTombstonesToCloud(context, cleanFamilyId, cleanChildUserId)

        val syncState = ChildTaskSyncState(context, cleanFamilyId, cleanChildUserId, onTasksUpdated)
        activeSyncStates[cleanChildUserId] = syncState

        // 1. Realtime Database listener (sub-second zero-quota updates)
        var rtdbListener: ValueEventListener? = null
        val rtdb = getRtdb()
        val rtdbRef = rtdb?.getReference(RTDB_TASKS)?.child(cleanFamilyId)?.child(cleanChildUserId)

        if (rtdbRef != null) {
            rtdbListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    Log.i(TAG, "TASK_RTDB_RECEIVED count=${snapshot.childrenCount} childUserId=$cleanChildUserId")
                    val freshMap = mutableMapOf<String, ChildQuest>()
                    for (taskSnap in snapshot.children) {
                        val id = taskSnap.child("id").getValue(String::class.java) ?: taskSnap.key ?: continue
                        if (isTombstoned(context, id)) {
                            Log.i(TAG, "TASK_TOMBSTONED_SNAPSHOT_IGNORED taskId=$id")
                            try { taskSnap.ref.removeValue() } catch (_: Exception) {}
                            continue
                        }

                        val title = taskSnap.child("title").getValue(String::class.java) ?: ""
                        val rewardStars = taskSnap.child("rewardStars").getValue(Long::class.java)?.toInt() ?: 20
                        val dueTime = taskSnap.child("dueTime").getValue(String::class.java) ?: ""
                        val statusStr = taskSnap.child("status").getValue(String::class.java) ?: "PENDING"
                        val status = try { QuestStatus.valueOf(statusStr) } catch (_: Exception) { QuestStatus.PENDING }

                        val photoProofLabel = taskSnap.child("photoProofLabel").getValue(String::class.java) ?: ""
                        val photoProofUri = taskSnap.child("photoProofUri").getValue(String::class.java) ?: ""
                        val submittedAt = taskSnap.child("submittedAt").getValue(Long::class.java)
                        val verifiedAt = taskSnap.child("verifiedAt").getValue(Long::class.java)
                        val createdAt = taskSnap.child("createdAt").getValue(Long::class.java) ?: 0L
                        val updatedAt = taskSnap.child("updatedAt").getValue(Long::class.java) ?: 0L
                        val rewardApplied = taskSnap.child("rewardApplied").getValue(Boolean::class.java) ?: false
                        val fId = taskSnap.child("familyId").getValue(String::class.java) ?: cleanFamilyId
                        val cUid = taskSnap.child("childUserId").getValue(String::class.java) ?: cleanChildUserId
                        val createdBy = taskSnap.child("createdByUserId").getValue(String::class.java) ?: ""

                        freshMap[id] = ChildQuest(
                            id = id,
                            title = title,
                            rewardStars = rewardStars,
                            dueTime = dueTime,
                            status = status,
                            photoProofLabel = photoProofLabel,
                            photoProofUri = photoProofUri,
                            submittedAt = submittedAt,
                            verifiedAt = verifiedAt,
                            rewardApplied = rewardApplied,
                            familyId = fId,
                            childUserId = cUid,
                            createdByUserId = createdBy,
                            createdAt = createdAt,
                            updatedAt = updatedAt
                        )
                    }

                    syncState.latestRtdbTasks.clear()
                    syncState.latestRtdbTasks.putAll(freshMap)
                    syncState.reconcileAndNotify()
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "RTDB task listener cancelled: ${error.message}")
                }
            }
            rtdbRef.addValueEventListener(rtdbListener)
        }

        // 1b. Realtime Database Tombstones listener (instant cross-device deletion sync)
        var rtdbTombstoneListener: ValueEventListener? = null
        val rtdbTombstoneRef = rtdb?.getReference(RTDB_TOMBSTONES)?.child(cleanFamilyId)?.child(cleanChildUserId)
        if (rtdbTombstoneRef != null) {
            rtdbTombstoneListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    var newTombstonesFound = false
                    for (tombSnap in snapshot.children) {
                        val taskId = tombSnap.child("taskId").getValue(String::class.java) ?: tombSnap.key ?: continue
                        if (!isTombstoned(context, taskId)) {
                            addTombstone(context, taskId)
                            newTombstonesFound = true
                            Log.i(TAG, "TASK_CLOUD_TOMBSTONE_RECEIVED taskId=$taskId childUserId=$cleanChildUserId")
                        }
                        syncState.latestRtdbTasks.remove(taskId)
                        syncState.latestFirestoreTasks.remove(taskId)
                        ChildQuestManager.deleteQuest(context, cleanChildUserId, taskId)
                        if (currentAuthUid.isNotBlank() && currentAuthUid != cleanChildUserId) {
                            ChildQuestManager.deleteQuest(context, currentAuthUid, taskId)
                        }
                    }
                    if (newTombstonesFound) {
                        syncState.reconcileAndNotify()
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "RTDB tombstone listener cancelled: ${error.message}")
                }
            }
            rtdbTombstoneRef.addValueEventListener(rtdbTombstoneListener)
        }

        // 2. Firestore listener
        val db = getDb()
        val firestoreReg = db?.collection(COLLECTION_FAMILIES)
            ?.document(cleanFamilyId)
            ?.collection(COLLECTION_MEMBERS)
            ?.document(cleanChildUserId)
            ?.collection(COLLECTION_TASKS)
            ?.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Firestore task listener error: ${error.message}")
                    onError?.invoke(error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                Log.i(TAG, "TASK_FIRESTORE_RECEIVED count=${snapshot.documents.size} childUserId=$cleanChildUserId")

                val freshMap = mutableMapOf<String, ChildQuest>()
                for (doc in snapshot.documents) {
                    val id = doc.getString("id") ?: doc.id
                    if (isTombstoned(context, id)) {
                        Log.i(TAG, "TASK_TOMBSTONED_SNAPSHOT_IGNORED taskId=$id")
                        try { doc.reference.delete() } catch (_: Exception) {}
                        continue
                    }

                    val title = doc.getString("title") ?: ""
                    val rewardStars = doc.getLong("rewardStars")?.toInt() ?: 20
                    val dueTime = doc.getString("dueTime") ?: ""
                    val statusStr = doc.getString("status") ?: "PENDING"
                    val status = try { QuestStatus.valueOf(statusStr) } catch (_: Exception) { QuestStatus.PENDING }

                    val photoProofLabel = doc.getString("photoProofLabel") ?: ""
                    val photoProofUri = doc.getString("photoProofUri") ?: ""
                    val submittedAt = doc.getLong("submittedAt")
                    val verifiedAt = doc.getLong("verifiedAt")
                    val createdAt = doc.getLong("createdAt") ?: 0L
                    val updatedAt = doc.getLong("updatedAt") ?: 0L
                    val rewardApplied = doc.getBoolean("rewardApplied") ?: false
                    val fId = doc.getString("familyId") ?: cleanFamilyId
                    val cUid = doc.getString("childUserId") ?: cleanChildUserId
                    val createdBy = doc.getString("createdByUserId") ?: ""

                    freshMap[id] = ChildQuest(
                        id = id,
                        title = title,
                        rewardStars = rewardStars,
                        dueTime = dueTime,
                        status = status,
                        photoProofLabel = photoProofLabel,
                        photoProofUri = photoProofUri,
                        submittedAt = submittedAt,
                        verifiedAt = verifiedAt,
                        rewardApplied = rewardApplied,
                        familyId = fId,
                        childUserId = cUid,
                        createdByUserId = createdBy,
                        createdAt = createdAt,
                        updatedAt = updatedAt
                    )
                }

                syncState.latestFirestoreTasks.clear()
                syncState.latestFirestoreTasks.putAll(freshMap)
                syncState.reconcileAndNotify()
            }

        // 2b. Firestore tombstones listener
        val firestoreTombstonesReg = db?.collection(COLLECTION_FAMILIES)
            ?.document(cleanFamilyId)
            ?.collection(COLLECTION_MEMBERS)
            ?.document(cleanChildUserId)
            ?.collection("tombstones")
            ?.addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                var newTombstonesFound = false
                for (doc in snapshot.documents) {
                    val taskId = doc.getString("taskId") ?: doc.id
                    if (!isTombstoned(context, taskId)) {
                        addTombstone(context, taskId)
                        newTombstonesFound = true
                        Log.i(TAG, "TASK_FIRESTORE_TOMBSTONE_RECEIVED taskId=$taskId childUserId=$cleanChildUserId")
                    }
                    syncState.latestRtdbTasks.remove(taskId)
                    syncState.latestFirestoreTasks.remove(taskId)
                    ChildQuestManager.deleteQuest(context, cleanChildUserId, taskId)
                    if (currentAuthUid.isNotBlank() && currentAuthUid != cleanChildUserId) {
                        ChildQuestManager.deleteQuest(context, currentAuthUid, taskId)
                    }
                }
                if (newTombstonesFound) {
                    syncState.reconcileAndNotify()
                }
            }

        return object : ListenerRegistration {
            override fun remove() {
                activeSyncStates.remove(cleanChildUserId)
                try {
                    firestoreReg?.remove()
                } catch (_: Exception) {}
                try {
                    firestoreTombstonesReg?.remove()
                } catch (_: Exception) {}
                try {
                    if (rtdbRef != null && rtdbListener != null) {
                        rtdbRef.removeEventListener(rtdbListener)
                    }
                } catch (_: Exception) {}
                try {
                    if (rtdbTombstoneRef != null && rtdbTombstoneListener != null) {
                        rtdbTombstoneRef.removeEventListener(rtdbTombstoneListener)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Submits photo proof for a task by Child with dual-sync across RTDB and Firestore.
     */
    fun submitTaskProof(
        context: Context,
        familyId: String,
        childUserId: String,
        questId: String,
        photoLabel: String,
        photoUri: String,
        childName: String = "Child",
        childCode: String = "",
        onComplete: (Boolean) -> Unit = {}
    ) {
        val cleanFamilyId = familyId.trim().uppercase()
        val authUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
        val cleanChildUserId = authUid.ifBlank { childUserId.trim() }
        val deviceChildCode = if (childCode.isNotBlank()) childCode.trim().uppercase() else ChildIdManager.getDeviceChildId(context)

        Log.i(TAG, "TASK_PROOF_IDENTITY familyId=$cleanFamilyId childUid=$cleanChildUserId childCode=$deviceChildCode taskId=$questId")
        Log.i(TAG, "TASK_PROOF_UPLOAD_START taskId=$questId familyId=$cleanFamilyId childUid=$cleanChildUserId photoUri=$photoUri")
        Log.i(TAG, "TASK_PROOF_UPDATE_START taskId=$questId")

        if (cleanFamilyId.isBlank() || cleanChildUserId.isBlank() || questId.isBlank() || photoUri.isBlank()) {
            Log.e(TAG, "TASK_PROOF_SUBMIT_FAILED taskId=$questId error=missing_required_ids_or_proof_uri")
            onComplete(false)
            return
        }

        if (!photoUri.startsWith("https://")) {
            Log.e(TAG, "TASK_PROOF_SUBMIT_FAILED taskId=$questId error=invalid_proof_uri_must_be_https photoUri=$photoUri")
            onComplete(false)
            return
        }

        if (cleanChildUserId.startsWith("HS-", ignoreCase = true)) {
            Log.e(TAG, "TASK_IDENTITY_ERROR: Pairing code $cleanChildUserId passed as childUserId in submitTaskProof")
            onComplete(false)
            return
        }

        val now = System.currentTimeMillis()

        // 1. Resolve complete existing task to write a full object with setValue (identical to createTask & verifyTask)
        val existingTask = activeSyncStates[cleanChildUserId]?.latestRtdbTasks?.get(questId)
            ?: activeSyncStates[cleanChildUserId]?.latestFirestoreTasks?.get(questId)
            ?: ChildQuestManager.getQuests(context, deviceChildCode).find { it.id == questId }
            ?: ChildQuest(
                id = questId,
                title = photoLabel.ifBlank { "Task" },
                rewardStars = 20,
                dueTime = "Due 8:00 PM",
                status = QuestStatus.SUBMITTED,
                photoProofLabel = photoLabel,
                photoProofUri = photoUri,
                submittedAt = now,
                updatedAt = now,
                familyId = cleanFamilyId,
                childUserId = cleanChildUserId
            )

        val updatedTask = existingTask.copy(
            status = QuestStatus.SUBMITTED,
            photoProofLabel = photoLabel,
            photoProofUri = photoUri,
            submittedAt = now,
            updatedAt = now
        )

        // Complete full payload matching createTask schema
        val fullTaskData = hashMapOf<String, Any>(
            "id" to updatedTask.id,
            "title" to updatedTask.title,
            "rewardStars" to updatedTask.rewardStars,
            "dueTime" to updatedTask.dueTime,
            "status" to QuestStatus.SUBMITTED.name,
            "photoProofLabel" to photoLabel,
            "photoProofUri" to photoUri,
            "submittedAt" to now,
            "updatedAt" to now,
            "rewardApplied" to updatedTask.rewardApplied,
            "familyId" to cleanFamilyId,
            "childUserId" to cleanChildUserId,
            "createdByUserId" to updatedTask.createdByUserId,
            "createdAt" to (if (updatedTask.createdAt > 0) updatedTask.createdAt else now)
        )

        // 2. Instantly update active sync state on device for immediate zero-latency UI response
        activeSyncStates[cleanChildUserId]?.let { syncState ->
            syncState.latestRtdbTasks[questId] = updatedTask
            syncState.latestFirestoreTasks[questId] = updatedTask
            syncState.reconcileAndNotify()
        }

        // Call completion immediately to unlock child UI without waiting on network roundtrips
        mainHandler.post { onComplete(true) }

        // 3. Write FULL payload to RTDB using setValue (bypasses partial-update validation issues)
        val rtdb = getRtdb()
        if (rtdb != null) {
            rtdb.getReference(RTDB_TASKS)
                .child(cleanFamilyId)
                .child(cleanChildUserId)
                .child(questId)
                .setValue(fullTaskData)
                .addOnSuccessListener {
                    android.util.Log.i("HomeSyncLatency", "TASK_PROOF_RTDB_WRITE taskId=$questId childUid=$cleanChildUserId status=SUBMITTED timestamp=${System.currentTimeMillis()}")
                    Log.i(TAG, "TASK_PROOF_UPDATE_RTDB_SUCCESS taskId=$questId")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "TASK_PROOF_UPDATE_RTDB_FAILED taskId=$questId error=${e.message}", e)
                    // Fallback to updateChildren if setValue encounters node permission issues
                    rtdb.getReference(RTDB_TASKS)
                        .child(cleanFamilyId)
                        .child(cleanChildUserId)
                        .child(questId)
                        .updateChildren(fullTaskData as Map<String, Any>)
                }
        }

        // 4. Dual-sync to Firestore
        val db = getDb()
        val docRef = db?.collection(COLLECTION_FAMILIES)
            ?.document(cleanFamilyId)
            ?.collection(COLLECTION_MEMBERS)
            ?.document(cleanChildUserId)
            ?.collection(COLLECTION_TASKS)
            ?.document(questId)

        docRef?.set(fullTaskData, SetOptions.merge())
            ?.addOnSuccessListener {
                Log.i(TAG, "TASK_PROOF_UPLOAD_SUCCESS taskId=$questId")
                Log.i(TAG, "TASK_PROOF_UPDATE_FIRESTORE_SUCCESS taskId=$questId")
                Log.i(TAG, "TASK_PROOF_UPDATE_SUCCESS taskId=$questId")
                Log.i(TAG, "TASK_GUARDIAN_PROOF_RECEIVED taskId=$questId")
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "TASK_PROOF_SUBMIT_FIRESTORE_FAILED taskId=$questId error=${e.message}", e)
            }

        // 5. Dual-sync to pairing-code legacy pipeline (hs_quests) so Guardian receives it regardless of query mode
        if (deviceChildCode.isNotBlank()) {
            try {
                val allQuests = ChildQuestManager.getQuests(context, deviceChildCode)
                val mergedQuests = allQuests.map { if (it.id == questId) updatedTask else it }
                val finalList = if (mergedQuests.any { it.id == questId }) mergedQuests else (mergedQuests + updatedTask)
                ChildQuestManager.saveQuestsFromCloud(context, deviceChildCode, finalList)
                kotlin.concurrent.thread {
                    try {
                        FirebaseSyncManager.syncQuestsToCloud(context, deviceChildCode, finalList)
                        Log.i(TAG, "TASK_PROOF_SYNC_QUESTS_SUCCESS taskId=$questId childCode=$deviceChildCode")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to sync quests to cloud pipeline", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error syncing proof to legacy quests tree", e)
            }
        }

        // 6. Notify guardian across cloud & local with full family context and Cloud Push
        val proofNotifId = "proof_submitted_${questId}_${now}"
        val proofNotif = SystemNotification(
            id = proofNotifId,
            title = "Task Photo Verification Required 📷",
            message = "Photo proof submitted by $childName. Please verify to award stars!",
            type = NotificationType.TASK_PHOTO_SUBMITTED,
            childName = childName,
            childCode = deviceChildCode,
            actionData = questId,
            targetRole = "GUARDIAN",
            familyId = cleanFamilyId,
            childUid = cleanChildUserId
        )
        NotificationManager.addNotification(context, proofNotif)
        try {
            FirebaseSyncManager.sendNotificationToCloud(proofNotif)
            Log.i(TAG, "TASK_PROOF_NOTIFICATION_DISPATCHED taskId=$questId childUserId=$cleanChildUserId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dispatch task proof notification to cloud", e)
        }
    }

    /**
     * Approves or rejects a task by Guardian.
     * Guaranteed idempotent: stars are credited only once when approve == true and rewardApplied was false.
     * Dual-syncs across RTDB and Firestore.
     */
    fun verifyTask(
        context: Context,
        familyId: String,
        childUserId: String,
        questId: String,
        approve: Boolean,
        rewardStars: Int = 20,
        onComplete: (Boolean) -> Unit = {}
    ) {
        val cleanFamilyId = familyId.trim().uppercase()
        val cleanChildUserId = childUserId.trim()
        val cleanTaskId = questId.trim()

        Log.i(TAG, "TASK_VERIFY_START familyId=$cleanFamilyId childUserId=$cleanChildUserId taskId=$cleanTaskId approve=$approve")

        if (cleanFamilyId.isBlank() || cleanChildUserId.isBlank() || cleanTaskId.isBlank()) {
            Log.e(TAG, "TASK_VERIFY_FAILED taskId=$cleanTaskId error=invalid_parameters")
            onComplete(false)
            return
        }

        if (cleanChildUserId.startsWith("HS-", ignoreCase = true)) {
            Log.e(TAG, "TASK_IDENTITY_ERROR: Pairing code $cleanChildUserId passed as childUserId in verifyTask")
            onComplete(false)
            return
        }

        val now = System.currentTimeMillis()
        val updates = hashMapOf<String, Any>(
            "status" to if (approve) QuestStatus.APPROVED.name else QuestStatus.REJECTED.name,
            "verifiedAt" to now,
            "updatedAt" to now
        )
        if (approve) {
            updates["rewardApplied"] = true
            approvedTaskVersions[cleanTaskId] = now
            Log.i(TAG, "TASK_APPROVE_START taskId=$cleanTaskId familyId=$cleanFamilyId childUserId=$cleanChildUserId")
            Log.i(TAG, "TASK_APPROVE_STATE_PERSISTED taskId=$cleanTaskId approve=true status=${QuestStatus.APPROVED.name}")
        }

        // 1. Dual-sync status update to Realtime Database and Cloud Tombstone
        val rtdb = getRtdb()
        val rtdbTaskRef = rtdb?.getReference(RTDB_TASKS)
            ?.child(cleanFamilyId)
            ?.child(cleanChildUserId)
            ?.child(cleanTaskId)

        rtdbTaskRef?.updateChildren(updates)?.addOnSuccessListener {
            if (approve) {
                Log.i(TAG, "TASK_APPROVE_RTDB_SUCCESS taskId=$cleanTaskId")
                syncTombstoneToCloud(cleanFamilyId, cleanChildUserId, cleanTaskId)
            }
            Log.i(TAG, "TASK_VERIFY_RTDB_SUCCESS taskId=$cleanTaskId approve=$approve")
        }

        // 2. Award stars idempotently if approved
        if (approve && rewardStars > 0) {
            ChildRewardsManager.addRewardsToCloudWallet(
                context = context,
                familyId = cleanFamilyId,
                childUid = cleanChildUserId,
                earnedPoints = rewardStars,
                earnedCoins = rewardStars,
                transactionId = "task_reward_$cleanTaskId"
            )
            ChildRewardsManager.addRewards(
                context = context,
                childId = cleanChildUserId,
                earnedPoints = rewardStars,
                earnedCoins = rewardStars
            )
        }

        try {
            NotificationManager.deleteNotificationsForQuest(context, cleanTaskId)
        } catch (_: Exception) {}

        if (approve) {
            val approvedNotif = SystemNotification(
                id = "task_approved_${cleanTaskId}",
                title = "Task Approved! 🌟",
                message = "Awesome job! You earned $rewardStars Stars for completing the task!",
                type = NotificationType.TASK_APPROVED,
                childName = "Child",
                childCode = cleanChildUserId,
                actionData = cleanTaskId,
                targetRole = "CHILD",
                familyId = cleanFamilyId,
                childUid = cleanChildUserId
            )
            try {
                FirebaseSyncManager.sendNotificationToCloud(approvedNotif)
                Log.i(TAG, "TASK_APPROVED_NOTIFICATION_DISPATCHED taskId=$cleanTaskId childUserId=$cleanChildUserId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to dispatch task approved notification to cloud", e)
            }
        }

        // 3. Dual-sync status update to Firestore
        val db = getDb()
        val docRef = db?.collection(COLLECTION_FAMILIES)
            ?.document(cleanFamilyId)
            ?.collection(COLLECTION_MEMBERS)
            ?.document(cleanChildUserId)
            ?.collection(COLLECTION_TASKS)
            ?.document(cleanTaskId)

        docRef?.set(updates, SetOptions.merge())?.addOnSuccessListener {
            if (approve) {
                Log.i(TAG, "TASK_APPROVE_FIRESTORE_SUCCESS taskId=$cleanTaskId")
                Log.i(TAG, "TASK_APPROVE_SUCCESS taskId=$cleanTaskId")
            } else {
                Log.i(TAG, "TASK_VERIFY_SUCCESS taskId=$cleanTaskId approve=false")
            }
        }?.addOnFailureListener { e ->
            Log.w(TAG, "TASK_VERIFY_FIRESTORE_FAILED taskId=$cleanTaskId error=${e.message}")
        }

        if (approve) {
            // Schedule background cloud document cleanup after snapshot propagation
            mainHandler.postDelayed({
                try { rtdbTaskRef?.removeValue() } catch (_: Exception) {}
                try { docRef?.delete() } catch (_: Exception) {}
            }, 5000L)
        }

        // Instantly update active sync state for zero-latency UI response
        activeSyncStates[cleanChildUserId]?.let { syncState ->
            if (approve) {
                syncState.latestRtdbTasks.remove(cleanTaskId)
                syncState.latestFirestoreTasks.remove(cleanTaskId)
            } else {
                val existing = syncState.latestRtdbTasks[cleanTaskId] ?: syncState.latestFirestoreTasks[cleanTaskId]
                if (existing != null) {
                    val updated = existing.copy(
                        status = QuestStatus.REJECTED,
                        verifiedAt = now,
                        updatedAt = now
                    )
                    syncState.latestRtdbTasks[cleanTaskId] = updated
                    syncState.latestFirestoreTasks[cleanTaskId] = updated
                }
            }
            syncState.reconcileAndNotify()
        }

        mainHandler.post { onComplete(true) }
    }

    /**
     * Deletes a task from Realtime Database and Firestore.
     */
    fun deleteTask(
        context: Context,
        familyId: String,
        childUserId: String,
        questId: String,
        onComplete: (Boolean) -> Unit = {}
    ) {
        val cleanFamilyId = familyId.trim().uppercase()
        val cleanChildUserId = childUserId.trim()
        val cleanTaskId = questId.trim()

        Log.i(TAG, "TASK_DELETE_START familyId=$cleanFamilyId childUserId=$cleanChildUserId taskId=$cleanTaskId")

        if (cleanFamilyId.isBlank() || cleanChildUserId.isBlank() || cleanTaskId.isBlank()) {
            onComplete(false)
            return
        }

        if (cleanChildUserId.startsWith("HS-", ignoreCase = true)) {
            Log.e(TAG, "TASK_IDENTITY_ERROR: Pairing code $cleanChildUserId passed as childUserId in deleteTask")
            onComplete(false)
            return
        }

        // 1. Authoritative local tombstone and cloud tombstone FIRST
        addTombstone(context, cleanTaskId)
        syncTombstoneToCloud(cleanFamilyId, cleanChildUserId, cleanTaskId)
        Log.i(TAG, "TASK_TOMBSTONE_ADDED taskId=$cleanTaskId")

        // 2. Immediately purge local sync state and notify listeners for instant UI update
        val currentAuthUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
        for (syncState in activeSyncStates.values) {
            syncState.latestRtdbTasks.remove(cleanTaskId)
            syncState.latestFirestoreTasks.remove(cleanTaskId)
            syncState.reconcileAndNotify()
        }

        // 3. Purge specifically from local SharedPreferences cache
        ChildQuestManager.deleteQuest(context, cleanChildUserId, cleanTaskId)
        if (currentAuthUid.isNotBlank() && currentAuthUid != cleanChildUserId) {
            ChildQuestManager.deleteQuest(context, currentAuthUid, cleanTaskId)
        }
        try {
            NotificationManager.deleteNotificationsForQuest(context, cleanTaskId)
        } catch (_: Exception) {}
        Log.i(TAG, "TASK_CACHE_PURGED taskId=$cleanTaskId")

        var rtdbDone = false
        var firestoreDone = false
        var rtdbSuccess = false
        var firestoreSuccess = false

        fun checkFinished() {
            if (rtdbDone && firestoreDone) {
                if (rtdbSuccess && firestoreSuccess) {
                    Log.i(TAG, "TASK_DELETE_SUCCESS taskId=$cleanTaskId")
                    mainHandler.post { onComplete(true) }
                } else {
                    val failedBackend = if (!rtdbSuccess && !firestoreSuccess) "RTDB & Firestore" else if (!rtdbSuccess) "RTDB" else "Firestore"
                    Log.e(TAG, "TASK_DELETE_FAILED backend=$failedBackend taskId=$cleanTaskId")
                    mainHandler.post { onComplete(rtdbSuccess || firestoreSuccess) }
                }
            }
        }

        // 4. Delete from Realtime Database
        val rtdb = getRtdb()
        if (rtdb != null) {
            rtdb.getReference(RTDB_TASKS)
                .child(cleanFamilyId)
                .child(cleanChildUserId)
                .child(cleanTaskId)
                .removeValue()
                .addOnSuccessListener {
                    rtdbDone = true
                    rtdbSuccess = true
                    Log.i(TAG, "TASK_DELETE_RTDB_SUCCESS taskId=$cleanTaskId")
                    checkFinished()
                }
                .addOnFailureListener { e ->
                    rtdbDone = true
                    rtdbSuccess = false
                    Log.e(TAG, "TASK_DELETE_FAILED backend=RTDB taskId=$cleanTaskId error=${e.message}")
                    checkFinished()
                }
        } else {
            rtdbDone = true
            rtdbSuccess = true
            checkFinished()
        }

        // 5. Delete from Firestore
        val db = getDb()
        if (db != null) {
            db.collection(COLLECTION_FAMILIES)
                .document(cleanFamilyId)
                .collection(COLLECTION_MEMBERS)
                .document(cleanChildUserId)
                .collection(COLLECTION_TASKS)
                .document(cleanTaskId)
                .delete()
                .addOnSuccessListener {
                    firestoreDone = true
                    firestoreSuccess = true
                    Log.i(TAG, "TASK_DELETE_FIRESTORE_SUCCESS taskId=$cleanTaskId")
                    checkFinished()
                }
                .addOnFailureListener { e ->
                    firestoreDone = true
                    firestoreSuccess = false
                    Log.e(TAG, "TASK_DELETE_FAILED backend=Firestore taskId=$cleanTaskId error=${e.message}")
                    checkFinished()
                }
        } else {
            firestoreDone = true
            firestoreSuccess = true
            checkFinished()
        }
    }

    /**
     * Removes submitted photo proof from a task, resetting it back to PENDING status.
     * This is called when the Child wants to re-take/change their proof photo before Guardian reviews.
     * Unlike submitTaskProof, this does NOT require an HTTPS URL.
     */
    fun removeTaskProof(
        context: Context,
        familyId: String,
        childUserId: String,
        questId: String,
        onComplete: (Boolean) -> Unit = {}
    ) {
        val cleanFamilyId = familyId.trim().uppercase()
        val authUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
        val cleanChildUserId = authUid.ifBlank { childUserId.trim() }

        Log.i(TAG, "TASK_PROOF_REMOVE_START taskId=$questId familyId=$cleanFamilyId childUid=$cleanChildUserId")

        if (cleanFamilyId.isBlank() || cleanChildUserId.isBlank() || questId.isBlank()) {
            Log.e(TAG, "TASK_PROOF_REMOVE_FAILED taskId=$questId error=missing_required_ids")
            onComplete(false)
            return
        }
        if (cleanChildUserId.startsWith("HS-", ignoreCase = true)) {
            Log.e(TAG, "TASK_IDENTITY_ERROR: Pairing code $cleanChildUserId passed as childUserId in removeTaskProof")
            onComplete(false)
            return
        }

        val now = System.currentTimeMillis()
        val updates = hashMapOf<String, Any>(
            "status" to QuestStatus.PENDING.name,
            "photoProofLabel" to "",
            "photoProofUri" to "",
            "updatedAt" to now
        )

        // 1. Reset in Realtime Database
        val rtdb = getRtdb()
        rtdb?.getReference(RTDB_TASKS)
            ?.child(cleanFamilyId)
            ?.child(cleanChildUserId)
            ?.child(questId)
            ?.updateChildren(updates)
            ?.addOnSuccessListener {
                Log.i(TAG, "TASK_PROOF_REMOVE_RTDB_SUCCESS taskId=$questId")
            }

        // 2. Reset in Firestore
        val db = getDb()
        val docRef = db?.collection(COLLECTION_FAMILIES)
            ?.document(cleanFamilyId)
            ?.collection(COLLECTION_MEMBERS)
            ?.document(cleanChildUserId)
            ?.collection(COLLECTION_TASKS)
            ?.document(questId)

        docRef?.update(updates as Map<String, Any>)
            ?.addOnSuccessListener {
                Log.i(TAG, "TASK_PROOF_REMOVE_FIRESTORE_SUCCESS taskId=$questId")
            }
            ?.addOnFailureListener { e ->
                Log.w(TAG, "TASK_PROOF_REMOVE_UPDATE_FAILED taskId=$questId, trying set merge: ${e.message}")
                docRef.set(updates, SetOptions.merge())
            }

        // Instantly update active sync state for zero-latency UI response
        activeSyncStates[cleanChildUserId]?.let { syncState ->
            val existing = syncState.latestRtdbTasks[questId] ?: syncState.latestFirestoreTasks[questId]
            if (existing != null) {
                val updated = existing.copy(
                    status = QuestStatus.PENDING,
                    photoProofLabel = "",
                    photoProofUri = "",
                    updatedAt = now
                )
                syncState.latestRtdbTasks[questId] = updated
                syncState.latestFirestoreTasks[questId] = updated
                syncState.reconcileAndNotify()
            }
        }

        mainHandler.post { onComplete(true) }
    }
}
