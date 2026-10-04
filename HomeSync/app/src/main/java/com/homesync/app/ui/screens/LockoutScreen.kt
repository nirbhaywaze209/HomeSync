package com.homesync.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homesync.app.ui.theme.LockoutTheme
import com.homesync.app.ui.theme.NightEmergencyRed
import com.homesync.app.ui.theme.NightMoonGlow
import com.homesync.app.ui.theme.NightSkyDark
import com.homesync.app.ui.theme.NightSkyLight
import com.homesync.app.ui.theme.NightSkyMid
import com.homesync.app.ui.theme.NightStarWhite
import com.homesync.app.ui.theme.NightTextMuted
import com.homesync.app.util.ScreenTimeManager
import com.homesync.app.util.ParentalControlManager
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

@Composable
fun LockoutScreen(
    childId: String = "DEFAULT_CHILD",
    onUnlock: () -> Unit = {}
) {
    val context = LocalContext.current
    var isLocked by remember { mutableStateOf(ScreenTimeManager.isDeviceLocked(context, childId)) }
    var remainingSeconds by remember { mutableStateOf(ScreenTimeManager.getRemainingSeconds(context, childId)) }

    androidx.activity.compose.BackHandler(enabled = true) {
        Toast.makeText(context, "Device is locked by Guardian", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) {
        android.util.Log.i("HomeSyncLatency", "LOCKOUT_SCREEN_SHOWN childId=$childId timestamp=${System.currentTimeMillis()}")
    }

    // Live listener for Guardian remote unlock / +1h commands from Cloud & Local (Dual-Sync: RTDB + Firestore)
    DisposableEffect(childId) {
        val cleanId = childId.trim().uppercase()
        var cancelRtdb: (() -> Unit)? = null

        if (cleanId.isNotBlank()) {
            cancelRtdb = com.homesync.app.util.FirebaseRealtimeSyncManager.listenScreenTimeWithCommandDetails(cleanId) { rem, locked, tot, used, cmdId, cmdTimestamp, curfewOverride, targetChildId, commandType, _ ->
                val cleanTargetChild = targetChildId.trim().uppercase()
                if (cleanTargetChild.isNotBlank() && cleanTargetChild != "ALL" && cleanTargetChild != cleanId) {
                    android.util.Log.i("LockoutScreen", "SCREEN_TIME_COMMAND_IGNORED_TARGET_MISMATCH targetChild=$cleanTargetChild currentChild=$cleanId cmdId=$cmdId")
                    return@listenScreenTimeWithCommandDetails
                }

                val lastTs = ScreenTimeManager.getLastCommandTimestamp(context, cleanId)
                if (cmdTimestamp > 0 && cmdTimestamp < (lastTs - 30_000L)) {
                    android.util.Log.i("LockoutScreen", "SCREEN_TIME_COMMAND_STALE_IGNORED commandId=$cmdId cmdTimestamp=$cmdTimestamp lastTimestamp=$lastTs")
                    return@listenScreenTimeWithCommandDetails
                }

                if (cmdId.startsWith("RESET") || commandType.startsWith("RESET")) {
                    android.util.Log.i("LockoutScreen", "SCREEN_TIME_COMMAND_APPLIED childCode=$cleanId commandId=$cmdId action=RESET")
                    android.util.Log.i("LockoutScreen", "SCREEN_TIME_UNLOCK_STATE unlocked=true reason=RESET_COMMAND")
                    ScreenTimeManager.recordResetBaseForChild(context, cleanId)
                    ScreenTimeManager.saveLastCommand(context, cleanId, cmdId, cmdTimestamp, false)
                    com.homesync.app.util.ParentalControlManager.setCurfewOverride(context, cleanId, true)
                    ScreenTimeManager.setLocalLocked(context, cleanId, false)
                    val resetAllowance = if (tot > 0) tot else ScreenTimeManager.DEFAULT_ALLOWANCE_SECONDS
                    remainingSeconds = resetAllowance
                    isLocked = false
                    ScreenTimeManager.saveTotalAllowance(context, cleanId, resetAllowance)
                    ScreenTimeManager.saveRemainingSeconds(context, cleanId, resetAllowance)
                    ScreenTimeManager.saveUsedSeconds(context, cleanId, 0)
                    ScreenTimeManager.updateUsageFromChild(context, cleanId, 0)

                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onUnlock()
                    }
                    return@listenScreenTimeWithCommandDetails
                }

                val isExplicitUnlock = cmdId.startsWith("UNLOCK") || commandType.contains("UNLOCK") ||
                        cmdId.startsWith("GRANT_") || commandType.contains("EXTRA_TIME") || commandType.contains("GRANT") ||
                        cmdId.startsWith("SET_LIMIT") || commandType.contains("SET_DAILY_LIMIT")

                if (isExplicitUnlock) {
                    val actualUsed = if (ScreenTimeManager.hasUsageStatsPermission(context)) {
                        ScreenTimeManager.getRealDeviceUsageTodaySeconds(context, cleanId).coerceAtLeast(0)
                    } else {
                        ScreenTimeManager.getUsedSeconds(context, cleanId)
                    }
                    val currentStoredTot = ScreenTimeManager.getTotalAllowance(context, cleanId)
                    val (finalTot, finalRem) = if (cmdId.startsWith("SET_LIMIT") || commandType.contains("SET_DAILY_LIMIT")) {
                        val allowanceFromCmd = cmdId.split("_").mapNotNull { it.toIntOrNull() }.firstOrNull { it in 60..86400 }
                        val newAllowance = allowanceFromCmd ?: (if (tot > 0) tot else ScreenTimeManager.DEFAULT_ALLOWANCE_SECONDS)
                        val safeRem = (newAllowance - actualUsed).coerceAtLeast(0)
                        Pair(newAllowance, safeRem)
                    } else if (cmdId.startsWith("GRANT_") || commandType.contains("EXTRA_TIME") || commandType.contains("GRANT")) {
                        val extraSec = cmdId.split("_").mapNotNull { it.toIntOrNull() }.firstOrNull { it in 60..86400 }
                            ?: (if (tot > 0 && currentStoredTot > 0 && tot > currentStoredTot) (tot - currentStoredTot) else 1800)
                        val baseTot = if (currentStoredTot > 0) currentStoredTot else (if (tot > 0) tot else ScreenTimeManager.DEFAULT_ALLOWANCE_SECONDS)
                        val safeTot = if (baseTot <= actualUsed) (actualUsed + extraSec) else (baseTot + extraSec)
                        val safeRem = (safeTot - actualUsed).coerceAtLeast(0)
                        Pair(safeTot, safeRem)
                    } else {
                        val effTot = if (tot > 0) tot else currentStoredTot
                        val safeTot = if (effTot <= actualUsed) (actualUsed + (if (rem > 0) rem else 1800)) else effTot
                        val safeRem = (safeTot - actualUsed).coerceAtLeast(0)
                        Pair(safeTot, safeRem)
                    }

                    if (finalRem > 0 && !locked) {
                        android.util.Log.i("LockoutScreen", "SCREEN_TIME_COMMAND_APPLIED childCode=$cleanId commandId=$cmdId action=UNLOCK")
                        android.util.Log.i("LockoutScreen", "SCREEN_TIME_UNLOCK_STATE unlocked=true reason=REMOTE_UNLOCK")
                        ScreenTimeManager.saveLastCommand(context, cleanId, cmdId, cmdTimestamp, false)
                        com.homesync.app.util.ParentalControlManager.setCurfewOverride(context, cleanId, true)
                        ScreenTimeManager.setLocalLocked(context, cleanId, false)
                        remainingSeconds = finalRem
                        isLocked = false
                        ScreenTimeManager.saveTotalAllowance(context, cleanId, finalTot)
                        ScreenTimeManager.saveRemainingSeconds(context, cleanId, finalRem)
                        ScreenTimeManager.saveUsedSeconds(context, cleanId, actualUsed)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onUnlock()
                        }
                    } else {
                        ScreenTimeManager.saveTotalAllowance(context, cleanId, finalTot)
                        ScreenTimeManager.saveRemainingSeconds(context, cleanId, finalRem)
                        ScreenTimeManager.saveUsedSeconds(context, cleanId, actualUsed)
                        remainingSeconds = finalRem
                        isLocked = true
                    }
                } else if (locked || cmdId.startsWith("LOCK") || commandType.contains("LOCK") || ScreenTimeManager.isRemoteLocked(context, cleanId)) {
                    isLocked = true
                    remainingSeconds = 0
                    ParentalControlManager.setCurfewOverride(context, cleanId, false)
                    ScreenTimeManager.setLocalLocked(context, cleanId, true)
                    if (cmdId.isNotBlank() && cmdId != "NONE") {
                        ScreenTimeManager.saveLastCommand(context, cleanId, cmdId, cmdTimestamp, true)
                    }
                }
            }
        }
        onDispose {
            cancelRtdb?.invoke()
        }
    }

    LaunchedEffect(childId) {
        val cleanId = childId.trim().uppercase()
        while (true) {
            delay(1000L)
            if (cleanId.isNotBlank()) {
                ScreenTimeManager.checkAndApplyDailyReset(context, cleanId)
            }
            val remoteLocked = ScreenTimeManager.isRemoteLocked(context, cleanId)
            val locked = remoteLocked || ScreenTimeManager.isDeviceLocked(context, cleanId)
            val rem = if (locked) 0 else ScreenTimeManager.getRemainingSeconds(context, cleanId)
            isLocked = locked
            remainingSeconds = rem
            if (!locked && rem > 0) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onUnlock()
                }
                break
            }
        }
    }

    var currentTime by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            currentTime = Date()
        }
    }

    val timeFormat = remember { SimpleDateFormat("h:mm", Locale.getDefault()) }
    val amPmFormat = remember { SimpleDateFormat("a", Locale.getDefault()) }
    val secondsFormat = remember { SimpleDateFormat("ss", Locale.getDefault()) }

    LockoutTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(NightSkyDark, NightSkyMid, NightSkyLight)
                    )
                )
        ) {
            // Starry background
            val stars = remember {
                val rnd = Random(42)
                List(50) {
                    Triple(
                        rnd.nextFloat(), // x relative
                        rnd.nextFloat(), // y relative
                        rnd.nextFloat() // size/brightness modifier
                    )
                }
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height

                // Draw subtle stars
                stars.forEach { (x, y, mod) ->
                    val radius = (1f + mod * 2f).dp.toPx()
                    val alpha = 0.3f + mod * 0.5f
                    drawCircle(
                        color = NightStarWhite.copy(alpha = alpha),
                        radius = radius,
                        center = Offset(x * w, y * h)
                    )
                }
                
                // A few larger glow circles
                drawCircle(
                    color = NightStarWhite.copy(alpha = 0.05f),
                    radius = 150.dp.toPx(),
                    center = Offset(w * 0.8f, h * 0.2f)
                )
                drawCircle(
                    color = NightMoonGlow.copy(alpha = 0.03f),
                    radius = 250.dp.toPx(),
                    center = Offset(w * 0.2f, h * 0.7f)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "🌙",
                    fontSize = 56.sp
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = timeFormat.format(currentTime),
                        fontSize = 62.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = NightStarWhite,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = secondsFormat.format(currentTime),
                            fontSize = 20.sp,
                            color = NightMoonGlow
                        )
                        Text(
                            text = amPmFormat.format(currentTime),
                            fontSize = 20.sp,
                            color = NightMoonGlow,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                val rules = remember(childId) { com.homesync.app.util.ParentalControlManager.getRules(context, childId) }
                val isCurfew = com.homesync.app.util.ParentalControlManager.isCurfewActiveNow(rules, context)
                val isRemoteLock = ScreenTimeManager.isRemoteLocked(context, childId)
                val lockoutReason = when {
                    isRemoteLock -> "Your device has been locked remotely by your guardian."
                    isCurfew -> "Your device is currently locked due to curfew."
                    remainingSeconds <= 0 -> "Your daily screen time allowance has run out."
                    else -> "Your device has been locked remotely by your guardian."
                }
                
                Text(
                    text = lockoutReason,
                    fontSize = 15.sp,
                    color = NightTextMuted,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Surface(
                    color = NightSkyMid.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                        if (!isLocked && remainingSeconds > 0) {
                            Text(
                                text = "🟢 Access Restored! Time remaining: ${ScreenTimeManager.formatTime(remainingSeconds)}",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF16A34A),
                                textAlign = TextAlign.Center
                            )
                        } else {
                            val lockedUntil = when {
                                isRemoteLock -> "Locked by Guardian"
                                isCurfew -> "Curfew ends at ${rules.curfewEndTime}"
                                remainingSeconds <= 0 -> "Allowance ends for today"
                                else -> "Locked by Guardian"
                            }
                            Text(
                                text = lockedUntil,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = NightStarWhite,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.heightIn(min = 20.dp).weight(1f, fill = false))
                
                if (!isLocked && remainingSeconds > 0) {
                    Button(
                        onClick = onUnlock,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF16A34A),
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = "Open Dashboard Now 🚀",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val notif = com.homesync.app.util.SystemNotification(
                                    title = "Time Extension Request",
                                    message = "Child requested a +15 min screen time extension.",
                                    type = com.homesync.app.util.NotificationType.CHILD_SAFE_CHECKIN,
                                    childCode = childId,
                                    targetRole = "GUARDIAN"
                                )
                                com.homesync.app.util.NotificationManager.addNotification(context, notif)
                                Toast.makeText(context, "Request sent to Guardian!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2563EB),
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                text = "Request +15m from Guardian ⏳",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = {
                                try {
                                    val familyMembers = com.homesync.app.util.FamilyManager.getCachedMembers(context)
                                    val guardian = familyMembers.firstOrNull { it.role == com.homesync.app.util.FamilyRole.GUARDIAN }
                                    val phone = guardian?.phoneNumber?.trim() ?: ""
                                    val uri = if (phone.isNotBlank()) android.net.Uri.parse("tel:$phone") else null
                                    val intent = if (uri != null) {
                                        android.content.Intent(android.content.Intent.ACTION_DIAL, uri)
                                    } else {
                                        android.content.Intent(android.content.Intent.ACTION_DIAL)
                                    }.apply {
                                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Opening dialer...", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NightEmergencyRed,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Phone,
                                contentDescription = "Emergency Call",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Emergency Call",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LockoutScreenPreview() {
    LockoutScreen()
}