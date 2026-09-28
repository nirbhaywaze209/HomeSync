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
    FAMILY_JOIN_REQUEST
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
            list.sortedByDescending { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private const val CHANNEL_ID = "homesync_heads_up_alerts_v2"
    private const val CHANNEL_NAME = "HomeSync Pop-up Alerts"
    private const val CHANNEL_EMERGENCY_ID = "homesync_emergency_alerts_v2"
    private const val CHANNEL_EMERGENCY_NAME = "HomeSync Emergency SOS Alerts"

    private fun showSystemStatusBarNotification(context: Context, notification: SystemNotification) {
        try {
            // Only fire system pop-up banner if notification is targeted to the current device role
            if (!notification.targetRole.equals(activeDeviceRole, ignoreCase = true)) {
                return
            }

            val systemNotifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
            val isEmergency = notification.type == NotificationType.SOS_EMERGENCY

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (isEmergency) {
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
                        val alarmSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                            ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                        val audioAttributes = android.media.AudioAttributes.Builder()
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .build()
                        setSound(alarmSound, audioAttributes)
                    }
                    systemNotifManager.createNotificationChannel(emergencyChannel)
                } else {
                    val channel = android.app.NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        android.app.NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Instant pop-up notifications for child check-ins, tasks, and safety alerts"
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 300, 150, 300)
                        enableLights(true)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
                    systemNotifManager.createNotificationChannel(channel)
                }
            }

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = if (launchIntent != null) {
                android.app.PendingIntent.getActivity(
                    context,
                    notification.id.hashCode(),
                    launchIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            } else null

            val targetChannel = if (isEmergency) CHANNEL_EMERGENCY_ID else CHANNEL_ID
            val builder = androidx.core.app.NotificationCompat.Builder(context, targetChannel)
                .setSmallIcon(if (isEmergency) android.R.drawable.ic_dialog_alert else android.R.drawable.ic_dialog_info)
                .setContentTitle("${if (isEmergency) "🚨 SOS: " else ""}${notification.childName}: ${notification.title}")
                .setContentText(notification.message)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                .setDefaults(if (isEmergency) androidx.core.app.NotificationCompat.DEFAULT_VIBRATE or androidx.core.app.NotificationCompat.DEFAULT_LIGHTS else androidx.core.app.NotificationCompat.DEFAULT_ALL)
                .setCategory(if (isEmergency) androidx.core.app.NotificationCompat.CATEGORY_ALARM else androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)

            if (isEmergency) {
                val alarmSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                    ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                builder.setSound(alarmSound)
                builder.setVibrate(longArrayOf(0, 500, 200, 500, 200, 500, 200, 500))
            }

            if (pendingIntent != null) {
                builder.setContentIntent(pendingIntent)
            }

            val notifId = notification.id.hashCode()
            systemNotifManager.notify(notifId, builder.build())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    fun addNotification(context: Context, notification: SystemNotification) {
        val current = getNotifications(context).toMutableList()
        if (isAlertDismissed(context, notification.id)) return
        android.util.Log.i("NotificationManager", "NOTIFICATION_RECEIVED id=${notification.id} title=\"${notification.title}\" targetRole=${notification.targetRole}")
        if (current.none { it.id == notification.id }) {
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
