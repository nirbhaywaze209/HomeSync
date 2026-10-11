package com.homesync.app.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.launch

enum class QuestStatus(val label: String, val badgeColorHex: Long) {
    PENDING("To Do", 0xFF64748B),
    SUBMITTED("Waiting Parent Approval ⏳", 0xFFF59E0B),
    APPROVED("Approved ✓ (+Stars)", 0xFF16A34A),
    REJECTED("Needs Re-do ❌", 0xFFDC2626)
}

data class ChildQuest(
    val id: String,
    val title: String,
    val rewardStars: Int,
    val dueTime: String,
    val status: QuestStatus = QuestStatus.PENDING,
    val photoProofLabel: String = "",
    val photoProofUri: String = "",
    val submittedAt: Long? = null,
    val verifiedAt: Long? = null,
    val rewardApplied: Boolean = false,
    val familyId: String = "",
    val childUserId: String = "",
    val createdByUserId: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

object ChildQuestManager {
    private const val PREFS_NAME = "homesync_child_quests_prefs_v3"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getKey(childId: String) = "quests_v3_" + childId.ifBlank { "default_child" }

    @Synchronized
    fun getQuests(context: Context, childId: String): List<ChildQuest> {
        val cleanId = childId.trim().uppercase()
        if (cleanId.isBlank()) return emptyList()
        val prefs = getPrefs(context)
        val jsonString = prefs.getString(getKey(cleanId), null) ?: return emptyList()

        return try {
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<ChildQuest>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id", "")
                val title = obj.optString("title", "")
                val statusName = obj.optString("status", QuestStatus.PENDING.name)
                val status = try { QuestStatus.valueOf(statusName) } catch (_: Exception) { QuestStatus.PENDING }

                // Purge legacy mock tasks, approved tasks, and tombstoned tasks
                if (id == "quest_1" || id == "quest_2" || id == "quest_3" || title.contains("Math Homework", ignoreCase = true) ||
                    status == QuestStatus.APPROVED || FamilyTaskManager.isTombstoned(context, id)
                ) {
                    continue
                }

                list.add(
                    ChildQuest(
                        id = if (id.isNotBlank()) id else UUID.randomUUID().toString(),
                        title = title,
                        rewardStars = obj.optInt("rewardStars", 20),
                        dueTime = obj.optString("dueTime", ""),
                        status = status,
                        photoProofLabel = obj.optString("photoProofLabel", ""),
                        photoProofUri = obj.optString("photoProofUri", ""),
                        submittedAt = if (obj.has("submittedAt") && !obj.isNull("submittedAt")) obj.optLong("submittedAt") else null,
                        verifiedAt = if (obj.has("verifiedAt") && !obj.isNull("verifiedAt")) obj.optLong("verifiedAt") else null,
                        createdAt = if (obj.has("createdAt") && !obj.isNull("createdAt")) obj.optLong("createdAt") else 0L,
                        updatedAt = if (obj.has("updatedAt") && !obj.isNull("updatedAt")) obj.optLong("updatedAt") else 0L
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun saveQuests(context: Context, childId: String, quests: List<ChildQuest>) {
        val prefs = getPrefs(context)
        val jsonArray = JSONArray()
        for (q in quests) {
            if (FamilyTaskManager.isTombstoned(context, q.id)) continue
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
                if (q.createdAt > 0) put("createdAt", q.createdAt)
                if (q.updatedAt > 0) put("updatedAt", q.updatedAt)
            }
            jsonArray.put(obj)
        }
        val serialized = jsonArray.toString()
        val cleanId = childId.trim().uppercase()
        if (cleanId.isNotBlank()) {
            val editor = prefs.edit()
            editor.putString(getKey(cleanId), serialized)
            editor.apply()
        }

        val hasApprovedFamily = FamilyManager.getStoredFamilyId(context).isNotBlank()
        if (!hasApprovedFamily) {
            try {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        FirebaseSyncManager.syncQuestsToCloud(context, childId, quests)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {
                try {
                    FirebaseSyncManager.syncQuestsToCloud(context, childId, quests)
                } catch (_: Exception) {}
            }
        }
    }

    @Synchronized
    fun saveQuestsFromCloud(context: Context, childId: String, quests: List<ChildQuest>) {
        val prefs = getPrefs(context)
        val jsonArray = JSONArray()
        for (q in quests) {
            if (FamilyTaskManager.isTombstoned(context, q.id)) continue
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
                if (q.createdAt > 0) put("createdAt", q.createdAt)
                if (q.updatedAt > 0) put("updatedAt", q.updatedAt)
            }
            jsonArray.put(obj)
        }
        val serialized = jsonArray.toString()
        val cleanId = childId.trim().uppercase()
        if (cleanId.isNotBlank()) {
            val editor = prefs.edit()
            editor.putString(getKey(cleanId), serialized)
            editor.apply()
        }
    }

    @Synchronized
    fun purgeLegacyTasksExcept(context: Context, childId: String, authoritativeTaskIds: List<String>): List<String> {
        val cleanId = childId.trim().uppercase()
        if (cleanId.isBlank()) return emptyList()
        val current = getQuests(context, cleanId)
        val authSet = authoritativeTaskIds.toSet()
        val toKeep = mutableListOf<ChildQuest>()
        val removedIds = mutableListOf<String>()

        for (q in current) {
            if (authSet.contains(q.id)) {
                toKeep.add(q)
            } else {
                removedIds.add(q.id)
            }
        }

        if (removedIds.isNotEmpty() || current.size != toKeep.size) {
            saveQuestsFromCloud(context, cleanId, toKeep)
        }
        return removedIds
    }

    @Synchronized
    fun submitProof(context: Context, childId: String, questId: String, photoLabel: String, photoUri: String = "", childName: String = "Child"): List<ChildQuest> {
        val current = getQuests(context, childId)
        var submittedQuestTitle = ""
        val updated = current.map { quest ->
            if (quest.id == questId) {
                submittedQuestTitle = quest.title
                quest.copy(
                    status = QuestStatus.SUBMITTED,
                    photoProofLabel = photoLabel,
                    photoProofUri = photoUri,
                    submittedAt = System.currentTimeMillis()
                )
            } else {
                quest
            }
        }
        saveQuests(context, childId, updated)

        if (submittedQuestTitle.isNotBlank()) {
            NotificationManager.addNotification(
                context,
                SystemNotification(
                    title = "Task Photo Verification Required 📷",
                    message = "Photo proof submitted by $childName for \"$submittedQuestTitle\". Please verify to award stars!",
                    type = NotificationType.TASK_PHOTO_SUBMITTED,
                    childName = childName,
                    childCode = childId,
                    actionData = questId
                )
            )
            try {
                FirebaseSyncManager.logActivityToCloud(
                    context,
                    ChildActivityCloudItem(
                        childCode = childId,
                        title = "Task Photo Submitted 📷",
                        detail = "$childName submitted photo proof for \"$submittedQuestTitle\"",
                        category = "TASK"
                    )
                )
            } catch (_: Exception) {}
        }
        return updated
    }

    @Synchronized
    fun verifyQuest(context: Context, childId: String, questId: String, approve: Boolean): List<ChildQuest> {
        val current = getQuests(context, childId)
        val target = current.find { it.id == questId }
        // Idempotency: if already in target state, just ensure notifications are cleared and return
        if (target == null || (approve && target.status == QuestStatus.APPROVED) || (!approve && target.status == QuestStatus.REJECTED)) {
            try {
                NotificationManager.deleteNotificationsForQuest(context, questId)
            } catch (_: Exception) {}
            return current
        }

        var awardedStars = 0
        val updated = current.map { quest ->
            if (quest.id == questId) {
                if (approve) {
                    if (!quest.rewardApplied) {
                        awardedStars = quest.rewardStars
                    }
                    quest.copy(
                        status = QuestStatus.APPROVED,
                        verifiedAt = System.currentTimeMillis(),
                        rewardApplied = true
                    )
                } else {
                    quest.copy(
                        status = QuestStatus.REJECTED,
                        verifiedAt = System.currentTimeMillis()
                    )
                }
            } else {
                quest
            }
        }

        saveQuests(context, childId, updated)

        if (approve && awardedStars > 0) {
            ChildRewardsManager.addRewards(
                context = context,
                childId = childId,
                earnedPoints = awardedStars,
                earnedCoins = awardedStars
            )
        }

        try {
            NotificationManager.deleteNotificationsForQuest(context, questId)
        } catch (_: Exception) {}

        return updated
    }

    @Synchronized
    fun removeProof(context: Context, childId: String, questId: String): List<ChildQuest> {
        val current = getQuests(context, childId)
        val updated = current.map { quest ->
            if (quest.id == questId) {
                if (quest.photoProofUri.isNotBlank()) {
                    try {
                        val file = java.io.File(quest.photoProofUri)
                        if (file.exists()) file.delete()
                    } catch (_: Exception) {}
                }
                quest.copy(
                    status = QuestStatus.PENDING,
                    photoProofLabel = "",
                    photoProofUri = "",
                    submittedAt = null
                )
            } else {
                quest
            }
        }
        saveQuests(context, childId, updated)
        try {
            NotificationManager.markNotificationsForQuestAsRead(context, questId)
        } catch (_: Exception) {}
        return updated
    }

    @Synchronized
    fun addCustomQuest(context: Context, childId: String, title: String, rewardStars: Int, dueTime: String): List<ChildQuest> {
        val current = getQuests(context, childId).toMutableList()
        val newQuest = ChildQuest(
            id = "quest_" + System.currentTimeMillis(),
            title = title,
            rewardStars = rewardStars,
            dueTime = dueTime,
            status = QuestStatus.PENDING
        )
        current.add(newQuest)
        saveQuests(context, childId, current)

        val notif = SystemNotification(
            id = "quest_assigned_${newQuest.id}",
            title = "New Quest Assigned! 📋",
            message = "New task: \"${newQuest.title}\" • Earn ${newQuest.rewardStars} Stars! (${newQuest.dueTime})",
            type = NotificationType.TASK_ASSIGNED,
            childName = "Child",
            childCode = childId,
            actionData = newQuest.id,
            targetRole = "CHILD"
        )
        NotificationManager.addNotification(context, notif)

        return current
    }

    @Synchronized
    fun deleteQuest(context: Context, childId: String, questId: String): List<ChildQuest> {
        val current = getQuests(context, childId).filter { it.id != questId }
        saveQuests(context, childId, current)
        try {
            NotificationManager.deleteNotificationsForQuest(context, questId)
        } catch (_: Exception) {}
        return current
    }
}
