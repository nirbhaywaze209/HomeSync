package com.homesync.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

    // Live listener for Guardian remote unlock / +1h commands from Cloud & Local (Dual-Sync: RTDB + Firestore)
    DisposableEffect(childId) {
        val cleanId = childId.trim().uppercase()
        var cancelRtdb: (() -> Unit)? = null

        if (cleanId.isNotBlank()) {
            cancelRtdb = com.homesync.app.util.FirebaseRealtimeSyncManager.listenScreenTimeWithCommand(cleanId) { rem, locked, tot, used, cmdId, cmdTimestamp, curfewOverride ->
                val lastTs = ScreenTimeManager.getLastCommandTimestamp(context, cleanId)
                if (cmdTimestamp > 0 && cmdTimestamp < lastTs) {
                    android.util.Log.i("LockoutScreen", "SCREEN_TIME_COMMAND_STALE_IGNORED commandId=$cmdId cmdTimestamp=$cmdTimestamp lastTimestamp=$lastTs")
                    return@listenScreenTimeWithCommand
                }

                if (!locked || cmdId.startsWith("UNLOCK") || cmdId.startsWith("GRANT_") || cmdId.startsWith("RESET_")) {
                    android.util.Log.i("LockoutScreen", "SCREEN_TIME_COMMAND_APPLIED childCode=$cleanId commandId=$cmdId action=UNLOCK")
                    android.util.Log.i("LockoutScreen", "SCREEN_TIME_UNLOCK_STATE unlocked=true reason=REMOTE_UNLOCK")
                    ScreenTimeManager.saveLastCommand(context, cleanId, cmdId, cmdTimestamp, false)
                    com.homesync.app.util.ParentalControlManager.setCurfewOverride(context, cleanId, true)
                    ScreenTimeManager.setLocalLocked(context, cleanId, false)

                    val actualUsed = if (ScreenTimeManager.hasUsageStatsPermission(context)) {
                        ScreenTimeManager.getRealDeviceUsageTodaySeconds(context).coerceAtLeast(0)
                    } else {
                        ScreenTimeManager.getUsedSeconds(context, cleanId)
                    }
                    val grantedRem = when {
                        cmdId.startsWith("RESET_") -> ScreenTimeManager.DEFAULT_ALLOWANCE_SECONDS
                        cmdId.startsWith("GRANT_") -> (cmdId.removePrefix("GRANT_").substringBefore("_").toIntOrNull() ?: 900)
                        rem > 0 -> rem
                        else -> 1800
                    }
                    val safeTot = (actualUsed + grantedRem).coerceAtLeast(tot)
                    val safeRem = (safeTot - actualUsed).coerceAtLeast(grantedRem)
                    remainingSeconds = safeRem
                    isLocked = false
                    ScreenTimeManager.saveTotalAllowance(context, cleanId, safeTot)
                    ScreenTimeManager.saveRemainingSeconds(context, cleanId, safeRem)
                    ScreenTimeManager.applyRemoteUpdate(context, cleanId, safeRem, false, safeTot, actualUsed)

                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onUnlock()
                    }
                } else if (locked) {
                    isLocked = true
                    remainingSeconds = 0
                    ScreenTimeManager.setLocalLocked(context, cleanId, true)
                    ScreenTimeManager.saveLastCommand(context, cleanId, cmdId, cmdTimestamp, true)
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
            val locked = ScreenTimeManager.isDeviceLocked(context, childId)
            val rem = ScreenTimeManager.getRemainingSeconds(context, childId)
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
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(48.dp))
                
                Text(
                    text = "🌙",
                    fontSize = 72.sp
                )
                
                Spacer(modifier = Modifier.height(32.dp))

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = timeFormat.format(currentTime),
                        fontSize = 72.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = NightStarWhite,
                        letterSpacing = 4.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = secondsFormat.format(currentTime),
                            fontSize = 24.sp,
                            color = NightMoonGlow
                        )
                        Text(
                            text = amPmFormat.format(currentTime),
                            fontSize = 24.sp,
                            color = NightMoonGlow,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))

                val rules = remember(childId) { com.homesync.app.util.ParentalControlManager.getRules(context, childId) }
                val isCurfew = com.homesync.app.util.ParentalControlManager.isCurfewActiveNow(rules, context)
                val lockoutReason = when {
                    isCurfew -> "Your device is currently locked due to curfew."
                    remainingSeconds <= 0 -> "Your daily screen time allowance has run out."
                    else -> "Your device has been locked remotely by your guardian."
                }
                
                Text(
                    text = lockoutReason,
                    fontSize = 16.sp,
                    color = NightTextMuted,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Surface(
                    color = NightSkyMid.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                        if (!isLocked && remainingSeconds > 0) {
                            Text(
                                text = "🟢 Access Restored! Time remaining: ${ScreenTimeManager.formatTime(remainingSeconds)}",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF16A34A),
                                textAlign = TextAlign.Center
                            )
                        } else {
                            val lockedUntil = when {
                                isCurfew -> "Curfew ends at ${rules.curfewEndTime}"
                                remainingSeconds <= 0 -> "Allowance ends for today"
                                else -> "Locked by Guardian"
                            }
                            Text(
                                text = lockedUntil,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = NightStarWhite,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                if (!isLocked && remainingSeconds > 0) {
                    Button(
                        onClick = onUnlock,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF16A34A),
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = "Open Dashboard Now 🚀",
                            fontSize = 16.sp,
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
                                .height(52.dp),
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2563EB),
                                contentColor = Color.White
                            )
                        ) {
                            Text(
                                text = "Request +15m from Guardian ⏳",
                                fontSize = 15.sp,
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
                                .height(52.dp),
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NightEmergencyRed,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Phone,
                                contentDescription = "Emergency Call",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Emergency Call",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LockoutScreenPreview() {
    LockoutScreen()
}