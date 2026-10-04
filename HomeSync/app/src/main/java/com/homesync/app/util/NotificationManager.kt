package com.homesync.app.util

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class NotificationType {
    TASK_PHOTO_SUBMITTED,
    CHILD_SAFE_CHECKIN,
    SOS_EMERGENCY,
    SAFE_ZONE_EVENT,
    FAMILY_JOIN_REQUEST,
    TASK_ASSIGNED,
    TASK_APPROVED
}

data class SystemNotification(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val type: NotificationType,
    val childName: String = "Child",
    val childCode: String = "",
    val isRead: Boolean = false,
    val actionData: String = "", // Quest ID or extra metadata
    val targetRole: String = "GUARDIAN", // "GUARDIAN" or "CHILD"
    val familyId: String = "",
    val childUid: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

object NotificationManager {
    private const val PREFS_NAME = "homesync_notifications_prefs_v1"
    private const val KEY_NOTIFICATIONS = "saved_notifications_list"

    private const val KEY_DISMISSED_ALERTS = "dismissed_alert_ids"

    @Volatile
    var activeDeviceRole: String = "GUARDIAN"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isAlertDismissed(context: Context, notifId: String): Boolean {
        if (notifId.isBlank()) return true
        val prefs = getPrefs(context)
        val set = prefs.getStringSet(KEY_DISMISSED_ALERTS, emptySet()) ?: emptySet()
        return set.contains(notifId)
    }

    fun dismissAlert(context: Context, notifId: String) {
        if (notifId.isBlank()) return
        val prefs = getPrefs(context)
        val current = (prefs.getStringSet(KEY_DISMISSED_ALERTS, emptySet()) ?: emptySet()).toMutableSet()
        current.add(notifId)
        prefs.edit().putStringSet(KEY_DISMISSED_ALERTS, current).apply()
    }

    @Synchronized
    fun getNotifications(context: Context): List<SystemNotification> {
        val prefs = getPrefs(context)
        val jsonString = prefs.getString(KEY_NOTIFICATIONS, null)

        if (jsonString.isNullOrBlank()) {
            return emptyList()
        }

        return try {
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<SystemNotification>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id", "")
                val childName = obj.optString("childName", "")
                val childCode = obj.optString("childCode", "")
                // Filter out legacy dummy notifications
                if (id == "notif_1" || id == "notif_2" ||
                    childName.equals("Aarav", ignoreCase = true) || childName.equals("Maya", ignoreCase = true) ||
                    childCode.equals("HS-849201", ignoreCase = true) || childCode.equals("HS-392810", ignoreCase = true)
                ) {
                    continue
                }

                val typeName = obj.optString("type", NotificationType.CHILD_SAFE_CHECKIN.name)
                val type = try { NotificationType.valueOf(typeName) } catch (_: Exception) { NotificationType.CHILD_SAFE_CHECKIN }

                list.add(
                    SystemNotification(
                        id = if (id.isNotBlank()) id else UUID.randomUUID().toString(),
                        title = obj.optString("title", "Notification"),
                        message = obj.optString("message", ""),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        type = type,
                        childName = childName.ifBlank { "Child" },
                        childCode = childCode,
                        isRead = obj.optBoolean("isRead", false),
                        actionData = obj.optString("actionData", ""),
                        targetRole = obj.optString("targetRole", "GUARDIAN"),
                        familyId = obj.optString("familyId", ""),
                        childUid = obj.optString("childUid", ""),
                        latitude = obj.optDouble("latitude", 0.0),
                        longitude = obj.optDouble("longitude", 0.0)
                    )
                )
            }
            val effectiveRole = getEffectiveRole(context)
            list.filter { it.targetRole.equals(effectiveRole, ignoreCase = true) }
                .sortedByDescending { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getEffectiveRole(context: Context): String {
        val storedRole = try {
            FamilyManager.getStoredUserRole(context)
        } catch (_: Exception) {
            FamilyRole.GUARDIAN
        }
        if (activeDeviceRole.equals("CHILD", ignoreCase = true) || storedRole == FamilyRole.CHILD) {
            return "CHILD"
        }
        return if (activeDeviceRole.isNotBlank()) activeDeviceRole else "GUARDIAN"
    }

    fun isTargetDevice(context: Context, notification: SystemNotification): Boolean {
        val effectiveRole = getEffectiveRole(context)
        if (!notification.targetRole.equals(effectiveRole, ignoreCase = true)) {
            return false
        }
        if (effectiveRole.equals("CHILD", ignoreCase = true)) {
            val myUid = try {
                com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                    ?: FamilyManager.getStoredUserId(context)
            } catch (_: Exception) { "" }
            val myCode = try { ChildIdManager.getDeviceChildId(context) } catch (_: Exception) { "" }

            val targetUid = notification.childUid.trim()
            val targetCode = notification.childCode.trim()

            val matchesUid = targetUid.isBlank() || myUid.isBlank() || targetUid.equals(myUid, ignoreCase = true)
            val matchesCode = targetCode.isBlank() || myCode.isBlank() || targetCode.equals(myCode, ignoreCase = true)

            if (!matchesUid && !matchesCode) {
                return false
            }
        } else if (effectiveRole.equals("GUARDIAN", ignoreCase = true)) {
            val myFamilyId = try { FamilyManager.getStoredFamilyId(context).trim().uppercase() } catch (_: Exception) { "" }
            val notifFamilyId = notification.familyId.trim().uppercase()
            if (myFamilyId.isNotBlank() && notifFamilyId.isNotBlank() && myFamilyId != notifFamilyId) {
                return false
            }
        }
        return true
    }

    const val CHANNEL_ID = "homesync_heads_up_alerts_v3"
    const val CHANNEL_NAME = "HomeSync Alerts & Tasks"
    const val CHANNEL_EMERGENCY_ID = "homesync_emergency_alerts_v2"
    const val CHANNEL_EMERGENCY_NAME = "HomeSync Emergency SOS Alerts"

    fun ensureChannels(context: Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val systemNotifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return

            // 1. Regular Alerts & Quests Channel (Heads-up pop-up, notification chime, vibration)
            val notifSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            val notifAudioAttrs = android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .build()

            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Instant pop-up notifications for child quests, tasks, check-ins, and safety alerts"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
                enableLights(true)
                lightColor = android.graphics.Color.BLUE
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setSound(notifSound, notifAudioAttrs)
            }
            systemNotifManager.createNotificationChannel(channel)

            // 2. Emergency SOS Channel (Loud alarm, flashing red, DND bypass)
            val alarmSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            val emergencyAudioAttrs = android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .build()

            val emergencyChannel = android.app.NotificationChannel(
                CHANNEL_EMERGENCY_ID,
                CHANNEL_EMERGENCY_NAME,
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Loud emergency SOS alerts from family members"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500, 200, 500)
                enableLights(true)
                lightColor = android.graphics.Color.RED
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
                setSound(alarmSound, emergencyAudioAttrs)
            }
            systemNotifManager.createNotificationChannel(emergencyChannel)
        }
    }

    private fun showSystemStatusBarNotification(context: Context, notification: SystemNotification) {
        try {
            // Only fire system pop-up banner if notification is targeted to the current device role and user
            if (!isTargetDevice(context, notification)) {
                return
            }

            val systemNotifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
            val isEmergency = notification.type == NotificationType.SOS_EMERGENCY
            ensureChannels(context)

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("NOTIFICATION_ID", notification.id)
                putExtra("NOTIFICATION_TYPE", notification.type.name)
                putExtra("ACTION_DATA", notification.actionData)
                putExtra("TARGET_ROLE", notification.targetRole)
                if (isEmergency) {
                    putExtra("NAVIGATE_TO", "EMERGENCY_SOS")
                    putExtra("SOS_CHILD_NAME", notification.childName)
                    putExtra("SOS_CHILD_CODE", notification.childCode)
                    putExtra("SOS_FAMILY_ID", notification.familyId)
                }
            }
            val pendingIntent = if (launchIntent != null) {
                android.app.PendingIntent.getActivity(
                    context,
                    notification.id.hashCode(),
                    launchIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            } else null

            val isChildTarget = notification.targetRole.equals("CHILD", ignoreCase = true)
            val displayTitle = when {
                isEmergency -> "🚨 SOS: ${notification.childName} - ${notification.title}"
                isChildTarget -> notification.title
                else -> "${notification.childName}: ${notification.title}"
            }

            val targetChannel = if (isEmergency) CHANNEL_EMERGENCY_ID else CHANNEL_ID
            val smallIconRes = if (isEmergency) {
                android.R.drawable.ic_dialog_alert
            } else {
                context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
            }

            val builder = androidx.core.app.NotificationCompat.Builder(context, targetChannel)
                .setSmallIcon(smallIconRes)
                .setContentTitle(displayTitle)
                .setContentText(notification.message)
                .setStyle(
                    androidx.core.app.NotificationCompat.BigTextStyle()
                        .bigText(notification.message)
                        .setBigContentTitle(displayTitle)
                        .setSummaryText(if (isChildTarget) "HomeSync Quest" else "HomeSync")
                )
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                .setCategory(if (isEmergency) androidx.core.app.NotificationCompat.CATEGORY_ALARM else androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setColor(0xFF2563EB.toInt())
                .setShowWhen(true)
                .setWhen(notification.timestamp)

            // Large Icon for high-fidelity phone notification look
            try {
                if (context.applicationInfo.icon != 0) {
                    val largeBmp = android.graphics.BitmapFactory.decodeResource(context.resources, context.applicationInfo.icon)
                    if (largeBmp != null) {
                        builder.setLargeIcon(largeBmp)
                    }
                }
            } catch (_: Exception) {}

            if (isEmergency) {
                val alarmSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                    ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                builder.setSound(alarmSound)
                builder.setVibrate(longArrayOf(0, 500, 200, 500, 200, 500, 200, 500))
                builder.setDefaults(androidx.core.app.NotificationCompat.DEFAULT_VIBRATE or androidx.core.app.NotificationCompat.DEFAULT_LIGHTS)
            } else {
                val notifSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                builder.setSound(notifSound)
                builder.setVibrate(longArrayOf(0, 250, 150, 250))
                builder.setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
            }

            if (pendingIntent != null) {
                builder.setContentIntent(pendingIntent)
                if (isEmergency) {
                    builder.setFullScreenIntent(pendingIntent, true)
                }

                // Add contextual action button like native phone notifications
                val actionLabel = when (notification.type) {
                    NotificationType.TASK_ASSIGNED -> "View Quest 📋"
                    NotificationType.TASK_APPROVED -> "Check Stars ⭐"
                    NotificationType.TASK_PHOTO_SUBMITTED -> "Verify Proof 📷"
                    NotificationType.SOS_EMERGENCY -> "View Location 🚨"
                    else -> "Open HomeSync"
                }
                builder.addAction(smallIconRes, actionLabel, pendingIntent)
            }

            val notifId = notification.id.hashCode()
            systemNotifManager.notify(notifId, builder.build())
            android.util.Log.i("NotificationManager", "SYSTEM_NOTIFICATION_DISPLAYED id=${notification.id} title=\"$displayTitle\" targetRole=${notification.targetRole}")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun addNotification(context: Context, notification: SystemNotification) {
        if (isAlertDismissed(context, notification.id)) return
        val current = getNotifications(context).toMutableList()
        android.util.Log.i("NotificationManager", "NOTIFICATION_RECEIVED id=${notification.id} title=\"${notification.title}\" targetRole=${notification.targetRole}")
        if (isTargetDevice(context, notification) && current.none { it.id == notification.id }) {
            current.add(0, notification)
            saveNotifications(context, current)
        }
        showSystemStatusBarNotification(context, notification)
        try {
            FirebaseSyncManager.sendNotificationToCloud(notification)
        } catch (_: Exception) {}
        val unread = getUnreadCount(context)
        android.util.Log.i("NotificationManager", "NOTIFICATION_BADGE_UPDATED unreadCount=$unread")
    }

    @Synchronized
    fun addNotificationFromCloud(context: Context, notification: SystemNotification) {
        if (isAlertDismissed(context, notification.id)) return
        if (!isTargetDevice(context, notification)) return

        val current = getNotifications(context).toMutableList()
        val existingIndex = current.indexOfFirst { it.id == notification.id }
        if (existingIndex >= 0) {
            if (notification.isRead && !current[existingIndex].isRead) {
                current[existingIndex] = current[existingIndex].copy(isRead = true)
                saveNotifications(context, current)
                val unread = getUnreadCount(context)
                android.util.Log.i("NotificationManager", "NOTIFICATION_READ id=${notification.id}")
                android.util.Log.i("NotificationManager", "NOTIFICATION_BADGE_UPDATED unreadCount=$unread")
            }
        } else {
            android.util.Log.i("NotificationManager", "NOTIFICATION_RECEIVED id=${notification.id} title=\"${notification.title}\" targetRole=${notification.targetRole}")
            current.add(0, notification)
            saveNotifications(context, current)
            if (!notification.isRead) {
                showSystemStatusBarNotification(context, notification)
            }
            val unread = getUnreadCount(context)
            android.util.Log.i("NotificationManager", "NOTIFICATION_BADGE_UPDATED unreadCount=$unread")
        }
    }

    @Synchronized
    fun markAllAsRead(context: Context) {
        val current = getNotifications(context)
        val updated = current.map {
            dismissAlert(context, it.id)
            FirebaseSyncManager.markNotificationReadInCloud(it.id)
            it.copy(isRead = true)
        }
        saveNotifications(context, updated)
        val unread = getUnreadCount(context)
        android.util.Log.i("NotificationManager", "NOTIFICATION_READ id=ALL")
        android.util.Log.i("NotificationManager", "NOTIFICATION_BADGE_UPDATED unreadCount=$unread")
    }

    @Synchronized
    fun markAsRead(context: Context, notificationId: String) {
        if (notificationId.isBlank()) return
        dismissAlert(context, notificationId)
        FirebaseSyncManager.markNotificationReadInCloud(notificationId)
        val current = getNotifications(context)
        val updated = current.map {
            if (it.id == notificationId) it.copy(isRead = true) else it
        }
        saveNotifications(context, updated)
        val unread = getUnreadCount(context)
        android.util.Log.i("NotificationManager", "NOTIFICATION_READ id=$notificationId")
        android.util.Log.i("NotificationManager", "NOTIFICATION_BADGE_UPDATED unreadCount=$unread")
    }

    @Synchronized
    fun markNotificationsForQuestAsRead(context: Context, questId: String) {
        if (questId.isBlank()) return
        val current = getNotifications(context)
        val updated = current.map {
            if (it.actionData == questId || it.id == questId) {
                dismissAlert(context, it.id)
                FirebaseSyncManager.markNotificationReadInCloud(it.id)
                it.copy(isRead = true)
            } else it
        }
        saveNotifications(context, updated)
        val unread = getUnreadCount(context)
        android.util.Log.i("NotificationManager", "NOTIFICATION_READ questId=$questId")
        android.util.Log.i("NotificationManager", "NOTIFICATION_BADGE_UPDATED unreadCount=$unread")
    }

    @Synchronized
    fun deleteNotificationsForQuest(context: Context, questId: String) {
        if (questId.isBlank()) return
        val current = getNotifications(context)
        val toDelete = current.filter { it.actionData == questId || it.id == questId }
        for (n in toDelete) {
            dismissAlert(context, n.id)
            FirebaseSyncManager.deleteNotificationInCloud(n.id)
        }
        val remaining = current.filter { it.actionData != questId && it.id != questId }
        saveNotifications(context, remaining)
        val unread = getUnreadCount(context)
        android.util.Log.i("NotificationManager", "NOTIFICATION_CLEARED questId=$questId")
        android.util.Log.i("NotificationManager", "NOTIFICATION_BADGE_UPDATED unreadCount=$unread")
    }

    @Synchronized
    fun clearAll(context: Context) {
        val current = getNotifications(context)
        for (n in current) {
            dismissAlert(context, n.id)
            FirebaseSyncManager.deleteNotificationInCloud(n.id)
        }
        saveNotifications(context, emptyList())
        android.util.Log.i("NotificationManager", "NOTIFICATION_CLEARED id=ALL")
        android.util.Log.i("NotificationManager", "NOTIFICATION_BADGE_UPDATED unreadCount=0")
    }

    @Synchronized
    fun getUnreadCount(context: Context): Int {
        return getNotifications(context).count { !it.isRead && !isAlertDismissed(context, it.id) }
    }

    private fun saveNotifications(context: Context, notifications: List<SystemNotification>) {
        val prefs = getPrefs(context)
        val jsonArray = JSONArray()
        for (n in notifications) {
            val obj = JSONObject().apply {
                put("id", n.id)
                put("title", n.title)
                put("message", n.message)
                put("timestamp", n.timestamp)
                put("type", n.type.name)
                put("childName", n.childName)
                put("childCode", n.childCode)
                put("isRead", n.isRead)
                put("actionData", n.actionData)
                put("targetRole", n.targetRole)
                put("familyId", n.familyId)
                put("childUid", n.childUid)
                put("latitude", n.latitude)
                put("longitude", n.longitude)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_NOTIFICATIONS, jsonArray.toString()).apply()
    }
}
