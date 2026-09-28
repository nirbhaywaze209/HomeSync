package com.homesync.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.homesync.app.MainActivity

class HomeSyncMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "HomeSyncFCM"
        const val CHANNEL_EMERGENCY_ID = "homesync_emergency_alerts_v2"
        const val CHANNEL_EMERGENCY_NAME = "HomeSync Emergency SOS Alerts"

        fun ensureEmergencyChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val systemNotifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return
                val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()

                val channel = NotificationChannel(
                    CHANNEL_EMERGENCY_ID,
                    CHANNEL_EMERGENCY_NAME,
                    NotificationManager.IMPORTANCE_MAX
                ).apply {
                    description = "Loud emergency SOS alerts from family members"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500, 200, 500)
                    enableLights(true)
                    lightColor = Color.RED
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    setBypassDnd(true)
                    setSound(alarmSound, audioAttributes)
                }
                systemNotifManager.createNotificationChannel(channel)
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val maskedToken = if (token.length > 12) "${token.substring(0, 6)}...${token.substring(token.length - 4)}" else "masked_token"
        Log.i(TAG, "FCM_TOKEN_REFRESHED token=$maskedToken")
        Log.i(TAG, "FCM_TOKEN_REGISTER_START")
        val prefs = getSharedPreferences("homesync_fcm_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("fcm_token", token).apply()
        FamilyManager.registerFcmToken(applicationContext, token)
    }

    private fun isEventProcessed(context: Context, eventId: String): Boolean {
        if (eventId.isBlank()) return false
        val prefs = context.getSharedPreferences("homesync_processed_sos_events", Context.MODE_PRIVATE)
        val set = prefs.getStringSet("processed_event_ids", emptySet()) ?: emptySet()
        return set.contains(eventId)
    }

    private fun markEventProcessed(context: Context, eventId: String) {
        if (eventId.isBlank()) return
        val prefs = context.getSharedPreferences("homesync_processed_sos_events", Context.MODE_PRIVATE)
        val current = (prefs.getStringSet("processed_event_ids", emptySet()) ?: emptySet()).toMutableSet()
        current.add(eventId)
        if (current.size > 200) {
            val trimmed = current.toList().takeLast(150).toSet()
            prefs.edit().putStringSet("processed_event_ids", trimmed).apply()
        } else {
            prefs.edit().putStringSet("processed_event_ids", current).apply()
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data
        val notifType = data["type"] ?: remoteMessage.notification?.tag ?: "SOS_EMERGENCY"
        val eventId = data["eventId"] ?: System.currentTimeMillis().toString()

        Log.i(TAG, "SOS_FCM_NOTIFICATION_RECEIVED eventId=$eventId type=$notifType")

        if (isEventProcessed(applicationContext, eventId)) {
            Log.i(TAG, "SOS_FCM_DUPLICATE_IGNORED eventId=$eventId")
            return
        }

        markEventProcessed(applicationContext, eventId)

        val title = data["title"] ?: remoteMessage.notification?.title ?: "EMERGENCY SOS ALERT"
        val body = data["message"] ?: data["body"] ?: remoteMessage.notification?.body ?: "Emergency alert received from child!"
        val childName = data["childName"] ?: "Child"
        val childCode = data["childCode"] ?: ""
        val familyId = data["familyId"] ?: ""

        showEmergencyNotification(
            context = applicationContext,
            eventId = eventId,
            title = title,
            message = body,
            childName = childName,
            childCode = childCode,
            familyId = familyId
        )
        Log.i(TAG, "SOS_FCM_NOTIFICATION_DISPLAYED eventId=$eventId")
    }

    private fun showEmergencyNotification(
        context: Context,
        eventId: String,
        title: String,
        message: String,
        childName: String,
        childCode: String,
        familyId: String
    ) {
        try {
            val systemNotifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            ensureEmergencyChannel(context)

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("NAVIGATE_TO", "EMERGENCY_SOS")
                putExtra("SOS_CHILD_NAME", childName)
                putExtra("SOS_CHILD_CODE", childCode)
                putExtra("SOS_FAMILY_ID", familyId)
                putExtra("SOS_EVENT_ID", eventId)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                eventId.hashCode(),
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val builder = NotificationCompat.Builder(context, CHANNEL_EMERGENCY_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("🚨 SOS: $childName - $title")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$message\n\nTap immediately to view live child location."))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setSound(alarmSound)
                .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500, 200, 500))
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)

            systemNotifManager.notify(eventId.hashCode(), builder.build())
            Log.i(TAG, "Dispatched emergency notification for $childName (eventId: $eventId)")
        } catch (e: Exception) {
            Log.e(TAG, "Error displaying emergency notification", e)
        }
    }
}
