package com.homesync.app.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.homesync.app.MainActivity
import com.homesync.app.util.ChildIdManager
import com.homesync.app.util.FamilyManager
import com.homesync.app.util.FamilyRole
import com.homesync.app.util.FirebaseRealtimeSyncManager
import com.homesync.app.util.FirebaseSyncManager
import com.homesync.app.util.LocationHelper
import com.homesync.app.util.NotificationType
import com.homesync.app.util.ParentalControlManager
import com.homesync.app.util.ScreenTimeManager
import com.homesync.app.util.SystemNotification
import java.util.UUID
import kotlinx.coroutines.*

class HomeSyncForegroundService : Service() {

    companion object {
        private const val TAG = "HomeSyncFGS"

        const val CHANNEL_CHILD_PERSISTENT_ID = "homesync_child_protection_channel"
        const val CHANNEL_CHILD_PERSISTENT_NAME = "HomeSync Child Protection"

        const val CHANNEL_GUARDIAN_PERSISTENT_ID = "homesync_guardian_monitor_channel"
        const val CHANNEL_GUARDIAN_PERSISTENT_NAME = "HomeSync Guardian Real-Time Monitor"

        const val NOTIFICATION_ID_PERSISTENT = 9001
        const val NOTIFICATION_ID_EMERGENCY = 9002

        const val ACTION_START_CHILD = "com.homesync.app.action.START_CHILD_SERVICE"
        const val ACTION_START_GUARDIAN = "com.homesync.app.action.START_GUARDIAN_SERVICE"
        const val ACTION_TRIGGER_SOS = "com.homesync.app.action.TRIGGER_SOS"
        const val ACTION_STOP_ALARM = "com.homesync.app.action.STOP_ALARM"

        @Volatile
        var isServiceRunning = false

        @Volatile
        var activeEmergencyAlert: SystemNotification? = null

        fun startForChild(context: Context) {
            val intent = Intent(context, HomeSyncForegroundService::class.java).apply {
                action = ACTION_START_CHILD
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start service for child", e)
            }
        }

        fun startForGuardian(context: Context) {
            val intent = Intent(context, HomeSyncForegroundService::class.java).apply {
                action = ACTION_START_GUARDIAN
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start service for guardian", e)
            }
        }

        @Volatile
        var instance: HomeSyncForegroundService? = null

        fun stopAlarm(context: Context) {
            activeEmergencyAlert = null
            try {
                instance?.silenceAlarm()
            } catch (_: Exception) {}

            val intent = Intent(context, HomeSyncForegroundService::class.java).apply {
                action = ACTION_STOP_ALARM
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun triggerSosDirect(context: Context) {
            val intent = Intent(context, HomeSyncForegroundService::class.java).apply {
                action = ACTION_TRIGGER_SOS
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to trigger SOS direct via service", e)
            }
        }
    }

    private var currentRole: String = "CHILD"
    private var cancelSosListener: (() -> Unit)? = null
    private var cancelNotifListener: (() -> Unit)? = null
    private var childMonitoringJob: Job? = null
    private var cancelChildCommandListener: (() -> Unit)? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val handledAlertIds = java.util.Collections.synchronizedSet(java.util.HashSet<String>())
    private val silencedAlertIds = java.util.Collections.synchronizedSet(java.util.HashSet<String>())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        isServiceRunning = true
        ensureChannels()
        Log.i(TAG, "HomeSyncForegroundService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_CHILD
        Log.i(TAG, "onStartCommand action=$action")

        when (action) {
            ACTION_TRIGGER_SOS -> {
                handleTriggerSos()
            }
            ACTION_STOP_ALARM -> {
                silenceAlarm()
            }
            ACTION_START_GUARDIAN -> {
                currentRole = "GUARDIAN"
                stopChildScreenTimeMonitoring()
                startForeground(NOTIFICATION_ID_PERSISTENT, buildGuardianPersistentNotification())
                setupGuardianSosListener()
            }
            ACTION_START_CHILD -> {
                currentRole = "CHILD"
                startForeground(NOTIFICATION_ID_PERSISTENT, buildChildPersistentNotification())
                setupChildScreenTimeMonitoring()
            }
            else -> {
                currentRole = "CHILD"
                startForeground(NOTIFICATION_ID_PERSISTENT, buildChildPersistentNotification())
                setupChildScreenTimeMonitoring()
            }
        }

        return START_STICKY
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // 1. Silent persistent channel for child
            val childChannel = NotificationChannel(
                CHANNEL_CHILD_PERSISTENT_ID,
                CHANNEL_CHILD_PERSISTENT_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing notification for Child Safety and fast SOS access"
                setShowBadge(false)
            }
            nm.createNotificationChannel(childChannel)

            // 2. Silent persistent channel for guardian
            val guardianChannel = NotificationChannel(
                CHANNEL_GUARDIAN_PERSISTENT_ID,
                CHANNEL_GUARDIAN_PERSISTENT_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing notification for Guardian background real-time safety monitoring"
                setShowBadge(false)
            }
            nm.createNotificationChannel(guardianChannel)

            // 3. High-importance emergency alarm channel
            com.homesync.app.util.NotificationManager.ensureChannels(this)
            com.homesync.app.util.HomeSyncMessagingService.ensureEmergencyChannel(this)
        }
    }

    private fun buildChildPersistentNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            1001,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Instant SOS Action Intent
        val sosIntent = Intent(this, HomeSyncForegroundService::class.java).apply {
            action = ACTION_TRIGGER_SOS
        }
        val sosPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this,
                1002,
                sosIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this,
                1002,
                sosIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_CHILD_PERSISTENT_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("HomeSync Child Safety Active 🛡️")
            .setContentText("Tap SOS to instantly alert parents with live location")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_dialog_alert,
                "🚨 SEND EMERGENCY SOS",
                sosPendingIntent
            )
            .build()
    }

    private fun buildGuardianPersistentNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            2001,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_GUARDIAN_PERSISTENT_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("HomeSync Family Monitor Active 🛡️")
            .setContentText("Monitoring real-time family safety alerts")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
            .build()
    }

    private fun handleTriggerSos() {
        FirebaseRealtimeSyncManager.getRtdb()?.goOnline()
        val activeChildId = ChildIdManager.getDeviceChildId(this)
        val activeChildName = ChildIdManager.getChildName(this, activeChildId).ifBlank { "Child" }
        val effFamilyId = FamilyManager.getStoredFamilyId(this)
        val canonicalChildUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
            ?: FamilyManager.getStoredUserId(this)

        val loc = LocationHelper.getLastKnownLocation(this)
        val lat = loc?.latitude ?: 0.0
        val lng = loc?.longitude ?: 0.0

        val sosId = "sos_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
        val clickTs = System.currentTimeMillis()

        Log.i(TAG, "SOS_SERVICE_TRIGGERED alertId=$sosId childCode=$activeChildId lat=$lat lng=$lng")

        val sosNotif = SystemNotification(
            id = sosId,
            title = "EMERGENCY SOS ALERT",
            message = "EMERGENCY SOS Alert triggered by $activeChildName! Live location active.",
            type = NotificationType.SOS_EMERGENCY,
            childName = activeChildName,
            childCode = activeChildId,
            targetRole = "GUARDIAN",
            familyId = effFamilyId,
            childUid = canonicalChildUid,
            latitude = lat,
            longitude = lng
        )

        // 1. Immediately write to dedicated RTDB fast node
        FirebaseRealtimeSyncManager.sendEmergencySos(
            familyId = effFamilyId,
            childCode = activeChildId,
            notification = sosNotif
        )

        // 2. Write to RTDB hs_notifications
        FirebaseRealtimeSyncManager.sendNotification(sosNotif)

        // 3. Write to Firestore hs_sos_events & hs_notifications
        FirebaseSyncManager.sendNotificationToCloud(sosNotif)

        // Local feedback
        playLocalAlertBeep()
        android.widget.Toast.makeText(this, "🚨 EMERGENCY SOS Alert Sent to Parents!", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun setupGuardianSosListener() {
        cancelSosListener?.invoke()
        cancelNotifListener?.invoke()

        val familyId = FamilyManager.getStoredFamilyId(this)
        Log.i(TAG, "Setting up Guardian SOS listener for family: $familyId")

        // Ensure CPU stays active for background socket delivery
        acquireMonitorWakeLock()

        // Force RTDB to stay online in background and keep tables synced
        val rtdb = FirebaseRealtimeSyncManager.getRtdb()
        rtdb?.goOnline()
        if (familyId.isNotBlank()) {
            try {
                rtdb?.getReference("hs_sos")?.child(familyId.trim().uppercase())?.keepSynced(true)
                rtdb?.getReference("hs_notifications")?.keepSynced(true)
            } catch (_: Exception) {}
        }

        // 1. Dedicated ultra-fast listener on hs_sos/{familyId}
        if (familyId.isNotBlank()) {
            cancelSosListener = FirebaseRealtimeSyncManager.listenEmergencySos(familyId) { sosNotif ->
                Log.i(TAG, "GUARDIAN_SERVICE_SOS_RECEIVED from hs_sos id=${sosNotif.id} child=${sosNotif.childName}")
                triggerGuardianEmergencyAlert(sosNotif)
            }
        }

        // 2. Redundant listener on hs_notifications for any SOS
        cancelNotifListener = FirebaseRealtimeSyncManager.listenNotifications { notif ->
            if (notif.type == NotificationType.SOS_EMERGENCY && notif.targetRole.equals("GUARDIAN", ignoreCase = true)) {
                val myFamily = FamilyManager.getStoredFamilyId(this)
                if (myFamily.isBlank() || notif.familyId.isBlank() || myFamily.equals(notif.familyId, ignoreCase = true)) {
                    Log.i(TAG, "GUARDIAN_SERVICE_SOS_RECEIVED from hs_notifications id=${notif.id} child=${notif.childName}")
                    triggerGuardianEmergencyAlert(notif)
                }
            }
        }
    }

    private fun triggerGuardianEmergencyAlert(sosNotif: SystemNotification) {
        if (silencedAlertIds.contains(sosNotif.id)) {
            Log.i(TAG, "Skipping already silenced SOS alert id=${sosNotif.id}")
            return
        }
        if (!handledAlertIds.add(sosNotif.id)) {
            Log.i(TAG, "Already handling SOS alert id=${sosNotif.id}")
            return
        }

        activeEmergencyAlert = sosNotif

        // Wake screen
        acquireWakeLock()

        // Start siren sound and vibration
        startEmergencySiren()

        // Fire full-screen heads up notification
        showEmergencyHeadsUpNotification(sosNotif)
    }

    private fun showEmergencyHeadsUpNotification(notif: SystemNotification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("NAVIGATE_TO", "EMERGENCY_SOS")
            putExtra("SOS_CHILD_NAME", notif.childName)
            putExtra("SOS_CHILD_CODE", notif.childCode)
            putExtra("SOS_FAMILY_ID", notif.familyId)
            putExtra("SOS_EVENT_ID", notif.id)
            putExtra("SOS_LATITUDE", notif.latitude)
            putExtra("SOS_LONGITUDE", notif.longitude)
            putExtra("SOS_MESSAGE", notif.message)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            notif.id.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val silenceIntent = Intent(this, HomeSyncForegroundService::class.java).apply {
            action = ACTION_STOP_ALARM
        }
        val silencePendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                this,
                9999,
                silenceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this,
                9999,
                silenceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val emergencyChannelId = com.homesync.app.util.NotificationManager.CHANNEL_EMERGENCY_ID

        val builder = NotificationCompat.Builder(this, emergencyChannelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 EMERGENCY SOS: ${notif.childName} Needs Help!")
            .setContentText(notif.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${notif.message}\n\nTap immediately to view live child location and contact."))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(contentPendingIntent, true)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_lock_power_off, "SILENCE ALARM", silencePendingIntent)
            .setAutoCancel(true)

        nm.notify(NOTIFICATION_ID_EMERGENCY, builder.build())
    }

    private fun startEmergencySiren() {
        silenceAlarm()
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, alarmUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing siren media player", e)
        }

        // Emergency Vibration Pattern
        try {
            val pattern = longArrayOf(0, 800, 300, 800, 300, 800, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                } else {
                    @Suppress("DEPRECATION")
                    v?.vibrate(pattern, 0)
                }
            }
        } catch (_: Exception) {}
    }

    fun silenceAlarm() {
        val currentAlert = activeEmergencyAlert
        if (currentAlert != null) {
            silencedAlertIds.add(currentAlert.id)
            if (currentAlert.familyId.isNotBlank() && currentAlert.childCode.isNotBlank()) {
                FirebaseRealtimeSyncManager.acknowledgeEmergencySos(currentAlert.familyId, currentAlert.childCode)
            }
        }
        activeEmergencyAlert = null

        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID_EMERGENCY)
        } catch (_: Exception) {}

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.cancel()
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.cancel()
            }
        } catch (_: Exception) {}

        releaseWakeLock()
    }

    private fun playLocalAlertBeep() {
        try {
            val alertTone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val mp = MediaPlayer.create(applicationContext, alertTone)
            mp?.start()
            mp?.setOnCompletionListener { it.release() }
        } catch (_: Exception) {}
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "HomeSync:EmergencyWakeLock"
                )
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(30000L) // 30 seconds
            }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    private var monitorWakeLock: PowerManager.WakeLock? = null

    private fun acquireMonitorWakeLock() {
        try {
            if (monitorWakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                monitorWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomeSync:GuardianMonitorWakeLock")
            }
            if (monitorWakeLock?.isHeld == false) {
                monitorWakeLock?.acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseMonitorWakeLock() {
        try {
            if (monitorWakeLock?.isHeld == true) {
                monitorWakeLock?.release()
            }
        } catch (_: Exception) {}
        monitorWakeLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved: app swiped away, scheduling immediate service resurrection")
        val restartIntent = Intent(applicationContext, HomeSyncForegroundService::class.java).apply {
            action = if (currentRole == "GUARDIAN") ACTION_START_GUARDIAN else ACTION_START_CHILD
        }
        val restartPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                applicationContext,
                8888,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                applicationContext,
                8888,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        try {
            alarmManager?.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 500L,
                restartPendingIntent
            )
        } catch (_: Exception) {
            alarmManager?.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 500L,
                restartPendingIntent
            )
        }
    }

    private fun stopChildScreenTimeMonitoring() {
        childMonitoringJob?.cancel()
        childMonitoringJob = null
        cancelChildCommandListener?.invoke()
        cancelChildCommandListener = null
    }

    private fun setupChildScreenTimeMonitoring() {
        stopChildScreenTimeMonitoring()

        val activeChildId = ChildIdManager.getDeviceChildId(this)
        if (activeChildId.isBlank()) return

        Log.i(TAG, "Starting background screen time monitoring for child=$activeChildId")

        // 1. Listen for remote Guardian commands (LOCK, UNLOCK, RESET, GRANT, SET_LIMIT) in the background
        cancelChildCommandListener = FirebaseRealtimeSyncManager.listenScreenTimeWithCommandDetails(activeChildId) { rem, locked, tot, _, cmdId, cmdTimestamp, curfewOverride, targetChildId, commandType, _, resetVersion ->
            try {
                val cleanTarget = targetChildId.trim().uppercase()
                val cleanActive = activeChildId.trim().uppercase()
                if (cleanTarget.isNotEmpty() && cleanTarget != "ALL" && cleanTarget != cleanActive) {
                    return@listenScreenTimeWithCommandDetails
                }

                // Cloud Reset Detection
                val localResetVersion = ScreenTimeManager.getDailyResetVersion(this, activeChildId)
                if (resetVersion > 0L && resetVersion > localResetVersion) {
                    ScreenTimeManager.recordResetBaseForChild(this, activeChildId, resetVersion)
                    ParentalControlManager.setCurfewOverride(this, activeChildId, true)
                    ScreenTimeManager.setLocalLocked(this, activeChildId, false)
                    ScreenTimeManager.setRemoteLocked(this, activeChildId, false)
                    val resetAllowance = if (tot > 0) tot else ScreenTimeManager.DEFAULT_ALLOWANCE_SECONDS
                    ScreenTimeManager.saveTotalAllowance(this, activeChildId, resetAllowance)
                    ScreenTimeManager.saveRemainingSeconds(this, activeChildId, resetAllowance)
                    ScreenTimeManager.saveUsedSeconds(this, activeChildId, 0)
                    ScreenTimeManager.updateUsageFromChild(this, activeChildId, 0)
                    return@listenScreenTimeWithCommandDetails
                }

                if (cmdId.startsWith("LOCK") || commandType.contains("LOCK")) {
                    ParentalControlManager.setCurfewOverride(this, activeChildId, false)
                    ScreenTimeManager.setLocalLocked(this, activeChildId, true)
                    ScreenTimeManager.setRemoteLocked(this, activeChildId, true)
                    ScreenTimeManager.saveRemainingSeconds(this, activeChildId, 0)
                    ScreenTimeManager.saveLastCommand(this, activeChildId, cmdId, cmdTimestamp, true)
                } else if (cmdId.startsWith("UNLOCK") || commandType.contains("UNLOCK")) {
                    ParentalControlManager.setCurfewOverride(this, activeChildId, true)
                    ScreenTimeManager.setLocalLocked(this, activeChildId, false)
                    ScreenTimeManager.setRemoteLocked(this, activeChildId, false)
                    val actualUsed = ScreenTimeManager.getUsedSeconds(this, activeChildId)
                    val currentTot = ScreenTimeManager.getTotalAllowance(this, activeChildId)
                    val effTot = if (tot > 0) tot else (if (currentTot > 0) currentTot else ScreenTimeManager.DEFAULT_ALLOWANCE_SECONDS)
                    val finalTot = if (effTot <= actualUsed) (actualUsed + (if (rem > 0) rem else 1800)) else effTot
                    val finalRem = (finalTot - actualUsed).coerceAtLeast(0)
                    ScreenTimeManager.saveTotalAllowance(this, activeChildId, finalTot)
                    ScreenTimeManager.saveRemainingSeconds(this, activeChildId, finalRem)
                    ScreenTimeManager.saveUsedSeconds(this, activeChildId, actualUsed)
                    ScreenTimeManager.saveLastCommand(this, activeChildId, cmdId, cmdTimestamp, false)
                } else if (cmdId.startsWith("RESET") || commandType.startsWith("RESET")) {
                    ParentalControlManager.setCurfewOverride(this, activeChildId, true)
                    ScreenTimeManager.setLocalLocked(this, activeChildId, false)
                    ScreenTimeManager.setRemoteLocked(this, activeChildId, false)
                    val resetAllowance = if (tot > 0) tot else ScreenTimeManager.DEFAULT_ALLOWANCE_SECONDS
                    ScreenTimeManager.saveTotalAllowance(this, activeChildId, resetAllowance)
                    ScreenTimeManager.saveRemainingSeconds(this, activeChildId, resetAllowance)
                    ScreenTimeManager.saveUsedSeconds(this, activeChildId, 0)
                    ScreenTimeManager.recordResetBaseForChild(this, activeChildId, if (cmdTimestamp > 0) cmdTimestamp else System.currentTimeMillis())
                    ScreenTimeManager.updateUsageFromChild(this, activeChildId, 0)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing screen time command in background service", e)
            }
        }

        // 2. Periodic background usage measurement and syncing loop
        childMonitoringJob = serviceScope.launch {
            var cycleCount = 0
            while (isActive) {
                try {
                    delay(3000L)
                    cycleCount++

                    ScreenTimeManager.checkAndApplyDailyReset(this@HomeSyncForegroundService, activeChildId)

                    val isLocked = ScreenTimeManager.isDeviceLocked(this@HomeSyncForegroundService, activeChildId)
                    if (!isLocked) {
                        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                        val isInteractive = pm?.isInteractive ?: true
                        val deltaSeconds = if (isInteractive) 3 else 0

                        val actualUsed = ScreenTimeManager.recordUsageTick(this@HomeSyncForegroundService, activeChildId, deltaSeconds)
                        val currentTot = ScreenTimeManager.getTotalAllowance(this@HomeSyncForegroundService, activeChildId)
                        val calculatedRem = (currentTot - actualUsed).coerceAtLeast(0)

                        ScreenTimeManager.saveRemainingSeconds(this@HomeSyncForegroundService, activeChildId, calculatedRem)
                        ScreenTimeManager.saveUsedSeconds(this@HomeSyncForegroundService, activeChildId, actualUsed)

                        if (calculatedRem <= 0 && actualUsed >= currentTot && currentTot > 0) {
                            ScreenTimeManager.setLocalLocked(this@HomeSyncForegroundService, activeChildId, true)
                        }
                    }

                    // Sync usage to cloud every 30s (every 10 cycles) when active, presence heartbeat every 30s (every 10 cycles)
                    if (cycleCount % 10 == 0) {
                        val currentUsed = ScreenTimeManager.getUsedSeconds(this@HomeSyncForegroundService, activeChildId)
                        ScreenTimeManager.updateUsageFromChild(this@HomeSyncForegroundService, activeChildId, currentUsed)
                        FirebaseRealtimeSyncManager.updatePresence(activeChildId, isOnline = true)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) break
                    Log.w(TAG, "Error in background screen time monitoring loop", e)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        silenceAlarm()
        releaseMonitorWakeLock()
        cancelSosListener?.invoke()
        cancelNotifListener?.invoke()
        stopChildScreenTimeMonitoring()
        serviceScope.cancel()
        Log.i(TAG, "HomeSyncForegroundService destroyed")
    }
}
