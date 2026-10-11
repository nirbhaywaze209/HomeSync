package com.homesync.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.homesync.app.MainActivity
import com.homesync.app.util.ChildIdManager
import com.homesync.app.util.FamilyManager
import com.homesync.app.util.FirebaseRealtimeSyncManager
import com.homesync.app.util.FirebaseSyncManager
import com.homesync.app.util.LocationHelper
import com.homesync.app.util.NotificationType
import com.homesync.app.util.SystemNotification
import java.util.Collections
import java.util.HashSet
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

        private val EMERGENCY_VIBRATION_PATTERN = longArrayOf(0, 800, 300, 800, 300, 800, 500)

        @Volatile
        var isServiceRunning = false

        @Volatile
        var activeEmergencyAlert: SystemNotification? = null

        @Volatile
        var instance: HomeSyncForegroundService? = null

        fun startForChild(context: Context) {
            startServiceSafely(context, ACTION_START_CHILD)
        }

        fun startForGuardian(context: Context) {
            startServiceSafely(context, ACTION_START_GUARDIAN)
        }

        fun triggerSosDirect(context: Context) {
            startServiceSafely(context, ACTION_TRIGGER_SOS)
        }

        fun stopAlarm(context: Context) {
            activeEmergencyAlert = null
            try {
                instance?.silenceAlarm()
            } catch (_: Exception) {}

            // Stopping the siren does not need a foreground start
            val intent = Intent(context, HomeSyncForegroundService::class.java).apply {
                action = ACTION_STOP_ALARM
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        private fun startServiceSafely(context: Context, action: String) {
            val intent = Intent(context, HomeSyncForegroundService::class.java).apply {
                this.action = action
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start service with action=$action", e)
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

    private val handledAlertIds = Collections.synchronizedSet(HashSet<String>())
    private val silencedAlertIds = Collections.synchronizedSet(HashSet<String>())
    private val handledServiceCommandIds = Collections.synchronizedSet(HashSet<String>())

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

        // startForeground must run for every action, because every start uses
        // startForegroundService. Otherwise Android crashes the app.
        val notification = if (action == ACTION_START_GUARDIAN) {
            buildGuardianPersistentNotification()
        } else {
            buildChildPersistentNotification()
        }

        // DATA_SYNC is always allowed. LOCATION is added only when a location
        // permission is granted, otherwise Android 14+ rejects the start.
        var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (hasLocationPermission()) {
            serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID_PERSISTENT, notification, serviceType)

        when (action) {
            ACTION_TRIGGER_SOS -> handleTriggerSos()
            ACTION_STOP_ALARM -> silenceAlarm()
            ACTION_START_GUARDIAN -> {
                currentRole = "GUARDIAN"
                stopChildScreenTimeMonitoring()
                setupGuardianSosListener()
            }
            ACTION_START_CHILD -> {
                currentRole = "CHILD"
                setupChildScreenTimeMonitoring()
            }
            else -> {
                currentRole = "CHILD"
                setupChildScreenTimeMonitoring()
            }
        }

        return START_STICKY
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    // ---------- Vibration helpers (version checks and deprecation handled here) ----------

    @Suppress("DEPRECATION")
    private fun legacyVibrator(): Vibrator? =
        getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private fun startEmergencyVibration() {
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                        ?.defaultVibrator
                        ?.vibrate(VibrationEffect.createWaveform(EMERGENCY_VIBRATION_PATTERN, 0))
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    legacyVibrator()?.vibrate(
                        VibrationEffect.createWaveform(EMERGENCY_VIBRATION_PATTERN, 0)
                    )
                }
                else -> {
                    @Suppress("DEPRECATION")
                    legacyVibrator()?.vibrate(EMERGENCY_VIBRATION_PATTERN, 0)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Emergency vibration failed", e)
        }
    }

    private fun stopVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
                    ?.cancel()
            } else {
                legacyVibrator()?.cancel()
            }
        } catch (_: Exception) {}
    }

    // ---------- Channels ----------

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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

    // ---------- Notifications ----------

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

        // Instant SOS action intent
        val sosIntent = Intent(this, HomeSyncForegroundService::class.java).apply {
            action = ACTION_TRIGGER_SOS
        }
        val sosPendingIntent = PendingIntent.getForegroundService(
            this,
            1002,
            sosIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

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

    // ---------- SOS (child side) ----------

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

        // 1. Fast path: the only write the guardian listens to. Send it first.
        FirebaseRealtimeSyncManager.sendEmergencySos(
            familyId = effFamilyId,
            childCode = activeChildId,
            notification = sosNotif
        )

        // 2 and 3. History copies to RTDB and Firestore run in the background,
        // so they never hold up the SOS.
        serviceScope.launch(Dispatchers.IO) {
            try {
                FirebaseRealtimeSyncManager.sendNotification(sosNotif)
            } catch (e: Exception) {
                Log.w(TAG, "RTDB history write failed", e)
            }
            try {
                FirebaseSyncManager.sendNotificationToCloud(sosNotif)
            } catch (e: Exception) {
                Log.w(TAG, "Firestore write failed", e)
            }
        }

        // Local feedback
        playLocalAlertBeep()
        android.widget.Toast.makeText(
            this,
            "🚨 EMERGENCY SOS Alert Sent to Parents!",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    // ---------- Guardian listeners ----------

    private fun setupGuardianSosListener() {
        cancelSosListener?.invoke()
        cancelNotifListener?.invoke()

        val familyId = FamilyManager.getStoredFamilyId(this)
        Log.i(TAG, "Setting up Guardian SOS listener for family: $familyId")

        // Keep the CPU active for background socket delivery
        acquireMonitorWakeLock()

        // Keep RTDB online and keep the SOS node synced.
        // hs_notifications is intentionally not synced here: it grows over time and slows delivery.
        val rtdb = FirebaseRealtimeSyncManager.getRtdb()
        rtdb?.goOnline()
        if (familyId.isNotBlank()) {
            try {
                rtdb?.getReference("hs_sos")?.child(familyId.trim().uppercase())?.keepSynced(true)
            } catch (_: Exception) {}
        }

        // Fast listener on hs_sos/{familyId}
        if (familyId.isNotBlank()) {
            cancelSosListener = FirebaseRealtimeSyncManager.listenEmergencySos(familyId) { sosNotif ->
                Log.i(TAG, "GUARDIAN_SERVICE_SOS_RECEIVED from hs_sos id=${sosNotif.id} child=${sosNotif.childName}")
                triggerGuardianEmergencyAlert(sosNotif)
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

        // Wake the screen
        acquireWakeLock()

        // Start siren sound and vibration
        startEmergencySiren()

        // Show the full-screen heads-up notification
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
        val silencePendingIntent = PendingIntent.getForegroundService(
            this,
            9999,
            silenceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(
            this,
            com.homesync.app.util.NotificationManager.CHANNEL_EMERGENCY_ID
        )
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 EMERGENCY SOS: ${notif.childName} Needs Help!")
            .setContentText(notif.message)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${notif.message}\n\nTap immediately to view live child location and contact.")
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(contentPendingIntent, true)
            .setContentIntent(contentPendingIntent)
            .addAction(android.R.drawable.ic_lock_power_off, "SILENCE ALARM", silencePendingIntent)
            .setAutoCancel(true)

        nm.notify(NOTIFICATION_ID_EMERGENCY, builder.build())
    }

    // ---------- Siren and alarm ----------

    private fun startEmergencySiren() {
        silenceAlarm()
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            if (alarmUri != null) {
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing siren media player", e)
        }

        startEmergencyVibration()
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

    // ---------- Wake locks and feedback ----------

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

    private fun playLocalAlertBeep() {
        try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
                .startTone(ToneGenerator.TONE_PROP_BEEP, 300)
        } catch (_: Exception) {}
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

        // 1. Listen for remote Guardian commands via dedicated hs_commands node in the background
        cancelChildCommandListener = FirebaseRealtimeSyncManager.listenChildCommands(activeChildId) { cmdId, commandType, targetChildId, cmdTimestamp, _ ->
            try {
                val cleanTarget = targetChildId.trim().uppercase()
                val cleanActive = activeChildId.trim().uppercase()
                if (cleanTarget.isNotEmpty() && cleanTarget != "ALL" && cleanTarget != cleanActive) {
                    return@listenChildCommands
                }

                if (cmdId.isNotBlank() && cmdId != "NONE" && !handledServiceCommandIds.add(cmdId)) {
                    Log.i(TAG, "COMMAND_IGNORED_DUPLICATE commandId=$cmdId")
                    return@listenChildCommands
                }
                if (cmdId.startsWith("LOCK") || commandType.contains("LOCK")) {
                    com.homesync.app.util.ParentalControlManager.setCurfewOverride(this, activeChildId, false)
                    com.homesync.app.util.ScreenTimeManager.setLocalLocked(this, activeChildId, true)
                    com.homesync.app.util.ScreenTimeManager.setRemoteLocked(this, activeChildId, true)
                    com.homesync.app.util.ScreenTimeManager.saveRemainingSeconds(this, activeChildId, 0)
                    com.homesync.app.util.ScreenTimeManager.saveLastCommand(this, activeChildId, cmdId, cmdTimestamp, true)
                } else if (cmdId.startsWith("UNLOCK") || commandType.contains("UNLOCK")) {
                    com.homesync.app.util.ParentalControlManager.setCurfewOverride(this, activeChildId, true)
                    com.homesync.app.util.ScreenTimeManager.setLocalLocked(this, activeChildId, false)
                    com.homesync.app.util.ScreenTimeManager.setRemoteLocked(this, activeChildId, false)
                    val actualUsed = com.homesync.app.util.ScreenTimeManager.getUsedSeconds(this, activeChildId)
                    val currentTot = com.homesync.app.util.ScreenTimeManager.getTotalAllowance(this, activeChildId)
                    val effTot = if (currentTot > 0) currentTot else com.homesync.app.util.ScreenTimeManager.DEFAULT_ALLOWANCE_SECONDS
                    val finalTot = if (effTot <= actualUsed) (actualUsed + 1800) else effTot
                    val finalRem = (finalTot - actualUsed).coerceAtLeast(0)
                    com.homesync.app.util.ScreenTimeManager.saveTotalAllowance(this, activeChildId, finalTot)
                    com.homesync.app.util.ScreenTimeManager.saveRemainingSeconds(this, activeChildId, finalRem)
                    com.homesync.app.util.ScreenTimeManager.saveUsedSeconds(this, activeChildId, actualUsed)
                    com.homesync.app.util.ScreenTimeManager.saveLastCommand(this, activeChildId, cmdId, cmdTimestamp, false)
                } else if (cmdId.startsWith("RESET") || commandType.startsWith("RESET")) {
                    com.homesync.app.util.ParentalControlManager.setCurfewOverride(this, activeChildId, true)
                    com.homesync.app.util.ScreenTimeManager.setLocalLocked(this, activeChildId, false)
                    com.homesync.app.util.ScreenTimeManager.setRemoteLocked(this, activeChildId, false)
                    val resetAllowance = com.homesync.app.util.ScreenTimeManager.DEFAULT_ALLOWANCE_SECONDS
                    com.homesync.app.util.ScreenTimeManager.saveTotalAllowance(this, activeChildId, resetAllowance)
                    com.homesync.app.util.ScreenTimeManager.saveRemainingSeconds(this, activeChildId, resetAllowance)
                    com.homesync.app.util.ScreenTimeManager.saveUsedSeconds(this, activeChildId, 0)
                    com.homesync.app.util.ScreenTimeManager.recordResetBaseForChild(this, activeChildId, if (cmdTimestamp > 0) cmdTimestamp else System.currentTimeMillis())
                    com.homesync.app.util.ScreenTimeManager.updateUsageFromChild(this, activeChildId, 0)
                    try {
                        FirebaseRealtimeSyncManager.getRtdb()
                            ?.getReference("hs_commands")
                            ?.child(activeChildId)
                            ?.child(cmdId)
                            ?.removeValue()
                    } catch (e: Exception) {
                        Log.w("HomeSyncLatency", "Failed to remove command $cmdId from hs_commands", e)
                    }
                }
                // Remove the command so it can never run again (stops replays and repeated lock blinking)
                if (cmdId.isNotBlank() && cmdId != "NONE") {
                    try {
                        FirebaseRealtimeSyncManager.getRtdb()?.getReference("hs_commands")
                            ?.child(activeChildId)?.child(cmdId)?.removeValue()
                    } catch (_: Exception) {}
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
                    delay(15000L)
                    cycleCount++

                    com.homesync.app.util.ScreenTimeManager.checkAndApplyDailyReset(this@HomeSyncForegroundService, activeChildId)

                    val isLocked = com.homesync.app.util.ScreenTimeManager.isDeviceLocked(this@HomeSyncForegroundService, activeChildId)
                    if (!isLocked) {
                        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                        val isInteractive = pm?.isInteractive ?: true
                        val deltaSeconds = if (isInteractive) 15 else 0

                        val actualUsed = com.homesync.app.util.ScreenTimeManager.recordUsageTick(this@HomeSyncForegroundService, activeChildId, deltaSeconds)
                        val currentTot = com.homesync.app.util.ScreenTimeManager.getTotalAllowance(this@HomeSyncForegroundService, activeChildId)
                        val calculatedRem = (currentTot - actualUsed).coerceAtLeast(0)

                        com.homesync.app.util.ScreenTimeManager.saveRemainingSeconds(this@HomeSyncForegroundService, activeChildId, calculatedRem)
                        com.homesync.app.util.ScreenTimeManager.saveUsedSeconds(this@HomeSyncForegroundService, activeChildId, actualUsed)

                        if (calculatedRem <= 0 && actualUsed >= currentTot && currentTot > 0) {
                            com.homesync.app.util.ScreenTimeManager.setLocalLocked(this@HomeSyncForegroundService, activeChildId, true)
                        }
                    }

                    // Sync usage to cloud every 30s (every 2 cycles) when active, presence heartbeat every 30s (every 2 cycles)
                    if (cycleCount % 2 == 0) {
                        val currentUsed = com.homesync.app.util.ScreenTimeManager.getUsedSeconds(this@HomeSyncForegroundService, activeChildId)
                        com.homesync.app.util.ScreenTimeManager.updateUsageFromChild(this@HomeSyncForegroundService, activeChildId, currentUsed)
                        FirebaseRealtimeSyncManager.updatePresence(activeChildId, isOnline = true)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) break
                    Log.w(TAG, "Error in background screen time monitoring loop", e)
                }
            }
        }
    }

    // ---------- Lifecycle ----------

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        silenceAlarm()
        releaseMonitorWakeLock()
        cancelSosListener?.invoke()
        cancelNotifListener?.invoke()
        cancelChildCommandListener?.invoke()
        stopChildScreenTimeMonitoring()
        serviceScope.cancel()
        wakeLock = null
        instance = null
        Log.i(TAG, "HomeSyncForegroundService destroyed")
    }
}