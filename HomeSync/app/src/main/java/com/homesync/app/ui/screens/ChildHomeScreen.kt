package com.homesync.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homesync.app.ui.components.LiveSafetyMap
import com.homesync.app.util.ChildIdManager
import com.homesync.app.util.ChildProfileCloudData
import com.homesync.app.util.ChildQuest
import com.homesync.app.util.ChildQuestManager
import com.homesync.app.util.ChildRewardsManager
import com.homesync.app.util.FamilyManager
import com.homesync.app.util.FamilyMember
import com.homesync.app.util.FamilyRole
import com.homesync.app.util.FamilyTaskManager
import com.homesync.app.util.MemberStatus
import com.homesync.app.util.FirebaseRealtimeSyncManager
import com.homesync.app.util.FirebaseStorageHelper
import com.homesync.app.util.FirebaseSyncManager
import com.homesync.app.util.LocationHelper
import com.homesync.app.util.ParentalControlManager
import com.homesync.app.util.QuestStatus
import com.homesync.app.util.ScreenTimeManager
import com.homesync.app.util.QuickActionCooldownManager
import com.homesync.app.util.SafeZoneManager
import com.homesync.app.util.NotificationManager
import com.homesync.app.util.SystemNotification
import com.homesync.app.util.NotificationType
import com.homesync.app.ui.components.WhatsAppProfileAvatar
import com.homesync.app.ui.components.WhatsAppProfileViewerDialog
import com.homesync.app.ui.components.WhatsAppPhotoOptionsModal
import kotlinx.coroutines.delay

// Strict Brand Colors for Child UI
private val BrandBlue = Color(0xFF2563EB)
private val DeepNavy = Color(0xFF1E3A8A)
private val SoftBg = Color(0xFFF8FAFC)
private val CardWhite = Color(0xFFFFFFFF)
private val TextPrimary = Color(0xFF0F172A)
private val TextSecondary = Color(0xFF64748B)
private val BorderGrey = Color(0xFFE2E8F0)
private val SafeGreen = Color(0xFF16A34A)
private val SafeGreenBg = Color(0xFFDCFCE7)
private val WarningAmber = Color(0xFFF59E0B)
private val WarningAmberBg = Color(0xFFFEF3C7)
private val RestrictionRed = Color(0xFFDC2626)
private val RestrictionRedBg = Color(0xFFFEE2E2)
private val InfoCyan = Color(0xFF0891B2)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChildHomeScreen(
    childName: String = "",
    pairingCode: String = "",
    onTriggerSOS: () -> Unit = {},
    onLockout: () -> Unit = {},
    onLogout: () -> Unit = {}
) {
    val context = LocalContext.current

    val authUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
    var canonicalChildUid by remember {
        mutableStateOf(authUid?.takeIf { it.isNotBlank() } ?: FamilyManager.getOrCreateUserId(context))
    }
    LaunchedEffect(Unit) {
        val latestUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        if (!latestUid.isNullOrBlank() && latestUid != canonicalChildUid) {
            canonicalChildUid = latestUid
        }
    }
    val currentUserId = canonicalChildUid
    var activeFamilyId by remember { mutableStateOf(FamilyManager.getStoredFamilyId(context)) }
    var familyMembers by remember { mutableStateOf(FamilyManager.getCachedMembers(context)) }

    // Authoritatively rehydrate family membership from Firestore on startup
    LaunchedEffect(canonicalChildUid) {
        val storedFid = FamilyManager.getStoredFamilyId(context)
        if (storedFid.isNotBlank() && storedFid != activeFamilyId) {
            activeFamilyId = storedFid
        }
        if (canonicalChildUid.isNotBlank()) {
            FamilyManager.checkUserFamilyMembership(context, canonicalChildUid) { res ->
                if (res is com.homesync.app.util.MembershipResult.Approved && res.familyId.isNotBlank() && res.familyId != activeFamilyId) {
                    activeFamilyId = res.familyId
                    FamilyManager.saveStoredFamilyId(context, res.familyId)
                }
            }
        }
    }

    val approvedGuardian = remember(familyMembers) {
        familyMembers.firstOrNull { it.role == FamilyRole.GUARDIAN && it.status == MemberStatus.APPROVED }
            ?: familyMembers.firstOrNull { it.role == FamilyRole.GUARDIAN }
    }

    var activeChildId by remember(pairingCode) {
        mutableStateOf(pairingCode.ifBlank { ChildIdManager.getDeviceChildId(context) })
    }

    // Diagnostics: SCREEN_TIME_IDENTITY
    LaunchedEffect(canonicalChildUid, activeChildId) {
        if (canonicalChildUid.isNotBlank() && activeChildId.isNotBlank()) {
            android.util.Log.i("ChildHomeScreen", "SCREEN_TIME_IDENTITY childUid=$canonicalChildUid childCode=$activeChildId role=CHILD")
            android.util.Log.i("ChildHomeScreen", "SCREEN_TIME_CHILD_UID uid=$canonicalChildUid")
            android.util.Log.i("ChildHomeScreen", "SCREEN_TIME_CHILD_CODE code=$activeChildId")
            android.util.Log.i("ChildHomeScreen", "SCREEN_TIME_RTDB_PATH path=hs_screentime/$activeChildId")
        }
    }

    // Auto-sync childCode to Firestore member doc and hs_users for canonical family pairing
    LaunchedEffect(canonicalChildUid, activeFamilyId, activeChildId, familyMembers) {
        val effFamilyId = activeFamilyId.ifBlank { FamilyManager.getStoredFamilyId(context) }
        val isApprovedChildInFamily = familyMembers.any {
            (it.userId == canonicalChildUid || (activeChildId.isNotBlank() && it.childCode.equals(activeChildId, ignoreCase = true))) &&
            it.status == MemberStatus.APPROVED
        }
        if (effFamilyId.isNotBlank() && canonicalChildUid.isNotBlank() && activeChildId.isNotBlank() && isApprovedChildInFamily) {
            val db = FirebaseSyncManager.getDb()
            db?.collection("hs_families")?.document(effFamilyId)
                ?.collection("members")?.document(canonicalChildUid)
                ?.set(mapOf("childCode" to activeChildId, "updatedAt" to System.currentTimeMillis()), com.google.firebase.firestore.SetOptions.merge())
            db?.collection("hs_users")?.document(canonicalChildUid)
                ?.set(mapOf("childCode" to activeChildId, "updatedAt" to System.currentTimeMillis()), com.google.firebase.firestore.SetOptions.merge())
        }
    }

    var profileRefreshTrigger by remember { mutableStateOf(0) }
    var childProfileBitmap by remember(activeChildId, canonicalChildUid, profileRefreshTrigger) {
        mutableStateOf(
            com.homesync.app.util.ProfileImageManager.getProfileImage(context, key = "user_$canonicalChildUid")
        )
    }
    LaunchedEffect(profileRefreshTrigger, canonicalChildUid) {
        childProfileBitmap = com.homesync.app.util.ProfileImageManager.getProfileImage(context, key = "user_$canonicalChildUid")
    }
    var savedChildrenList by remember {
        mutableStateOf(ChildIdManager.getAllSavedChildren(context))
    }
    var activeGuardianName by remember {
        val initialName = approvedGuardian?.name?.takeIf { it.isNotBlank() && !com.homesync.app.util.AuthManager.isSarahName(it) }
            ?: com.homesync.app.util.AuthManager.getGuardianName(context)
        mutableStateOf(if (com.homesync.app.util.AuthManager.isSarahName(initialName)) "Guardian" else initialName.ifBlank { "Guardian" })
    }
    var isGuardianConnected by remember { mutableStateOf(approvedGuardian != null || activeFamilyId.isNotBlank()) }

    var activeChildName by remember(childName, activeChildId) {
        val initial = if (childName.isNotBlank() && childName != "Child") childName else ChildIdManager.getChildName(context, activeChildId).ifBlank { "Child" }
        mutableStateOf(if (ChildIdManager.isGuardianOrIgnoredName(context, initial)) "Child" else initial)
    }

    // Authoritative child name sync from family members and automatic purge of guardian names and duplicate codes
    LaunchedEffect(canonicalChildUid, familyMembers, activeGuardianName) {
        val ownMember = familyMembers.firstOrNull { it.userId == canonicalChildUid && it.role == FamilyRole.CHILD }
            ?: familyMembers.firstOrNull { it.role == FamilyRole.CHILD && it.status == MemberStatus.APPROVED && !ChildIdManager.isGuardianOrIgnoredName(context, it.name) }
        if (ownMember != null && ownMember.name.isNotBlank()) {
            val cleanRealName = ownMember.name.trim()
            if (!cleanRealName.equals("Child", ignoreCase = true) && !ChildIdManager.isGuardianOrIgnoredName(context, cleanRealName)) {
                if (activeChildName != cleanRealName) {
                    activeChildName = cleanRealName
                }
                ChildIdManager.purgeGuardianAndDuplicateProfiles(
                    context = context,
                    guardianName = activeGuardianName,
                    currentChildName = cleanRealName,
                    currentChildCode = activeChildId
                )
                savedChildrenList = ChildIdManager.getAllSavedChildren(context)
                profileRefreshTrigger += 1
            }
        }
    }

    // Real-time listener for family member changes and child eviction
    DisposableEffect(activeFamilyId) {
        var listener: com.google.firebase.firestore.ListenerRegistration? = null
        if (activeFamilyId.isNotBlank()) {
            listener = FamilyManager.listenFamilyMembers(context, activeFamilyId) { members ->
                familyMembers = members

                // Check if current child is still an approved member of this family
                val isStillMember = members.any {
                    (it.userId.isNotBlank() && it.userId == currentUserId && it.status == MemberStatus.APPROVED) ||
                    (activeChildId.isNotBlank() && it.childCode.equals(activeChildId, ignoreCase = true) && it.status == MemberStatus.APPROVED)
                }
                if (members.isNotEmpty() && !isStillMember) {
                    android.util.Log.w("ChildHomeScreen", "CHILD_EVICTION: Child $currentUserId / $activeChildId was removed from family $activeFamilyId")
                    FamilyManager.clearFamilySession(context)
                    activeFamilyId = ""
                    Toast.makeText(context, "You have been removed from the family.", Toast.LENGTH_LONG).show()
                    onLogout()
                    return@listenFamilyMembers
                }

                // Auto-detect guardians and siblings from family
                val guardians = members.filter { it.role == FamilyRole.GUARDIAN && it.status == MemberStatus.APPROVED }
                    .ifEmpty { members.filter { it.role == FamilyRole.GUARDIAN } }
                if (guardians.isNotEmpty()) {
                    val primaryGuardian = guardians.first()
                    val cleanGName = primaryGuardian.name.trim()
                    if (cleanGName.isNotBlank() && !com.homesync.app.util.AuthManager.isSarahName(cleanGName)) {
                        activeGuardianName = cleanGName
                        com.homesync.app.util.AuthManager.saveGuardianName(context, cleanGName)
                    }
                    if (primaryGuardian.phoneNumber.isNotBlank()) {
                        com.homesync.app.util.AuthManager.saveGuardianPhone(context, primaryGuardian.phoneNumber)
                    }
                    isGuardianConnected = true
                }
                val siblings = members.filter { it.role == FamilyRole.CHILD && it.status == MemberStatus.APPROVED }
                siblings.forEach { s ->
                    if (s.userId != currentUserId && s.userId.isNotBlank() &&
                        !s.name.equals(activeChildName, ignoreCase = true) &&
                        !ChildIdManager.isGuardianOrIgnoredName(context, s.name)) {
                        ChildIdManager.addSiblingProfile(context, s.name, s.userId)
                    }
                }
                savedChildrenList = ChildIdManager.getAllSavedChildren(context)
                profileRefreshTrigger += 1
                android.util.Log.i("ChildHomeScreen", "PROFILE_UI_UPDATED familyId=$activeFamilyId memberCount=${members.size}")
            }
        }
        onDispose {
            listener?.remove()
        }
    }

    // Real-time listener on hs_users/{canonicalChildUid} to handle instant eviction when familyId is cleared
    DisposableEffect(canonicalChildUid) {
        var userListener: com.google.firebase.firestore.ListenerRegistration? = null
        if (canonicalChildUid.isNotBlank() && !canonicalChildUid.startsWith("HS-", ignoreCase = true)) {
            val db = FirebaseSyncManager.getDb()
            userListener = db?.collection("hs_users")?.document(canonicalChildUid)
                ?.addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
                    val userFamilyId = snapshot.getString("familyId") ?: ""
                    val membershipStatus = snapshot.getString("membershipStatus") ?: snapshot.getString("status") ?: ""
                    val isRemoved = userFamilyId.isBlank() || membershipStatus.equals("NONE", ignoreCase = true) || membershipStatus.equals("REMOVED", ignoreCase = true)
                    if (isRemoved && activeFamilyId.isNotBlank()) {
                        android.util.Log.w("ChildHomeScreen", "CHILD_EVICTION: hs_users familyId cleared for $canonicalChildUid")
                        FamilyManager.clearFamilySession(context)
                        activeFamilyId = ""
                        Toast.makeText(context, "You have been removed from the family.", Toast.LENGTH_LONG).show()
                        onLogout()
                    }
                }
        }
        onDispose {
            userListener?.remove()
        }
    }

    var showManageGuardiansModal by remember { mutableStateOf(false) }
    var editGuardianNameText by remember(activeGuardianName) { mutableStateOf(activeGuardianName) }

    val cameraGuardianLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            val targetGuardianUid = approvedGuardian?.userId?.trim()?.takeIf { it.isNotBlank() }
                ?: familyMembers.firstOrNull { it.role == FamilyRole.GUARDIAN }?.userId?.trim()?.takeIf { it.isNotBlank() }
                ?: ""
            val normalized = com.homesync.app.util.ProfileImageManager.normalizeAvatarBitmap(bitmap)
            if (targetGuardianUid.isNotBlank()) {
                com.homesync.app.util.ProfileImageManager.saveProfileImage(context, normalized, key = "user_$targetGuardianUid")
            }
            com.homesync.app.util.ProfileImageManager.saveProfileImage(context, normalized, key = "guardian")
            profileRefreshTrigger += 1
            Toast.makeText(context, "Guardian photo updated!", Toast.LENGTH_SHORT).show()

            if (targetGuardianUid.isNotBlank()) {
                val requesterUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: currentUserId
                val effFamilyId = activeFamilyId.ifBlank { FamilyManager.getStoredFamilyId(context) }
                android.util.Log.i("ChildHomeScreen", "PROFILE_SYNC_UPLOAD_START requesterUid=$requesterUid targetGuardianUid=$targetGuardianUid familyId=$effFamilyId")
                FirebaseStorageHelper.uploadCanonicalProfilePhoto(context, targetGuardianUid, normalized, effFamilyId) { photoUrl, err ->
                    if (photoUrl.isNotBlank()) {
                        android.util.Log.i("ChildHomeScreen", "PROFILE_SYNC_UPLOAD_SUCCESS requesterUid=$requesterUid targetGuardianUid=$targetGuardianUid familyId=$effFamilyId")
                        if (effFamilyId.isNotBlank()) {
                            FamilyManager.updateMemberProfilePicture(effFamilyId, targetGuardianUid, photoUrl)
                        }
                    } else {
                        android.util.Log.e("ChildHomeScreen", "PROFILE_SYNC_UPLOAD_FAILED requesterUid=$requesterUid targetGuardianUid=$targetGuardianUid familyId=$effFamilyId error=${err ?: "UPLOAD_FAILED"}")
                    }
                }
            }
        }
    }

    val galleryGuardianLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val targetGuardianUid = approvedGuardian?.userId?.trim()?.takeIf { it.isNotBlank() }
                ?: familyMembers.firstOrNull { it.role == FamilyRole.GUARDIAN }?.userId?.trim()?.takeIf { it.isNotBlank() }
                ?: ""
            val key = if (targetGuardianUid.isNotBlank()) "user_$targetGuardianUid" else "guardian"
            val success = com.homesync.app.util.ProfileImageManager.saveProfileImageFromUri(context, uri, key = key)
            if (success) {
                val newBmp = com.homesync.app.util.ProfileImageManager.getProfileImage(context, key = key)
                if (newBmp != null) {
                    com.homesync.app.util.ProfileImageManager.saveProfileImage(context, newBmp, key = "guardian")
                    if (targetGuardianUid.isNotBlank()) {
                        com.homesync.app.util.ProfileImageManager.saveProfileImage(context, newBmp, key = "user_$targetGuardianUid")
                    }
                    profileRefreshTrigger += 1
                    Toast.makeText(context, "Guardian photo updated!", Toast.LENGTH_SHORT).show()

                    if (targetGuardianUid.isNotBlank()) {
                        val requesterUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: currentUserId
                        val effFamilyId = activeFamilyId.ifBlank { FamilyManager.getStoredFamilyId(context) }
                        android.util.Log.i("ChildHomeScreen", "PROFILE_SYNC_UPLOAD_START requesterUid=$requesterUid targetGuardianUid=$targetGuardianUid familyId=$effFamilyId")
                        FirebaseStorageHelper.uploadCanonicalProfilePhoto(context, targetGuardianUid, newBmp, effFamilyId) { photoUrl, err ->
                            if (photoUrl.isNotBlank()) {
                                android.util.Log.i("ChildHomeScreen", "PROFILE_SYNC_UPLOAD_SUCCESS requesterUid=$requesterUid targetGuardianUid=$targetGuardianUid familyId=$effFamilyId")
                                if (effFamilyId.isNotBlank()) {
                                    FamilyManager.updateMemberProfilePicture(effFamilyId, targetGuardianUid, photoUrl)
                                }
                            } else {
                                android.util.Log.e("ChildHomeScreen", "PROFILE_SYNC_UPLOAD_FAILED requesterUid=$requesterUid targetGuardianUid=$targetGuardianUid familyId=$effFamilyId error=${err ?: "UPLOAD_FAILED"}")
                            }
                        }
                    }
                }
            }
        }
    }

    var hasUsageAccessPermission by remember {
        mutableStateOf(ScreenTimeManager.hasUsageStatsPermission(context))
    }
    var usedSecondsToday by remember(activeChildId) {
        mutableIntStateOf(ScreenTimeManager.getUsedSeconds(context, activeChildId))
    }
    var remainingSeconds by remember(activeChildId) {
        mutableIntStateOf(ScreenTimeManager.getRemainingSeconds(context, activeChildId))
    }
    var totalAllowance by remember(activeChildId) {
        mutableIntStateOf(ScreenTimeManager.getTotalAllowance(context, activeChildId))
    }
    var isLocked by remember(activeChildId) {
        mutableStateOf(ScreenTimeManager.isDeviceLocked(context, activeChildId))
    }

    var lastProcessedCommandId by remember(activeChildId) { 
        mutableStateOf(ScreenTimeManager.getLastCommandId(context, activeChildId)) 
    }

    // Real-time listener for remote Lock and Screen Time commands from Guardian via canonical RTDB hs_screentime/{childCode}
    DisposableEffect(activeChildId) {
        var cancelRtdb: (() -> Unit)? = null

        if (activeChildId.isNotBlank()) {
            val cleanActiveChild = activeChildId.trim().lowercase()
            cancelRtdb = FirebaseRealtimeSyncManager.listenScreenTimeWithCommandDetails(activeChildId) { rem, locked, tot, used, cmdId, cmdTimestamp, curfewOverrideFromCloud, targetChildId, commandType, _ ->
                // Per-child command isolation: Ignore command if targeted to another specific child
                val cleanTargetChild = targetChildId.trim().lowercase()
                if (cleanTargetChild.isNotEmpty() && cleanTargetChild != "all" && cleanTargetChild != cleanActiveChild) {
                    android.util.Log.i("ChildHomeScreen", "SCREEN_TIME_COMMAND_IGNORED_TARGET_MISMATCH targetChild=$cleanTargetChild currentChild=$cleanActiveChild cmdId=$cmdId")
                    if (cmdId.isNotBlank() && cmdId != "NONE") {
                        lastProcessedCommandId = cmdId
                    }
                    return@listenScreenTimeWithCommandDetails
                }

                val lastTs = ScreenTimeManager.getLastCommandTimestamp(context, activeChildId)
                if (cmdTimestamp > 0 && cmdTimestamp < (lastTs - 30_000L) && cmdId == lastProcessedCommandId) {
                    android.util.Log.i("ChildHomeScreen", "SCREEN_TIME_COMMAND_STALE_IGNORED commandId=$cmdId cmdTimestamp=$cmdTimestamp lastTimestamp=$lastTs")
                    return@listenScreenTimeWithCommandDetails
                }

                if (cmdTimestamp >= lastTs && cmdId.isNotBlank() && cmdId != "NONE") {
                    ScreenTimeManager.saveLastCommand(context, activeChildId, cmdId, cmdTimestamp, locked)
                }

                totalAllowance = tot

                if (curfewOverrideFromCloud) {
                    ParentalControlManager.setCurfewOverride(context, activeChildId, true)
                }

                // Command versioning: check if a new Guardian command arrived
                if (cmdId.isNotBlank() && cmdId != "NONE" && cmdId != lastProcessedCommandId) {
                    lastProcessedCommandId = cmdId
                    android.util.Log.i("ChildHomeScreen", "SCREEN_TIME_COMMAND_APPLIED childCode=$activeChildId commandId=$cmdId targetChild=$targetChildId commandType=$commandType locked=$locked rem=$rem tot=$tot timestamp=${System.currentTimeMillis()}")

                    if (cmdId.startsWith("LOCK") || commandType.contains("LOCK")) {
                        android.util.Log.i("HomeSyncLatency", "LOCK_STATE_CHANGED childId=$activeChildId targetChild=$targetChildId locked=true timestamp=${System.currentTimeMillis()}")
                        android.util.Log.i("ChildHomeScreen", "SCREEN_TIME_LOCK_STATE locked=true reason=REMOTE_LOCK")
                        isLocked = true
                        remainingSeconds = 0
                        ParentalControlManager.setCurfewOverride(context, activeChildId, false)
                        ScreenTimeManager.setLocalLocked(context, activeChildId, true)
                        ScreenTimeManager.saveRemainingSeconds(context, activeChildId, 0)
                        onLockout()
                        return@listenScreenTimeWithCommandDetails
                    } else if (cmdId.startsWith("UNLOCK") || commandType.contains("UNLOCK")) {
                        android.util.Log.i("ChildHomeScreen", "SCREEN_TIME_UNLOCK_STATE unlocked=true reason=REMOTE_UNLOCK")
                        isLocked = false
                        ParentalControlManager.setCurfewOverride(context, activeChildId, true)
                        ScreenTimeManager.setLocalLocked(context, activeChildId, false)
                        val actualUsed = if (ScreenTimeManager.hasUsageStatsPermission(context)) {
                            ScreenTimeManager.getRealDeviceUsageTodaySeconds(context, activeChildId).coerceAtLeast(0)
                        } else {
                            ScreenTimeManager.getUsedSeconds(context, activeChildId)
                        }
                        usedSecondsToday = actualUsed
                        val currentTot = ScreenTimeManager.getTotalAllowance(context, activeChildId)
                        val effTot = if (tot > 0) tot else currentTot
                        val finalTot = if (effTot <= actualUsed) (actualUsed + (if (rem > 0) rem else 1800)) else effTot
                        val finalRem = (finalTot - actualUsed).coerceAtLeast(0)
                        totalAllowance = finalTot
                        remainingSeconds = finalRem
                        ScreenTimeManager.saveTotalAllowance(context, activeChildId, finalTot)
                        ScreenTimeManager.saveRemainingSeconds(context, activeChildId, finalRem)
                        ScreenTimeManager.saveUsedSeconds(context, activeChildId, actualUsed)
                        return@listenScreenTimeWithCommandDetails
                    } else if (cmdId.startsWith("GRANT_") || commandType == "EXTRA_TIME" || commandType == "GRANT_EXTRA_TIME") {
                        val currentStoredTot = ScreenTimeManager.getTotalAllowance(context, activeChildId)
                        val extraSec = cmdId.split("_").mapNotNull { it.toIntOrNull() }.firstOrNull { it in 60..86400 }
                            ?: (if (tot > 0 && currentStoredTot > 0 && tot > currentStoredTot) (tot - currentStoredTot) else 1800)
                        android.util.Log.i("ChildHomeScreen", "SCREEN_TIME_UNLOCK_STATE unlocked=true reason=GRANT extraSeconds=$extraSec")
                        isLocked = false
                        ParentalControlManager.setCurfewOverride(context, activeChildId, true)
                        ScreenTimeManager.setLocalLocked(context, activeChildId, false)
                        val actualUsed = if (ScreenTimeManager.hasUsageStatsPermission(context)) {
                            ScreenTimeManager.getRealDeviceUsageTodaySeconds(context, activeChildId).coerceAtLeast(0)
                        } else {
                            ScreenTimeManager.getUsedSeconds(context, activeChildId)
                        }
                        usedSecondsToday = actualUsed
                        val baseTot = if (currentStoredTot > 0) currentStoredTot else (if (tot > 0) tot else ScreenTimeManager.DEFAULT_ALLOWANCE_SECONDS)
                        val safeTot = if (baseTot <= actualUsed) (actualUsed + extraSec) else (baseTot + extraSec)
                        val safeRem = (safeTot - actualUsed).coerceAtLeast(0)
                        totalAllowance = safeTot
                        remainingSeconds = safeRem
                        ScreenTimeManager.saveTotalAllowance(context, activeChildId, safeTot)
                        ScreenTimeManager.saveRemainingSeconds(context, activeChildId, safeRem)
                        ScreenTimeManager.saveUsedSeconds(context, activeChildId, actualUsed)
                        return@listenScreenTimeWithCommandDetails
                    } else if (cmdId.startsWith("RESET") || commandType.startsWith("RESET")) {
                        android.util.Log.i("ChildHomeScreen", "SCREEN_TIME_UNLOCK_STATE unlocked=true reason=RESET_COMMAND")
                        isLocked = false
                        ParentalControlManager.setCurfewOverride(context, activeChildId, true)
                        ScreenTimeManager.setLocalLocked(context, activeChildId, false)
                        ScreenTimeManager.recordResetBaseForChild(context, activeChildId)
                        usedSecondsToday = 0
                        val resetAllowance = if (tot > 0) tot else ScreenTimeManager.DEFAULT_ALLOWANCE_SECONDS
                        totalAllowance = resetAllowance
                        remainingSeconds = resetAllowance
                        ScreenTimeManager.saveTotalAllowance(context, activeChildId, resetAllowance)
                        ScreenTimeManager.saveRemainingSeconds(context, activeChildId, resetAllowance)
                        ScreenTimeManager.saveUsedSeconds(context, activeChildId, 0)
                        ScreenTimeManager.updateUsageFromChild(context, activeChildId, 0)
                        return@listenScreenTimeWithCommandDetails
                    } else if (cmdId.startsWith("SET_LIMIT") || commandType == "SET_DAILY_LIMIT") {
                        val allowanceFromCmd = cmdId.split("_").mapNotNull { it.toIntOrNull() }.firstOrNull { it in 60..86400 }
                        val newAllowance = allowanceFromCmd ?: (if (tot > 0) tot else ScreenTimeManager.DEFAULT_ALLOWANCE_SECONDS)
                        android.util.Log.i("ChildHomeScreen", "SCREEN_TIME_SET_DAILY_LIMIT childCode=$activeChildId newAllowance=$newAllowance cmdAllowance=$allowanceFromCmd tot=$tot")
                        val actualUsed = if (ScreenTimeManager.hasUsageStatsPermission(context)) {
                            ScreenTimeManager.getRealDeviceUsageTodaySeconds(context, activeChildId).coerceAtLeast(0)
                        } else {
                            ScreenTimeManager.getUsedSeconds(context, activeChildId)
                        }
                        usedSecondsToday = actualUsed
                        val newRem = (newAllowance - actualUsed).coerceAtLeast(0)
                        totalAllowance = newAllowance
                        remainingSeconds = newRem
                        val shouldLock = (newRem <= 0)
                        isLocked = shouldLock
                        ScreenTimeManager.saveTotalAllowance(context, activeChildId, newAllowance)
                        ScreenTimeManager.saveRemainingSeconds(context, activeChildId, newRem)
                        ScreenTimeManager.saveUsedSeconds(context, activeChildId, actualUsed)
                        ScreenTimeManager.setLocalLocked(context, activeChildId, shouldLock)
                        if (shouldLock) {
                            onLockout()
                        }
                        return@listenScreenTimeWithCommandDetails
                    }
                }

                // Normal state sync
                val isEffectivelyLocked = locked || ScreenTimeManager.isRemoteLocked(context, activeChildId) || ScreenTimeManager.isDeviceLocked(context, activeChildId)
                isLocked = isEffectivelyLocked
                if (isEffectivelyLocked) {
                    android.util.Log.i("HomeSyncLatency", "LOCK_STATE_CHANGED childId=$activeChildId targetChild=$targetChildId locked=true timestamp=${System.currentTimeMillis()}")
                    android.util.Log.i("ChildHomeScreen", "SCREEN_TIME_LOCK_STATE locked=true reason=REMOTE_LOCK")
                    remainingSeconds = 0
                    ParentalControlManager.setCurfewOverride(context, activeChildId, false)
                    ScreenTimeManager.setLocalLocked(context, activeChildId, true)
                    ScreenTimeManager.saveRemainingSeconds(context, activeChildId, 0)
                    onLockout()
                } else {
                    isLocked = false
                    ScreenTimeManager.setLocalLocked(context, activeChildId, false)
                    val actualUsed = if (ScreenTimeManager.hasUsageStatsPermission(context)) {
                        ScreenTimeManager.getRealDeviceUsageTodaySeconds(context, activeChildId).coerceAtLeast(0)
                    } else {
                        used
                    }
                    usedSecondsToday = actualUsed
                    if (tot > 0) {
                        totalAllowance = tot
                        ScreenTimeManager.saveTotalAllowance(context, activeChildId, tot)
                    }
                    val currentStoredTot = ScreenTimeManager.getTotalAllowance(context, activeChildId)
                    val calculatedRem = (currentStoredTot - actualUsed).coerceAtLeast(0)
                    remainingSeconds = calculatedRem
                    ScreenTimeManager.saveRemainingSeconds(context, activeChildId, calculatedRem)
                    ScreenTimeManager.saveUsedSeconds(context, activeChildId, actualUsed)
                }
            }
        }
        onDispose {
            cancelRtdb?.invoke()
        }
    }

    // Real-time listener for Parental Control & Curfew rules from Guardian
    DisposableEffect(activeChildId) {
        val cancel = ParentalControlManager.listenRulesFromCloud(context, activeChildId) { rules ->
            if (!ParentalControlManager.isCurfewOverridden(context, activeChildId) && ParentalControlManager.isCurfewActiveNow(rules, context)) {
                android.util.Log.i("ChildHomeScreen", "SCREEN_TIME_RELOCK_REASON reason=CURFEW")
                ScreenTimeManager.setLocalLocked(context, activeChildId, true)
                isLocked = true
                remainingSeconds = 0
                onLockout()
            }
            val ruleAllowanceSeconds = if (rules.maxDailyAllowanceMinutes > 0) {
                rules.maxDailyAllowanceMinutes * 60
            } else if (rules.maxDailyAllowanceHours > 0) {
                (rules.maxDailyAllowanceHours * 3600).toInt()
            } else 0

            if (ruleAllowanceSeconds > 0) {
                val currentTot = ScreenTimeManager.getTotalAllowance(context, activeChildId)
                if (ruleAllowanceSeconds != currentTot) {
                    val actualUsed = if (ScreenTimeManager.hasUsageStatsPermission(context)) {
                        ScreenTimeManager.getRealDeviceUsageTodaySeconds(context, activeChildId).coerceAtLeast(0)
                    } else {
                        ScreenTimeManager.getUsedSeconds(context, activeChildId)
                    }
                    usedSecondsToday = actualUsed
                    val newRem = (ruleAllowanceSeconds - actualUsed).coerceAtLeast(0)
                    totalAllowance = ruleAllowanceSeconds
                    remainingSeconds = newRem
                    val shouldLock = (newRem <= 0)
                    isLocked = shouldLock
                    ScreenTimeManager.saveTotalAllowance(context, activeChildId, ruleAllowanceSeconds)
                    ScreenTimeManager.saveRemainingSeconds(context, activeChildId, newRem)
                    ScreenTimeManager.saveUsedSeconds(context, activeChildId, actualUsed)
                    ScreenTimeManager.setLocalLocked(context, activeChildId, shouldLock)
                    if (shouldLock) {
                        onLockout()
                    }
                }
            }
        }
        onDispose {
            cancel?.invoke()
        }
    }

    // Periodic real device usage measurement loop using Android UsageStatsManager
    LaunchedEffect(activeChildId, isLocked) {
        var cycleCount = 0
        while (true) {
            kotlinx.coroutines.delay(3000L)
            cycleCount++

            if (activeChildId.isNotBlank()) {
                ScreenTimeManager.checkAndApplyDailyReset(context, activeChildId)
            }

            // 1. Authoritative remote lock check
            if (ScreenTimeManager.isRemoteLocked(context, activeChildId)) {
                android.util.Log.i("ChildHomeScreen", "SCREEN_TIME_RELOCK_REASON reason=REMOTE_LOCK")
                if (!isLocked) isLocked = true
                remainingSeconds = 0
                ScreenTimeManager.setLocalLocked(context, activeChildId, true)
                onLockout()
                continue
            }

            // 2. Check if curfew is active (honoring cloud curfew override)
            val currentRules = ParentalControlManager.getRules(context, activeChildId)
            if (!ParentalControlManager.isCurfewOverridden(context, activeChildId) && ParentalControlManager.isCurfewActiveNow(currentRules, context)) {
                android.util.Log.i("ChildHomeScreen", "SCREEN_TIME_RELOCK_REASON reason=CURFEW")
                if (!isLocked) {
                    isLocked = true
                    ScreenTimeManager.setLocalLocked(context, activeChildId, true)
                }
                remainingSeconds = 0
                onLockout()
                continue
            }

            val permGranted = ScreenTimeManager.hasUsageStatsPermission(context)
            if (permGranted != hasUsageAccessPermission) {
                hasUsageAccessPermission = permGranted
            }

            if (!isLocked) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                val isScreenInteractive = powerManager?.isInteractive ?: true
                val deltaSeconds = if (isScreenInteractive) 3 else 0

                val actualUsed = ScreenTimeManager.recordUsageTick(context, activeChildId, deltaSeconds)
                usedSecondsToday = actualUsed

                val currentTot = ScreenTimeManager.getTotalAllowance(context, activeChildId)
                val calculatedRem = (currentTot - actualUsed).coerceAtLeast(0)
                remainingSeconds = calculatedRem

                android.util.Log.i(
                    "HomeSyncScreenTime",
                    "SCREEN_TIME_MEASURED child identifier=$activeChildId actual used seconds=$actualUsed remaining seconds=$calculatedRem"
                )

                ScreenTimeManager.saveRemainingSeconds(context, activeChildId, remainingSeconds)
                ScreenTimeManager.saveUsedSeconds(context, activeChildId, actualUsed)

                // 3. Genuine quota exhaustion
                if (calculatedRem <= 0 && actualUsed >= currentTot && currentTot > 0 && !isLocked) {
                    android.util.Log.i("ChildHomeScreen", "SCREEN_TIME_RELOCK_REASON reason=QUOTA_EXHAUSTED used=$actualUsed tot=$currentTot")
                    isLocked = true
                    ScreenTimeManager.setLocalLocked(context, activeChildId, true)
                    onLockout()
                    continue
                }
            }

            // Sync to cloud every 15s (5 cycles) and heartbeat every 30s (10 cycles)
            if (cycleCount % 5 == 0 && activeChildId.isNotBlank()) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val realUsed = ScreenTimeManager.getRealDeviceUsageTodaySeconds(context, activeChildId)
                    if (realUsed >= 0) {
                        ScreenTimeManager.updateUsageFromChild(context, activeChildId, realUsed)
                    } else {
                        ScreenTimeManager.syncToCloud(context, activeChildId)
                    }
                }
            }
            if (cycleCount % 10 == 0 && activeChildId.isNotBlank()) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    FirebaseRealtimeSyncManager.updatePresence(activeChildId, isOnline = true)
                }
            }
        }
    }

    var hasLocationPerm by remember { mutableStateOf(LocationHelper.hasLocationPermission(context)) }

    // Permission Launcher for Location Access
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            hasLocationPerm = true
        }
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        com.homesync.app.util.NotificationManager.activeDeviceRole = "CHILD"
        com.homesync.app.util.IdentityDiagnosticHelper.printIdentityDiagnostic(
            context = context,
            screenRole = "CHILD",
            explicitChildCode = activeChildId,
            explicitChildUid = canonicalChildUid
        )
        if (!LocationHelper.hasLocationPermission(context)) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            hasLocationPerm = true
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var currentLocationTriple by remember(activeChildId) {
        mutableStateOf(ChildIdManager.getChildLocation(context, activeChildId) ?: Triple(0.0, 0.0, "Live Location"))
    }

    // Live continuous GPS tracking for Child Device
    DisposableEffect(activeChildId, activeChildName, hasLocationPerm) {
        val callback = if (hasLocationPerm || LocationHelper.hasLocationPermission(context)) {
            LocationHelper.startContinuousLocationUpdates(context) { latLng, addr ->
                ChildIdManager.saveChildLocation(context, activeChildId, latLng.latitude, latLng.longitude, addr)
                currentLocationTriple = Triple(latLng.latitude, latLng.longitude, addr)
                if (activeChildId.isNotBlank()) {
                    com.homesync.app.util.FirebaseRealtimeSyncManager.uploadChildLocation(
                        childCode = activeChildId,
                        childName = activeChildName,
                        latitude = latLng.latitude,
                        longitude = latLng.longitude,
                        address = addr
                    )
                }
            }
        } else null
        onDispose {
            LocationHelper.stopLocationUpdates(context, callback)
        }
    }

    var selectedTab by remember { mutableStateOf("Home") }
    var showExitAppConfirmationModal by remember { mutableStateOf(false) }

    BackHandler {
        if (selectedTab != "Home") {
            selectedTab = "Home"
        } else {
            showExitAppConfirmationModal = true
        }
    }

    var starBalance by remember(canonicalChildUid) {
        val initialCoins = ChildRewardsManager.getStats(context, canonicalChildUid).coins
        mutableIntStateOf(initialCoins)
    }
    var currentXp by remember { mutableIntStateOf(450) }

    // Real-time Cloud Rewards Wallet Listener
    DisposableEffect(canonicalChildUid, activeFamilyId) {
        val reg = ChildRewardsManager.listenWallet(context, activeFamilyId, canonicalChildUid) { wallet ->
            starBalance = wallet.coins
            currentXp = wallet.points
        }
        onDispose { reg?.remove() }
    }

    var activeArcadeTab by remember { mutableStateOf<Int?>(null) }
    var showStarShopModal by remember { mutableStateOf(false) }
    var showSosModal by remember { mutableStateOf(false) }
    var showNotificationSheet by remember { mutableStateOf(false) }

    // Notifications State & Polling
    var notificationsList by remember {
        mutableStateOf(com.homesync.app.util.NotificationManager.getNotifications(context))
    }
    val unreadNotifCount = remember(notificationsList) {
        com.homesync.app.util.NotificationManager.getUnreadCount(context)
    }

    LaunchedEffect(Unit) {
        val notifListener = com.homesync.app.util.FirebaseSyncManager.listenNotificationsFromCloud(context) { incoming ->
            com.homesync.app.util.NotificationManager.addNotificationFromCloud(context, incoming)
            notificationsList = com.homesync.app.util.NotificationManager.getNotifications(context)
            // Instant handshake detection when Guardian sends pairing notification
            if (incoming.type == com.homesync.app.util.NotificationType.CHILD_SAFE_CHECKIN && incoming.targetRole == "CHILD") {
                val parsedGuardian = incoming.message.substringBefore(" has successfully").trim()
                if (parsedGuardian.isNotBlank() && !com.homesync.app.util.AuthManager.isSarahName(parsedGuardian)) {
                    activeGuardianName = parsedGuardian
                    isGuardianConnected = true
                    com.homesync.app.util.AuthManager.saveGuardianName(context, parsedGuardian)
                    profileRefreshTrigger += 1
                }
            }
        }
    }

    // Live Child Quests & Photo Proof State
    val hasApprovedFamily = activeFamilyId.isNotBlank() || FamilyManager.getStoredFamilyId(context).isNotBlank() || com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null
    var activeQuests by remember(hasApprovedFamily) {
        mutableStateOf(
            if (hasApprovedFamily) {
                emptyList()
            } else {
                ChildQuestManager.getQuests(context, pairingCode)
            }
        )
    }

    LaunchedEffect(pairingCode, activeChildId) {
        val targetCode = (if (activeChildId.isNotBlank()) activeChildId else pairingCode).trim().uppercase()
        if (targetCode.isNotBlank()) {
            val currentChildBmp = childProfileBitmap ?: com.homesync.app.util.ProfileImageManager.getProfileImage(context, key = "child_$targetCode")
            val base64 = if (currentChildBmp != null) {
                com.homesync.app.util.TaskProofImageManager.encodeBitmapToBase64(currentChildBmp)
            } else ""

            if (com.homesync.app.util.NetworkHelper.isOnline(context)) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        FirebaseRealtimeSyncManager.syncChildProfile(
                            context,
                            com.homesync.app.util.ChildProfileCloudData(
                                childCode = targetCode,
                                name = activeChildName,
                                avatarBase64 = base64,
                                rewardStars = starBalance,
                                levelLabel = "Lvl 1 Hero",
                                xpProgress = currentXp,
                                isConnected = true,
                                lastActiveTime = System.currentTimeMillis()
                            )
                        )
                    } catch (_: Exception) {}
                }
            }
        }
    }

    DisposableEffect(pairingCode, activeChildId, activeFamilyId, canonicalChildUid) {
        val targetCode = (if (activeChildId.isNotBlank()) activeChildId else pairingCode).trim().uppercase()
        var questsReg: com.google.firebase.firestore.ListenerRegistration? = null
        var cancelChild: (() -> Unit)? = null
        var cancelGuardian: (() -> Unit)? = null
        var childProfileReg: com.google.firebase.firestore.ListenerRegistration? = null

        val effFamilyId = activeFamilyId.ifBlank { FamilyManager.getStoredFamilyId(context) }

        android.util.Log.i("FamilyTaskManager", "TASK_IDENTITY familyId=$effFamilyId firebaseAuthUid=$canonicalChildUid childCode=$targetCode")
        if (canonicalChildUid.startsWith("HS-", ignoreCase = true) || canonicalChildUid == targetCode) {
            android.util.Log.e("FamilyTaskManager", "TASK_IDENTITY_ERROR: canonicalChildUid ($canonicalChildUid) is an HS- pairing code, not a Firebase Auth UID!")
        }

        if (effFamilyId.isNotBlank() && canonicalChildUid.isNotBlank() && !canonicalChildUid.startsWith("HS-", ignoreCase = true)) {
            questsReg = FamilyTaskManager.listenTasksForChild(
                context = context,
                familyId = effFamilyId,
                childUserId = canonicalChildUid,
                onTasksUpdated = { remoteQuests ->
                    // Firestore snapshot is the ONLY authoritative source of truth
                    android.util.Log.i("ChildHomeScreen", "TASK_CHILD_UI_UPDATED childUserId=$canonicalChildUid count=${remoteQuests.size}")
                    activeQuests = remoteQuests
                    // Reconcile and purge pairing code caches so deleted tasks cannot survive or reappear
                    val taskIds = remoteQuests.map { it.id }
                    if (targetCode.isNotBlank()) {
                        ChildQuestManager.purgeLegacyTasksExcept(context, targetCode, taskIds)
                        ChildQuestManager.saveQuestsFromCloud(context, targetCode, remoteQuests)
                    }
                    if (pairingCode.isNotBlank() && pairingCode != targetCode) {
                        ChildQuestManager.purgeLegacyTasksExcept(context, pairingCode, taskIds)
                        ChildQuestManager.saveQuestsFromCloud(context, pairingCode, remoteQuests)
                    }
                }
            )
        } else if (effFamilyId.isNotBlank() || canonicalChildUid.isNotBlank()) {
            // Emits TASK_SYNC_LISTENER_NOT_ATTACHED failure log with exact reason
            FamilyTaskManager.listenTasksForChild(
                context = context,
                familyId = effFamilyId,
                childUserId = canonicalChildUid,
                onTasksUpdated = {}
            )
        } else if (targetCode.isNotBlank() && !hasApprovedFamily) {
            questsReg = com.homesync.app.util.FirebaseSyncManager.listenQuestsFromCloud(context, targetCode) { remoteQuests ->
                ChildQuestManager.saveQuestsFromCloud(context, targetCode, remoteQuests)
                activeQuests = remoteQuests
            }
        }

            cancelChild = FirebaseRealtimeSyncManager.listenChildProfile(context, targetCode) { profileData ->
                if (profileData.avatarBase64.isNotBlank()) {
                    profileRefreshTrigger += 1
                }
            }

            cancelGuardian = FirebaseRealtimeSyncManager.listenGuardianProfile(context, targetCode) { guardianData ->
                val gName = guardianData.guardianName.trim()
                val isSarah = com.homesync.app.util.AuthManager.isSarahName(gName)
                val isValid = gName.isNotBlank() && gName != "User" && gName != "Child" && !isSarah

                if (isValid && guardianData.isConnected) {
                    com.homesync.app.util.AuthManager.saveGuardianName(context, gName)
                    activeGuardianName = gName
                    isGuardianConnected = true
                    profileRefreshTrigger += 1
                } else if (isValid) {
                    com.homesync.app.util.AuthManager.saveGuardianName(context, gName)
                    activeGuardianName = gName
                    isGuardianConnected = false
                } else {
                    isGuardianConnected = false
                }
            }

            childProfileReg = FirebaseSyncManager.getDb()?.collection("hs_child_profiles")?.document(targetCode)
                ?.addSnapshotListener { snapshot, _ ->
                    if (snapshot != null && snapshot.exists()) {
                        val gName = snapshot.getString("guardianName")?.trim() ?: ""
                        val isPaired = snapshot.getBoolean("isPaired") ?: false
                        val isGConnected = snapshot.getBoolean("guardianConnected") ?: isPaired
                        if (isPaired && gName.isNotBlank() && !com.homesync.app.util.AuthManager.isSarahName(gName)) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                activeGuardianName = gName
                                isGuardianConnected = isGConnected
                                com.homesync.app.util.AuthManager.saveGuardianName(context, gName)
                                profileRefreshTrigger += 1
                            }
                        }
                    }
                }

        onDispose {
            questsReg?.remove()
            cancelChild?.invoke()
            cancelGuardian?.invoke()
            childProfileReg?.remove()
        }
    }


    // Track siblings already registered to avoid duplicates when LaunchedEffect re-fires
    val registeredSiblingCodes = remember { mutableSetOf<String>() }

    // Discover siblings — registered ONCE at startup.
    // Previously this re-ran on activeChildId, causing:
    //   sibling discovered → savedChildrenList update → recomposition → re-registration → loop
    LaunchedEffect(activeChildName, activeGuardianName, activeFamilyId, familyMembers) {
        FirebaseRealtimeSyncManager.listenAllChildren(context) { sibling ->
            val sCode = sibling.childCode.trim().uppercase()
            val sName = sibling.name.trim()
            val isIgnored = ChildIdManager.isGuardianOrIgnoredName(context, sName)
            val isSelf = sName.equals(activeChildName, ignoreCase = true) || sCode.equals(activeChildId, ignoreCase = true)
            val isGuardian = sName.equals(activeGuardianName, ignoreCase = true)
            val isApprovedFamilyMember = activeFamilyId.isBlank() || familyMembers.any { it.role == FamilyRole.CHILD && it.status == MemberStatus.APPROVED && it.name.equals(sName, ignoreCase = true) }

            if (sCode.isNotBlank() && sName.isNotBlank() && !isIgnored && !isSelf && !isGuardian && isApprovedFamilyMember
                && !registeredSiblingCodes.contains(sCode)) {
                val isUnpaired = ChildIdManager.isChildUnpaired(context, sCode)
                if (!isUnpaired) {
                    registeredSiblingCodes.add(sCode)
                    ChildIdManager.addSiblingProfile(context, sName, sCode)
                    val updated = ChildIdManager.getAllSavedChildren(context)
                    if (updated != savedChildrenList) {
                        savedChildrenList = updated
                    }
                    profileRefreshTrigger += 1
                }
            }
        }
    }


    var selectedQuestForProof by remember { mutableStateOf<ChildQuest?>(null) }
    var showLogoutConfirmationModal by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = CardWhite,
                contentColor = TextSecondary,
                tonalElevation = 8.dp
            ) {
                val tabs = listOf(
                    "Home" to Icons.Filled.Home,
                    "Games" to Icons.Filled.SportsEsports,
                    "Quests" to Icons.Filled.Checklist,
                    "Safety" to Icons.Filled.Shield
                )
                tabs.forEach { (title, icon) ->
                    val isSelected = selectedTab == title
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selectedTab = title },
                        icon = { Icon(imageVector = icon, contentDescription = title) },
                        label = { Text(title, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = BrandBlue,
                            selectedTextColor = BrandBlue,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = Color(0xFFEFF6FF)
                        )
                    )
                }
            }
        },
        containerColor = SoftBg
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(SoftBg)
        ) {
            // GAMIFIED HERO HEADER STATS BAR
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(DeepNavy, BrandBlue, InfoCyan))
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Level & XP Badge
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color.White.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(14.dp))
                                Text("Lvl 3 Hero", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, maxLines = 1)
                            }
                        }

                        // XP Progress Bar
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(52.dp)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color.White.copy(alpha = 0.25f))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(currentXp / 500f)
                                        .background(WarningAmber, RoundedCornerShape(3.dp))
                                )
                            }
                            Text("$currentXp/500", color = Color.White.copy(alpha = 0.9f), fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1)
                        }
                    }

                    // Right: Star Balance Wallet & Streak Badge
                    Row(
                        modifier = Modifier.wrapContentWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color.White.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                            modifier = Modifier.clickable { showStarShopModal = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text("⭐", fontSize = 11.sp)
                                Text("$starBalance", color = Color(0xFFFDE68A), fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, maxLines = 1)
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(50),
                            color = WarningAmber.copy(alpha = 0.25f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text("🔥", fontSize = 11.sp)
                                Text("5 Days", color = Color(0xFFFDE68A), fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }

            // MAIN SCROLLABLE VIEWPORT
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SoftBg)
            ) {
                when (selectedTab) {
                    "Games" -> ChildGamesHubView(
                        onPlayGame = { tabIndex ->
                            activeArcadeTab = tabIndex
                        },
                        onOpenShop = { showStarShopModal = true },
                        starBalance = starBalance
                    )
                    "Quests" -> ChildQuestsView(
                        quests = activeQuests,
                        onSubmitProofClicked = { quest ->
                            selectedQuestForProof = quest
                        },
                        onRemoveProofClicked = { quest ->
                            val authUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
                            val taskChildId = authUid.ifBlank { currentUserId }.trim()
                            if (activeFamilyId.isNotBlank() && taskChildId.isNotBlank() && !taskChildId.startsWith("HS-", ignoreCase = true)) {
                                FamilyTaskManager.removeTaskProof(
                                    context = context,
                                    familyId = activeFamilyId,
                                    childUserId = taskChildId,
                                    questId = quest.id
                                ) {
                                    Toast.makeText(context, "Uploaded photo proof removed!", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                activeQuests = ChildQuestManager.removeProof(context, activeChildId, quest.id)
                                Toast.makeText(context, "Uploaded photo proof removed!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    "Safety" -> ChildSafetyView(
                        childName = childName,
                        guardianName = activeGuardianName,
                        pairingCode = pairingCode,
                        canonicalChildUid = canonicalChildUid,
                        currentLocation = currentLocationTriple,
                        onSendCheckIn = {
                            val canAct = QuickActionCooldownManager.canPerformAction(context, canonicalChildUid, QuickActionCooldownManager.ACTION_SAFE_CHECKIN)
                            if (!canAct) {
                                val remMin = QuickActionCooldownManager.getRemainingCooldownMinutes(context, canonicalChildUid, QuickActionCooldownManager.ACTION_SAFE_CHECKIN)
                                Toast.makeText(context, "Safe check-in is on cooldown. Please wait ${remMin}m.", Toast.LENGTH_SHORT).show()
                                return@ChildSafetyView
                            }

                            val lastLoc = LocationHelper.getLastKnownLocation(context)
                            val currentLat = if (lastLoc != null && lastLoc.latitude != 0.0) lastLoc.latitude else currentLocationTriple.first
                            val currentLng = if (lastLoc != null && lastLoc.longitude != 0.0) lastLoc.longitude else currentLocationTriple.second
                            val zones = SafeZoneManager.getSafeZones(context)
                            val (isSafe, statusMessage) = SafeZoneManager.checkChildSafetyStatus(currentLat, currentLng, zones)

                            if (!isSafe) {
                                Toast.makeText(context, "⚠️ Check-in can only be used when inside a designated Safe Zone!", Toast.LENGTH_LONG).show()
                                return@ChildSafetyView
                            }

                            val eventTxId = "safe_checkin_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}"
                            QuickActionCooldownManager.recordActionNow(context, activeFamilyId, canonicalChildUid, QuickActionCooldownManager.ACTION_SAFE_CHECKIN)
                            ChildRewardsManager.addRewardsToCloudWallet(
                                context = context,
                                familyId = activeFamilyId,
                                childUid = canonicalChildUid,
                                earnedPoints = 10,
                                earnedCoins = 10,
                                transactionId = eventTxId
                            )
                            NotificationManager.addNotification(
                                context,
                                SystemNotification(
                                    title = "Child Safe Check-in 🟢",
                                    message = "$childName sent check-in: \"I am safe!\" ($statusMessage)",
                                    type = NotificationType.CHILD_SAFE_CHECKIN,
                                    childName = childName,
                                    childCode = pairingCode,
                                    targetRole = "GUARDIAN",
                                    familyId = activeFamilyId,
                                    childUid = canonicalChildUid,
                                    latitude = currentLat,
                                    longitude = currentLng
                                )
                            )
                            Toast.makeText(context, "\"I'm Safe\" check-in sent to guardian! (+10 Stars) 💚", Toast.LENGTH_LONG).show()
                        },
                        onCallGuardian = {
                            val phone = approvedGuardian?.phoneNumber?.takeIf { it.isNotBlank() }
                                ?: com.homesync.app.util.AuthManager.getGuardianPhone(context)
                            if (phone.isNotBlank()) {
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$phone")).apply {
                                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Opening dialer failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "Guardian phone number is not configured in Family Profile.", Toast.LENGTH_LONG).show()
                            }
                        },
                        onMessageGuardian = {
                            val phone = approvedGuardian?.phoneNumber?.takeIf { it.isNotBlank() }
                                ?: com.homesync.app.util.AuthManager.getGuardianPhone(context)
                            if (phone.isNotBlank()) {
                                val cleanPhone = phone.replace(Regex("[^0-9+]"), "")
                                val checkInMsg = "Hi $activeGuardianName, I'm checking in from HomeSync!"
                                try {
                                    // Try WhatsApp first
                                    val waIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        data = android.net.Uri.parse("https://api.whatsapp.com/send?phone=$cleanPhone&text=${android.net.Uri.encode(checkInMsg)}")
                                        `package` = "com.whatsapp"
                                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(waIntent)
                                } catch (_: Exception) {
                                    // Fallback to SMS
                                    try {
                                        val smsIntent = android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:$phone")).apply {
                                            putExtra("sms_body", checkInMsg)
                                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(smsIntent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Could not open messaging app: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else {
                                Toast.makeText(context, "Guardian phone number is not configured in Family Profile.", Toast.LENGTH_LONG).show()
                            }
                        },
                        onOpenSosModal = { showSosModal = true }
                    )
                    "Schedule" -> ChildScheduleView(onBack = { selectedTab = "Home" })
                    "Location" -> ChildLocationView(
                        childName = childName,
                        pairingCode = pairingCode,
                        onBack = { selectedTab = "Home" }
                    )
                    "Profile" -> ChildProfileView(
                        childName = activeChildName,
                        pairingCode = activeChildId,
                        starBalance = starBalance,
                        profileBitmap = childProfileBitmap,
                        onUpdateName = { newName -> activeChildName = newName },
                        onUpdatePhoto = { newBitmap -> childProfileBitmap = newBitmap },
                        onLogout = onLogout,
                        onOpenSchedule = { selectedTab = "Schedule" }
                    )
                    else -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Soft Blue Header
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = Color(0xFFEFF6FF),
                                    shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
                                )
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                // Greeting Row (Responsive Layout)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // 1. Avatar (Fixed compact size: 42dp)
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(CircleShape)
                                            .background(BrandBlue)
                                            .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                                            .clickable { selectedTab = "Profile" },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (childProfileBitmap != null) {
                                            Image(
                                                bitmap = childProfileBitmap!!.asImageBitmap(),
                                                contentDescription = "Child Avatar",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                            )
                                        } else {
                                            Text(
                                                text = activeChildName.take(1).uppercase(),
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 17.sp
                                            )
                                        }
                                    }

                                    // 2. Greeting Column (Flexible with weight 1f)
                                    Column(
                                        modifier = Modifier.weight(1f, fill = true),
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = "SafeFamily Kids",
                                            fontSize = 11.sp,
                                            color = InfoCyan,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "Hey, $activeChildName!",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }

                                    // 3. Header Action Buttons
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        // SHOP Button
                                        Surface(
                                            onClick = { showStarShopModal = true },
                                            color = WarningAmberBg,
                                            shape = RoundedCornerShape(50),
                                            border = BorderStroke(1.dp, Color(0xFFFDE68A)),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                                            ) {
                                                Text("⭐", fontSize = 11.sp)
                                                Text(
                                                    text = "SHOP",
                                                    color = WarningAmber,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }

                                        // Notification Button
                                        Surface(
                                            onClick = {
                                                notificationsList = com.homesync.app.util.NotificationManager.getNotifications(context)
                                                showNotificationSheet = !showNotificationSheet
                                            },
                                            color = CardWhite,
                                            shape = CircleShape,
                                            border = BorderStroke(1.dp, BorderGrey.copy(alpha = 0.5f)),
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                                BadgedBox(
                                                    badge = {
                                                        if (unreadNotifCount > 0) {
                                                            Badge(containerColor = RestrictionRed) {
                                                                Text(
                                                                    unreadNotifCount.toString(),
                                                                    color = Color.White,
                                                                    fontSize = 9.sp,
                                                                    fontWeight = FontWeight.Bold
                                                                )
                                                            }
                                                        }
                                                    }
                                                ) {
                                                    Icon(
                                                        Icons.Filled.Notifications,
                                                        contentDescription = "Notifications",
                                                        tint = TextPrimary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }

                                        // Logout Button
                                        Surface(
                                            onClick = { showLogoutConfirmationModal = true },
                                            color = CardWhite,
                                            shape = CircleShape,
                                            border = BorderStroke(1.dp, BorderGrey.copy(alpha = 0.5f)),
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                                Icon(
                                                    imageVector = Icons.Filled.Logout,
                                                    contentDescription = "Logout",
                                                    tint = RestrictionRed,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                // Friendly Safety Banner
                                val isBannerGuardianConnected = approvedGuardian != null || isGuardianConnected
                                val isBannerPaired = approvedGuardian != null || activeFamilyId.isNotBlank() || (activeGuardianName.isNotBlank() && !activeGuardianName.equals("Guardian", ignoreCase = true))
                                val bannerGuardianName = approvedGuardian?.name?.takeIf { it.isNotBlank() && !com.homesync.app.util.AuthManager.isSarahName(it) }
                                    ?: activeGuardianName.takeIf { it.isNotBlank() && !com.homesync.app.util.AuthManager.isSarahName(it) }
                                    ?: "Guardian"
                                val bannerBorderColor = if (isBannerGuardianConnected) Color(0xFFBBF7D0) else if (isBannerPaired) BorderGrey else Color(0xFFFED7AA)
                                val bannerIconBg = if (isBannerGuardianConnected) SafeGreenBg else if (isBannerPaired) SoftBg else WarningAmberBg
                                val bannerIconTint = if (isBannerGuardianConnected) SafeGreen else if (isBannerPaired) TextSecondary else WarningAmber
                                val bannerBadgeBg = if (isBannerGuardianConnected) SafeGreenBg else if (isBannerPaired) SoftBg else WarningAmberBg
                                val bannerBadgeText = if (isBannerGuardianConnected) SafeGreen else if (isBannerPaired) TextSecondary else WarningAmber

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = CardWhite),
                                    shape = RoundedCornerShape(20.dp),
                                    border = BorderStroke(1.dp, bannerBorderColor)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(CircleShape)
                                                    .background(bannerIconBg),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Filled.Shield, contentDescription = null, tint = bannerIconTint)
                                            }
                                            Column(modifier = Modifier.weight(1f, fill = false)) {
                                                Text(
                                                    if (isBannerGuardianConnected) "You're safe" else if (isBannerPaired) "Guardian Offline" else "Guardian Not Connected",
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                val statusSub = when {
                                                    isBannerGuardianConnected && bannerGuardianName.isNotBlank() && !bannerGuardianName.equals("Guardian", ignoreCase = true) ->
                                                        "Guardian $bannerGuardianName • Connected"
                                                    isBannerGuardianConnected ->
                                                        "Guardian Connected"
                                                    isBannerPaired && bannerGuardianName.isNotBlank() && !bannerGuardianName.equals("Guardian", ignoreCase = true) ->
                                                        "Guardian $bannerGuardianName • Offline"
                                                    isBannerPaired ->
                                                        "Guardian • Offline"
                                                    else ->
                                                        "Not Paired • Waiting for connection"
                                                }

                                                Text(
                                                    statusSub,
                                                    fontSize = 12.sp,
                                                    color = bannerBadgeText,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = bannerBadgeBg,
                                            border = BorderStroke(1.dp, bannerBorderColor)
                                        ) {
                                            Text(
                                                if (isBannerGuardianConnected) "● Safe" else if (isBannerPaired) "● Offline" else "● Unpaired",
                                                color = bannerBadgeText,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }

                                // FAMILY MEMBERS SECTION (View for Child, showing Guardian & Siblings connected via Pairing Code)
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Icon(Icons.Filled.People, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(16.dp))
                                            Text("FAMILY MEMBERS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp)
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Surface(
                                                shape = RoundedCornerShape(50),
                                                color = CardWhite,
                                                border = BorderStroke(1.dp, Color(0xFFBFDBFE))
                                            ) {
                                                Text(
                                                    text = if (activeFamilyId.isNotBlank()) "Family: $activeFamilyId" else "Code: ${activeChildId.ifBlank { pairingCode }}",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = BrandBlue,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                         // 1. Guardian Card (Shows real name when paired, or 'Not Paired' when waiting)
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier
                                                .width(76.dp)
                                                .clickable { showManageGuardiansModal = true }
                                        ) {
                                            val guardianUid = remember(approvedGuardian, familyMembers) {
                                                approvedGuardian?.userId?.trim()?.takeIf { it.isNotBlank() }
                                                    ?: familyMembers.firstOrNull { it.role == FamilyRole.GUARDIAN }?.userId?.trim() ?: ""
                                            }
                                            val guardianBitmap = remember(profileRefreshTrigger, guardianUid) {
                                                if (guardianUid.isNotBlank()) {
                                                    android.util.Log.d("ChildHomeScreen", "PROFILE_GUARDIAN_LOOKUP guardianUid=$guardianUid cacheKey=user_$guardianUid")
                                                    com.homesync.app.util.ProfileImageManager.getProfileImage(context, key = "user_$guardianUid")
                                                        ?: com.homesync.app.util.ProfileImageManager.getProfileImage(context, key = "guardian")
                                                } else {
                                                    com.homesync.app.util.ProfileImageManager.getProfileImage(context, key = "guardian")
                                                        ?: com.homesync.app.util.ProfileImageManager.getProfileImage(context)
                                                }
                                            }
                                            val isPaired = approvedGuardian != null || activeFamilyId.isNotBlank() || (activeGuardianName.isNotBlank() && !activeGuardianName.equals("Guardian", ignoreCase = true))
                                            val displayName = approvedGuardian?.name?.takeIf { it.isNotBlank() && !com.homesync.app.util.AuthManager.isSarahName(it) }
                                                ?: activeGuardianName.takeIf { it.isNotBlank() && !com.homesync.app.util.AuthManager.isSarahName(it) }
                                                ?: "Guardian"
                                            val isConnected = approvedGuardian != null || isGuardianConnected
                                            val statusLabel = when {
                                                isConnected -> "Connected"
                                                isPaired -> "Offline"
                                                else -> "Not Paired"
                                            }
                                            val statusColor = when {
                                                isConnected -> SafeGreen
                                                isPaired -> TextSecondary
                                                else -> WarningAmber
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .size(56.dp)
                                                    .clip(CircleShape)
                                                    .background(if (isConnected) BrandBlue else SoftBg)
                                                    .border(2.dp, statusColor, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (guardianBitmap != null && (isPaired || isConnected)) {
                                                    Image(
                                                        bitmap = guardianBitmap.asImageBitmap(),
                                                        contentDescription = displayName,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                } else if (isPaired || isConnected) {
                                                    Text(
                                                        text = displayName.take(1).uppercase(),
                                                        color = Color.White,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 20.sp
                                                    )
                                                } else {
                                                    Icon(
                                                        Icons.Filled.PersonAdd,
                                                        contentDescription = "Not Paired",
                                                        tint = TextSecondary,
                                                        modifier = Modifier.size(24.dp)
                                                    )
                                                }
                                            }
                                            Text(
                                                text = displayName,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.padding(top = 4.dp).fillMaxWidth()
                                            )
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                                modifier = Modifier.padding(top = 2.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(statusColor)
                                                )
                                                Text(statusLabel, fontSize = 10.sp, color = statusColor, fontWeight = FontWeight.Bold)
                                            }
                                        }


                                        // 2. Family Children Cards (Self & Siblings)
                                        val approvedFamilyChildren = remember(familyMembers, activeChildName, activeChildId, currentUserId) {
                                            val remoteChildren = familyMembers.filter { it.role == FamilyRole.CHILD && it.status == MemberStatus.APPROVED }
                                            if (remoteChildren.isNotEmpty()) {
                                                remoteChildren.map { m ->
                                                    val isSelf = (m.userId.isNotBlank() && m.userId == currentUserId) ||
                                                                 (activeChildId.isNotBlank() && m.childCode.equals(activeChildId, ignoreCase = true)) ||
                                                                 (activeChildName.isNotBlank() && m.name.equals(activeChildName, ignoreCase = true))
                                                    val dispName = if (isSelf && activeChildName.isNotBlank() && !activeChildName.equals("Child", ignoreCase = true)) activeChildName else m.name.ifBlank { "Child" }
                                                    val code = m.childCode.ifBlank { if (isSelf) activeChildId else m.userId }
                                                    Triple(ChildIdManager.formatChildName(dispName), code, isSelf)
                                                }
                                            } else {
                                                listOf(Triple(ChildIdManager.formatChildName(activeChildName.ifBlank { "Child" }), activeChildId, true))
                                            }
                                        }

                                        approvedFamilyChildren.forEach { (cleanName, cCode, isCurrentChild) ->
                                            val siblingUid = FamilyManager.resolveChildUid(cleanName, cCode, familyMembers)
                                            val memberPhotoBitmap = remember(profileRefreshTrigger, cCode, siblingUid) {
                                                com.homesync.app.util.ProfileImageManager.getProfileImage(context, key = "user_$siblingUid")
                                                    ?: (if (siblingUid != cCode) com.homesync.app.util.ProfileImageManager.getProfileImage(context, key = "user_$cCode") else null)
                                                    ?: com.homesync.app.util.ProfileImageManager.getProfileImage(context, key = "child_$cCode")
                                            }
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                modifier = Modifier.width(76.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(56.dp)
                                                        .clip(CircleShape)
                                                        .background(if (cleanName.lowercase().contains("maya")) Color(0xFF8B5CF6) else BrandBlue)
                                                        .border(if (isCurrentChild) 3.dp else 1.dp, if (isCurrentChild) SafeGreen else BorderGrey, CircleShape),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (memberPhotoBitmap != null) {
                                                        Image(
                                                            bitmap = memberPhotoBitmap.asImageBitmap(),
                                                            contentDescription = cleanName,
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier.fillMaxSize()
                                                        )
                                                    } else if (isCurrentChild && childProfileBitmap != null) {
                                                        Image(
                                                            bitmap = childProfileBitmap!!.asImageBitmap(),
                                                            contentDescription = cleanName,
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier.fillMaxSize()
                                                        )
                                                    } else {
                                                        Text(cleanName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                                    }
                                                }
                                                Text(
                                                    text = cleanName,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth()
                                                )
                                                Text(
                                                    text = if (isCurrentChild) "You" else "Sibling",
                                                    fontSize = 10.sp,
                                                    color = if (isCurrentChild) SafeGreen else TextSecondary,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Main Content
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            // Featured Game Banner
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        activeArcadeTab = 0
                                    },
                                colors = CardDefaults.cardColors(containerColor = BrandBlue),
                                shape = RoundedCornerShape(22.dp)
                            ) {
                                Box(modifier = Modifier.padding(18.dp)) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text("🎮 DAILY HABIT & SAFETY BLITZ", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFFDE68A))
                                            Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.2f)) {
                                                Text("+20 Stars", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                            }
                                        }
                                        Text("Star Blitz: Fast Tap Arcade", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.White)
                                        Text("Play the fast zero-lag Star Blitz arcade game & build combo streaks!", fontSize = 12.sp, color = Color(0xFFDBEAFE))
                                        Button(
                                            onClick = {
                                                activeArcadeTab = 0
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = WarningAmber),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.padding(top = 6.dp)
                                        ) {
                                            Text("Play Star Blitz Now 🚀", color = TextPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }

                            // Today's Progress Cards Grid
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("TODAY'S PROGRESS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp)
                                val completedTasksCount = activeQuests.count { it.status == QuestStatus.APPROVED }
                                val totalTasksCount = activeQuests.size
                                val pendingTasksCount = activeQuests.count { it.status != QuestStatus.APPROVED }
                                val nextTask = activeQuests.firstOrNull { it.status == QuestStatus.PENDING }

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    // Screen Time
                                    ChildProgressCard(
                                        title = "Screen Time",
                                        value = "${ScreenTimeManager.formatHoursAndMinutes(usedSecondsToday)} Used",
                                        subtitle = "${ScreenTimeManager.formatHoursAndMinutes(remainingSeconds)} left of ${totalAllowance / 3600}h",
                                        progressText = "${((totalAllowance - remainingSeconds) * 100 / totalAllowance.coerceAtLeast(1)).coerceIn(0, 100)}%",
                                        modifier = Modifier.weight(1f)
                                    )
                                    // My Tasks
                                    ChildProgressCard(
                                        title = "My Quests",
                                        value = if (totalTasksCount == 0) "0 Quests" else "$pendingTasksCount Remaining",
                                        subtitle = if (totalTasksCount == 0) "All caught up! 🎉" else "$completedTasksCount of $totalTasksCount completed",
                                        progressText = if (totalTasksCount == 0) "0/0" else "$completedTasksCount/$totalTasksCount",
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { selectedTab = "Quests" }
                                    )
                                }

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    // Next Activity
                                    ChildProgressCard(
                                        title = "Next Task",
                                        value = nextTask?.title ?: "Free Time! 🚀",
                                        subtitle = nextTask?.dueTime ?: "No pending tasks",
                                        progressText = if (nextTask != null) "${nextTask.rewardStars}⭐" else "✓",
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { selectedTab = "Quests" }
                                    )
                                    // Location
                                    val locStatus = remember(currentLocationTriple) {
                                        val zones = SafeZoneManager.getSafeZones(context)
                                        SafeZoneManager.checkChildSafetyStatus(currentLocationTriple.first, currentLocationTriple.second, zones)
                                    }
                                    ChildProgressCard(
                                        title = "Safety Zone",
                                        value = if (locStatus.first) "Inside Zone" else "Outside Zone",
                                        subtitle = if (locStatus.first) "Protected 🛡️" else "Stay alert ⚠️",
                                        progressText = if (locStatus.first) "🟢" else "📍",
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { selectedTab = "Safety" }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Gamified Arcade Game Full-Screen View Overlay
    AnimatedVisibility(
        visible = activeArcadeTab != null,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    ) {
        if (activeArcadeTab != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F172A))
            ) {
                HabitArcadeGame(
                    childId = pairingCode,
                    childName = childName,
                    initialTab = activeArcadeTab!!,
                    onBackToHome = { activeArcadeTab = null },
                    onRewardsUpdated = { stats ->
                        starBalance = stats.coins
                        currentXp = stats.points
                    }
                )
            }
        }
    }


    // Logout Confirmation Modal
    if (showLogoutConfirmationModal) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmationModal = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🚪", fontSize = 22.sp)
                    Text("Logout of Child Account?", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
            },
            text = {
                Text("Are you sure you want to log out of $childName's Child Mode? You will return to the Login screen.", fontSize = 13.sp, color = TextSecondary)
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutConfirmationModal = false
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RestrictionRed),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Logout 🚪", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirmationModal = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }

    // Manage Guardians & Family Modal for Child UI
    if (showManageGuardiansModal) {
        AlertDialog(
            onDismissRequest = { showManageGuardiansModal = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.People, contentDescription = null, tint = BrandBlue)
                        Text("Manage Guardians", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                    IconButton(onClick = { showManageGuardiansModal = false }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val modalGuardianUid = remember(approvedGuardian, familyMembers) {
                        approvedGuardian?.userId?.trim()?.takeIf { it.isNotBlank() }
                            ?: familyMembers.firstOrNull { it.role == FamilyRole.GUARDIAN }?.userId?.trim() ?: ""
                    }
                    val guardianBitmap = remember(profileRefreshTrigger, modalGuardianUid) {
                        if (modalGuardianUid.isNotBlank()) {
                            android.util.Log.d("ChildHomeScreen", "PROFILE_GUARDIAN_LOOKUP guardianUid=$modalGuardianUid cacheKey=user_$modalGuardianUid")
                            com.homesync.app.util.ProfileImageManager.getProfileImage(context, key = "user_$modalGuardianUid")
                                ?: com.homesync.app.util.ProfileImageManager.getProfileImage(context, key = "guardian")
                        } else {
                            com.homesync.app.util.ProfileImageManager.getProfileImage(context, key = "guardian")
                        }
                    }
                    val modalGuardianDisplayName = approvedGuardian?.name?.takeIf { it.isNotBlank() && !com.homesync.app.util.AuthManager.isSarahName(it) }
                        ?: activeGuardianName.takeIf { it.isNotBlank() && !com.homesync.app.util.AuthManager.isSarahName(it) }
                        ?: "Guardian"
                    val isConnected = approvedGuardian != null || isGuardianConnected

                    // Guardian Card Header
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color(0xFFBFDBFE))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(BrandBlue)
                                    .border(3.dp, if (isConnected) SafeGreen else TextSecondary, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (guardianBitmap != null) {
                                    Image(
                                        bitmap = guardianBitmap.asImageBitmap(),
                                        contentDescription = modalGuardianDisplayName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Text(
                                        text = modalGuardianDisplayName.take(1).uppercase(),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 28.sp
                                    )
                                }
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(modalGuardianDisplayName, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                                Surface(shape = RoundedCornerShape(50), color = if (isConnected) SafeGreenBg else Color(0xFFF1F5F9)) {
                                    Text(
                                        if (isConnected) "● Connected Guardian" else "● Offline Guardian",
                                        fontSize = 11.sp,
                                        color = if (isConnected) SafeGreen else TextSecondary,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = BorderGrey)

                    // Edit Guardian Display Name
                    Text("EDIT GUARDIAN NAME", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp)
                    OutlinedTextField(
                        value = editGuardianNameText,
                        onValueChange = { editGuardianNameText = it },
                        label = { Text("Guardian Display Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            if (editGuardianNameText.isNotBlank()) {
                                val clean = editGuardianNameText.trim()
                                activeGuardianName = clean
                                com.homesync.app.util.AuthManager.saveGuardianName(context, clean)
                                val effFamilyId = activeFamilyId.ifBlank { FamilyManager.getStoredFamilyId(context) }
                                if (modalGuardianUid.isNotBlank() && effFamilyId.isNotBlank()) {
                                    FamilyManager.updateMemberName(effFamilyId, modalGuardianUid, clean)
                                }
                                Toast.makeText(context, "Guardian name updated to $clean", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Save Guardian Name", fontWeight = FontWeight.Bold)
                    }

                    HorizontalDivider(color = BorderGrey)

                    // Change Guardian Photo Option
                    Text("CHANGE GUARDIAN PHOTO", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { cameraGuardianLauncher.launch(null) },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                            Text("Take Photo", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { galleryGuardianLauncher.launch("image/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                            Text("From Gallery", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {},
            containerColor = CardWhite,
            shape = RoundedCornerShape(18.dp)
        )
    }

    // Star Shop Modal
    if (showStarShopModal) {
        AlertDialog(
            onDismissRequest = { showStarShopModal = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("⭐", fontSize = 24.sp)
                    Column {
                        Text("Star Shop Rewards", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                        Text("Balance: $starBalance Stars", fontSize = 12.sp, color = WarningAmber, fontWeight = FontWeight.Bold)
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SoftBg)) {
                        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("Superhero Profile Frame", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
                                Text("Avatar customization", fontSize = 11.sp, color = TextSecondary)
                            }
                            Text("Unlocked ✓", color = SafeGreen, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardWhite), border = BorderStroke(1.dp, BorderGrey)) {
                        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("👑 Golden Superhero Aura", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
                                Text("Exclusive golden profile glow & avatar border", fontSize = 11.sp, color = TextSecondary)
                            }
                            Button(
                                onClick = {
                                    ChildRewardsManager.deductCoinsFromCloudWallet(
                                        context = context,
                                        familyId = activeFamilyId,
                                        childUid = canonicalChildUid,
                                        costCoins = 300,
                                        itemTitle = "Golden Superhero Aura"
                                    ) { success ->
                                        if (success) {
                                            showStarShopModal = false
                                            Toast.makeText(context, "👑 Golden Superhero Aura Badge Unlocked!", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = WarningAmber)
                            ) {
                                Text("300 Stars", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardWhite), border = BorderStroke(1.dp, BorderGrey)) {
                        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("🚀 VIP 2x Star Booster Pass", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
                                Text("Double rewards in Habit Blitz & Arcade games", fontSize = 11.sp, color = TextSecondary)
                            }
                            Button(
                                onClick = {
                                    ChildRewardsManager.deductCoinsFromCloudWallet(
                                        context = context,
                                        familyId = activeFamilyId,
                                        childUid = canonicalChildUid,
                                        costCoins = 250,
                                        itemTitle = "VIP 2x Star Booster"
                                    ) { success ->
                                        if (success) {
                                            showStarShopModal = false
                                            Toast.makeText(context, "🚀 VIP 2x Star Booster Activated!", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                            ) {
                                Text("250 Stars", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardWhite), border = BorderStroke(1.dp, BorderGrey)) {
                        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("🎨 Supernova Neon App Theme", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
                                Text("Vibrant neon custom app skin theme", fontSize = 11.sp, color = TextSecondary)
                            }
                            Button(
                                onClick = {
                                    ChildRewardsManager.deductCoinsFromCloudWallet(
                                        context = context,
                                        familyId = activeFamilyId,
                                        childUid = canonicalChildUid,
                                        costCoins = 400,
                                        itemTitle = "Supernova Neon Theme"
                                    ) { success ->
                                        if (success) {
                                            showStarShopModal = false
                                            Toast.makeText(context, "🎨 Supernova Neon Theme Activated!", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
                            ) {
                                Text("400 Stars", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardWhite), border = BorderStroke(1.dp, BorderGrey)) {
                        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("⏰ 15-Min Extra Screen Time Pass", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
                                Text("Send bonus screen time request to parents", fontSize = 11.sp, color = TextSecondary)
                            }
                            Button(
                                onClick = {
                                    ChildRewardsManager.deductCoinsFromCloudWallet(
                                        context = context,
                                        familyId = activeFamilyId,
                                        childUid = canonicalChildUid,
                                        costCoins = 100,
                                        itemTitle = "15-Min Extra Screen Time"
                                    ) { success ->
                                        if (success) {
                                            showStarShopModal = false
                                            Toast.makeText(context, "🎉 15-Min Screen Time Request Sent to Parents!", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = WarningAmber)
                            ) {
                                Text("100 Stars", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            containerColor = CardWhite
        )
    }

    // Child Notification Center Modal (Messages from Guardian & Safety updates)
    if (showNotificationSheet) {
        AlertDialog(
            onDismissRequest = { showNotificationSheet = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Notifications, contentDescription = null, tint = BrandBlue)
                        Text("Guardian Messages & Alerts", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                    IconButton(onClick = { showNotificationSheet = false }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Mark All Read & Clear All Action Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            com.homesync.app.util.NotificationManager.markAllAsRead(context)
                            notificationsList = com.homesync.app.util.NotificationManager.getNotifications(context)
                        }) {
                            Text("Mark all read", fontSize = 11.sp, color = BrandBlue, fontWeight = FontWeight.Bold)
                        }

                        TextButton(onClick = {
                            com.homesync.app.util.NotificationManager.clearAll(context)
                            notificationsList = emptyList()
                        }) {
                            Text("Clear all", fontSize = 11.sp, color = RestrictionRed, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (notificationsList.isEmpty()) {
                        Surface(
                            color = SoftBg,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                        ) {
                            Text("No new messages or notifications right now.", fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(14.dp), textAlign = TextAlign.Center)
                        }
                    } else {
                        notificationsList.forEach { notif ->
                            val notifBg = if (notif.isRead) SoftBg else Color(0xFFEFF6FF)
                            val notifBorder = if (notif.isRead) BorderGrey else BrandBlue

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        com.homesync.app.util.NotificationManager.markAsRead(context, notif.id)
                                        notificationsList = com.homesync.app.util.NotificationManager.getNotifications(context)
                                    },
                                colors = CardDefaults.cardColors(containerColor = notifBg),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, notifBorder)
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(notif.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        Surface(
                                            color = if (notif.isRead) BorderGrey.copy(alpha = 0.5f) else BrandBlue.copy(alpha = 0.15f),
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                if (notif.isRead) "Read" else "Unread",
                                                fontSize = 10.sp,
                                                color = if (notif.isRead) TextSecondary else BrandBlue,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Text(notif.message, fontSize = 12.sp, color = TextSecondary)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showNotificationSheet = false },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Close", fontWeight = FontWeight.Bold)
                }
            },
            containerColor = CardWhite,
            shape = RoundedCornerShape(18.dp)
        )
    }

    // Manage Guardian / Pairing Info Modal
    if (showManageGuardiansModal) {
        AlertDialog(
            onDismissRequest = { showManageGuardiansModal = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Shield, contentDescription = null, tint = BrandBlue)
                        Text("Guardian Connection", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                    IconButton(onClick = { showManageGuardiansModal = false }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (activeGuardianName.isNotBlank()) {
                        // Paired Guardian Status Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = SoftBg),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, if (isGuardianConnected) Color(0xFFBBF7D0) else BorderGrey)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("PAIRED GUARDIAN", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp)
                                        Text(activeGuardianName, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = if (isGuardianConnected) SafeGreenBg else WarningAmberBg
                                    ) {
                                        Text(
                                            if (isGuardianConnected) "● Connected" else "● Offline",
                                            color = if (isGuardianConnected) SafeGreen else WarningAmber,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                Text(
                                    if (isGuardianConnected)
                                        "Live sync is active. Guardian can manage screen time, quests & safety."
                                    else
                                        "Guardian device is currently offline or connecting.",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                    } else {
                        // Unpaired Status Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = WarningAmberBg),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Color(0xFFFDE68A))
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Filled.Info, contentDescription = null, tint = WarningAmber)
                                Column {
                                    Text("No Guardian Connected Yet", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
                                    Text("Share your pairing code with your parent to connect.", fontSize = 11.sp, color = TextSecondary)
                                }
                            }
                        }
                    }

                    // Device Pairing Code Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, Color(0xFFBFDBFE))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("YOUR PAIRING CODE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = BrandBlue, letterSpacing = 1.sp)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    activeChildId.ifBlank { pairingCode },
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Black,
                                    color = TextPrimary,
                                    letterSpacing = 2.sp
                                )
                                Button(
                                    onClick = {
                                        ChildIdManager.copyIdToClipboard(context, activeChildId.ifBlank { pairingCode })
                                        Toast.makeText(context, "Pairing code copied to clipboard!", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp).padding(end = 4.dp))
                                    Text("Copy", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Text(
                                "Enter this code on the Guardian device under Family Members -> Add to pair.",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showManageGuardiansModal = false },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        val tCode = (if (activeChildId.isNotBlank()) activeChildId else pairingCode).trim().uppercase()
                        Toast.makeText(context, "Checking connection...", Toast.LENGTH_SHORT).show()
                        FirebaseSyncManager.getDb()?.collection("hs_guardian_profiles")?.document(tCode)?.get()
                            ?.addOnSuccessListener { doc ->
                                if (doc != null && doc.exists()) {
                                    val gName = doc.getString("guardianName")?.trim() ?: ""
                                    if (gName.isNotBlank() && !com.homesync.app.util.AuthManager.isSarahName(gName)) {
                                        activeGuardianName = gName
                                        isGuardianConnected = true
                                        com.homesync.app.util.AuthManager.saveGuardianName(context, gName)
                                        profileRefreshTrigger += 1
                                        Toast.makeText(context, "Connected to Guardian $gName!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Waiting for Guardian to pair...", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    // Check hs_child_profiles as secondary channel
                                    FirebaseSyncManager.getDb()?.collection("hs_child_profiles")?.document(tCode)?.get()
                                        ?.addOnSuccessListener { cDoc ->
                                            val gName = cDoc?.getString("guardianName")?.trim() ?: ""
                                            if (gName.isNotBlank() && !com.homesync.app.util.AuthManager.isSarahName(gName)) {
                                                activeGuardianName = gName
                                                isGuardianConnected = true
                                                com.homesync.app.util.AuthManager.saveGuardianName(context, gName)
                                                profileRefreshTrigger += 1
                                                Toast.makeText(context, "Connected to Guardian $gName!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Waiting for Guardian to pair code $tCode...", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                }
                            }
                            ?.addOnFailureListener {
                                Toast.makeText(context, "Network check error: ${it.localizedMessage}", Toast.LENGTH_SHORT).show()
                            }
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Check Connection 🔄", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = CardWhite,
            shape = RoundedCornerShape(18.dp)
        )
    }


    // SOS Emergency Confirmation Modal
    if (showSosModal) {

        AlertDialog(
            onDismissRequest = { showSosModal = false },
            title = { Text("Send Emergency Alert?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("This will send an immediate loud alert and live location to your parents.", color = TextSecondary, fontSize = 13.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        showSosModal = false
                        val effFamilyId = activeFamilyId.ifBlank { com.homesync.app.util.FamilyManager.getStoredFamilyId(context) }
                        com.homesync.app.util.NotificationManager.addNotification(
                            context,
                            com.homesync.app.util.SystemNotification(
                                title = "EMERGENCY SOS ALERT",
                                message = "EMERGENCY SOS Alert triggered by $activeChildName! Live location active.",
                                type = com.homesync.app.util.NotificationType.SOS_EMERGENCY,
                                childName = activeChildName,
                                childCode = activeChildId,
                                targetRole = "GUARDIAN",
                                familyId = effFamilyId,
                                childUid = canonicalChildUid,
                                latitude = currentLocationTriple.first,
                                longitude = currentLocationTriple.second
                            )
                        )
                        onTriggerSOS()
                        Toast.makeText(context, "Emergency Alert Sent to Parents!", Toast.LENGTH_LONG).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RestrictionRed)
                ) {
                    Text("Yes, Send Alert")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSosModal = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = CardWhite
        )
    }

    // Exit App Confirmation Modal
    if (showExitAppConfirmationModal) {
        AlertDialog(
            onDismissRequest = { showExitAppConfirmationModal = false },
            title = {
                Text("Exit HomeSync Application?", fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Text("Are you sure you want to exit the application?", color = TextSecondary, fontSize = 14.sp)
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExitAppConfirmationModal = false
                        (context as? Activity)?.finish()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RestrictionRed),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Exit", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showExitAppConfirmationModal = false },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Cancel", color = TextPrimary)
                }
            },
            containerColor = CardWhite,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Photo Proof Upload Modal for Quests
    selectedQuestForProof?.let { quest ->
        val effFamilyId = activeFamilyId.ifBlank { FamilyManager.getStoredFamilyId(context) }
        val currentAuthUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: canonicalChildUid
        val taskChildId = currentAuthUid.ifBlank { FamilyManager.getStoredUserId(context) }
        PhotoProofUploadModal(
            quest = quest,
            familyId = effFamilyId,
            childUserId = taskChildId,
            onDismiss = { selectedQuestForProof = null },
            onSubmitProof = { photoLabel, photoUri ->
                val now = System.currentTimeMillis()
                activeQuests = activeQuests.map {
                    if (it.id == quest.id) {
                        it.copy(
                            status = QuestStatus.SUBMITTED,
                            photoProofLabel = photoLabel,
                            photoProofUri = photoUri,
                            submittedAt = now,
                            updatedAt = now
                        )
                    } else it
                }
                if (activeChildId.isNotBlank()) {
                    ChildQuestManager.saveQuestsFromCloud(context, activeChildId, activeQuests)
                }

                if (effFamilyId.isNotBlank()) {
                    FamilyTaskManager.submitTaskProof(
                        context = context,
                        familyId = effFamilyId,
                        childUserId = taskChildId,
                        questId = quest.id,
                        photoLabel = photoLabel,
                        photoUri = photoUri,
                        childName = activeChildName,
                        childCode = activeChildId
                    ) { success ->
                        if (success) {
                            Toast.makeText(context, "Photo proof sent to parents for verification!", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    activeQuests = ChildQuestManager.submitProof(
                        context = context,
                        childId = activeChildId,
                        questId = quest.id,
                        photoLabel = photoLabel,
                        photoUri = photoUri,
                        childName = activeChildName
                    )
                    Toast.makeText(context, "Photo proof sent to parents for verification!", Toast.LENGTH_LONG).show()
                }
                selectedQuestForProof = null
            }
        )
    }
}

// Child Progress Card
@Composable
private fun ChildProgressCard(
    title: String,
    value: String,
    subtitle: String,
    progressText: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.defaultMinSize(minHeight = 98.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, BorderGrey)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    progressText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandBlue,
                    maxLines = 1
                )
            }
            Text(
                value,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                fontSize = 11.sp,
                color = SafeGreen,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// Educational Games Hub View
@Composable
fun ChildGamesHubView(
    onPlayGame: (Int) -> Unit,
    onOpenShop: () -> Unit,
    starBalance: Int
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Gamified Arcade Hub 🎮", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text("Interactive real-time safety games & rewards", fontSize = 12.sp, color = TextSecondary)
            }
            Button(onClick = onOpenShop, colors = ButtonDefaults.buttonColors(containerColor = WarningAmberBg)) {
                Text("⭐ $starBalance Stars", color = WarningAmber, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }

        listOf(
            Triple("⭐ Star Blitz Arcade", "Fast-paced tap game! Collect healthy habits, stars & coins", 0),
            Triple("🧠 Eco Memory Match", "Interactive card flipper! Test your memory & earn S-Rank stars", 1)
        ).forEach { (gTitle, gDesc, tabIndex) ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlayGame(tabIndex) },
                colors = CardDefaults.cardColors(containerColor = CardWhite),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, BorderGrey)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(gTitle, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(gDesc, fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(top = 2.dp))
                    }
                    Button(onClick = { onPlayGame(tabIndex) }, colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)) {
                        Text("Play 🚀", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// Quests View with Photo Proof Verification
@Composable
fun ChildQuestsView(
    quests: List<ChildQuest>,
    onSubmitProofClicked: (ChildQuest) -> Unit,
    onRemoveProofClicked: (ChildQuest) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("My Quests & Tasks 📋", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text("Submit photo proof to parents for verification & earn stars!", fontSize = 12.sp, color = TextSecondary)

        if (quests.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SoftBg),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderGrey)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("🎉", fontSize = 32.sp)
                    Text("No Tasks Right Now!", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("You're all caught up! Enjoy your free time or check back later.", fontSize = 12.sp, color = TextSecondary, textAlign = TextAlign.Center)
                }
            }
        } else {
            quests.forEach { quest ->
                ChildQuestItemCard(
                    quest = quest,
                    onSubmitProofClicked = { onSubmitProofClicked(quest) },
                    onRemoveProofClicked = { onRemoveProofClicked(quest) }
                )
            }
        }
    }
}

@Composable
private fun ChildQuestItemCard(
    quest: ChildQuest,
    onSubmitProofClicked: () -> Unit,
    onRemoveProofClicked: () -> Unit
) {
    val context = LocalContext.current
    val isApproved = quest.status == QuestStatus.APPROVED

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (isApproved) Color(0xFFF0FDF4) else CardWhite),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, if (isApproved) Color(0xFFBBF7D0) else BorderGrey)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = quest.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isApproved) Color(0xFF166534) else TextPrimary,
                    textDecoration = if (isApproved) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp)
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color(quest.status.badgeColorHex).copy(alpha = 0.15f)
                ) {
                    Text(
                        text = quest.status.label,
                        color = Color(quest.status.badgeColorHex),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${quest.dueTime} • +${quest.rewardStars} Stars ⭐",
                    fontSize = 12.sp,
                    color = BrandBlue,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 6.dp)
                )

                if (quest.photoProofLabel.isNotBlank()) {
                    Text(
                        text = "📷 Proof: ${quest.photoProofLabel}",
                        fontSize = 11.sp,
                        color = TextSecondary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Optional Image Proof Thumbnail Preview
            if (quest.photoProofUri.isNotBlank() || quest.photoProofLabel.isNotBlank() || quest.status == QuestStatus.SUBMITTED) {
                val proofBitmap = remember(quest.photoProofUri, quest.id) {
                    com.homesync.app.util.TaskProofImageManager.getProofBitmap(context, quest.photoProofUri, quest.id)
                }
                if (proofBitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        Image(
                            bitmap = proofBitmap.asImageBitmap(),
                            contentDescription = "Uploaded proof thumbnail",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            when (quest.status) {
                QuestStatus.PENDING, QuestStatus.REJECTED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onSubmitProofClicked,
                            colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📷 Submit Photo Proof", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        if (quest.photoProofLabel.isNotBlank()) {
                            OutlinedButton(
                                onClick = onRemoveProofClicked,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("🗑️ Remove", fontSize = 12.sp, color = RestrictionRed, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                QuestStatus.SUBMITTED -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            color = Color(0xFFFEF3C7),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "⏳ Photo proof sent to parents! Waiting for verification...",
                                fontSize = 11.sp,
                                color = Color(0xFFD97706),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(10.dp),
                                textAlign = TextAlign.Center
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onSubmitProofClicked,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("🔄 Change / Resend Photo", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = BrandBlue)
                            }

                            OutlinedButton(
                                onClick = onRemoveProofClicked,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("🗑️ Remove", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RestrictionRed)
                            }
                        }
                    }
                }
                QuestStatus.APPROVED -> {
                    Surface(
                        color = Color(0xFFDCFCE7),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "✓ Verified by Parent! +${quest.rewardStars} Stars Deposited 🎉",
                            fontSize = 11.sp,
                            color = Color(0xFF15803D),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(10.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PhotoProofUploadModal(
    quest: ChildQuest,
    familyId: String = "",
    childUserId: String = "",
    onDismiss: () -> Unit,
    onSubmitProof: (String, String) -> Unit
) {
    val context = LocalContext.current
    val effFamilyId = familyId.ifBlank { FamilyManager.getStoredFamilyId(context) }
    val currentAuthUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
    val effChildUserId = if (currentAuthUid.isNotBlank()) currentAuthUid else childUserId.ifBlank { FamilyManager.getStoredUserId(context) }
    var selectedPhotoLabel by remember { mutableStateOf("📸 Task_Photo_Proof.jpg") }
    var photoUriString by remember { mutableStateOf("") }
    var localPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var photoTypeNote by remember { mutableStateOf("Tap camera or gallery to select proof") }
    var isUploadingToStorage by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            val label = "📷 Camera_Snap_${System.currentTimeMillis().toString().takeLast(4)}.jpg"
            selectedPhotoLabel = label
            localPreviewBitmap = bitmap
            photoUriString = "" // Strictly clear URI so local paths are never submitted
            isUploadingToStorage = true
            photoTypeNote = "Uploading HD proof to Firebase... ⏳"
            Toast.makeText(context, "Uploading HD proof to Firebase...", Toast.LENGTH_SHORT).show()
            FirebaseStorageHelper.uploadTaskProofPhoto(
                context = context,
                familyId = effFamilyId,
                childUserId = effChildUserId,
                questId = quest.id,
                bitmap = bitmap
            ) { downloadUrl, error ->
                if (downloadUrl.isNotBlank() && downloadUrl.startsWith("https://")) {
                    photoUriString = downloadUrl
                    isUploadingToStorage = false
                    photoTypeNote = "✓ HD Photo proof synchronized!"
                    Toast.makeText(context, "✓ HD Photo proof ready to submit!", Toast.LENGTH_SHORT).show()
                } else {
                    isUploadingToStorage = false
                    photoUriString = ""
                    photoTypeNote = "❌ Upload failed: ${error ?: "Unknown error"}. Please retake."
                    android.util.Log.w("PhotoProofUploadModal", "Proof upload error: $error")
                    Toast.makeText(context, "Upload failed: ${error ?: "Please try again"}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val label = "🖼️ Gallery_Image_${System.currentTimeMillis().toString().takeLast(4)}.jpg"
            selectedPhotoLabel = label
            val bmp = com.homesync.app.util.TaskProofImageManager.decodeSampledBitmapFromUri(context, uri)
            if (bmp != null) {
                localPreviewBitmap = bmp
                photoUriString = "" // Strictly clear URI so local paths are never submitted
                isUploadingToStorage = true
                photoTypeNote = "Uploading HD proof to Firebase... ⏳"
                Toast.makeText(context, "Uploading HD proof to Firebase...", Toast.LENGTH_SHORT).show()
                FirebaseStorageHelper.uploadTaskProofPhoto(
                    context = context,
                    familyId = effFamilyId,
                    childUserId = effChildUserId,
                    questId = quest.id,
                    bitmap = bmp
                ) { downloadUrl, error ->
                    if (downloadUrl.isNotBlank() && downloadUrl.startsWith("https://")) {
                        photoUriString = downloadUrl
                        isUploadingToStorage = false
                        photoTypeNote = "✓ HD Photo proof synchronized!"
                        Toast.makeText(context, "✓ HD Photo proof ready to submit!", Toast.LENGTH_SHORT).show()
                    } else {
                        isUploadingToStorage = false
                        photoUriString = ""
                        photoTypeNote = "❌ Upload failed: ${error ?: "Unknown error"}. Please retry."
                        android.util.Log.w("PhotoProofUploadModal", "Proof upload error: $error")
                        Toast.makeText(context, "Upload failed: ${error ?: "Please try again"}", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                photoUriString = ""
                localPreviewBitmap = null
                photoTypeNote = "❌ Failed to read selected image."
                Toast.makeText(context, "Cannot read image file from device", Toast.LENGTH_SHORT).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("📷 Task Photo Verification", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Task: ${quest.title}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Text("Take a live photo or choose an image from device gallery to verify completed work:", fontSize = 12.sp, color = TextSecondary)

                // 2 Native Launcher Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { cameraLauncher.launch(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("📷 Take Photo", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("Open Camera", fontSize = 10.sp, color = Color(0xFFDBEAFE))
                        }
                    }

                    Button(
                        onClick = { galleryLauncher.launch("image/*") },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🖼️ Upload File", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("From Gallery", fontSize = 10.sp, color = Color(0xFFE0E7FF))
                        }
                    }
                }

                // Local Preview if bitmap is available
                localPreviewBitmap?.let { bmp ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0F172A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Task proof local preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (isUploadingToStorage) {
                            Surface(
                                color = Color.Black.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                                        Text("Uploading to Firebase...", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }

                // Selected File Badge Indicator
                Surface(
                    color = Color(0xFFF1F5F9),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderGrey),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("ATTACHED PROOF", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                        Text(selectedPhotoLabel, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(
                            photoTypeNote,
                            fontSize = 11.sp,
                            color = when {
                                isUploadingToStorage -> BrandBlue
                                photoUriString.startsWith("https://") -> Color(0xFF16A34A)
                                photoTypeNote.startsWith("❌") -> RestrictionRed
                                else -> TextSecondary
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        },
        confirmButton = {
            val canSubmit = !isUploadingToStorage && photoUriString.isNotBlank() && photoUriString.startsWith("https://")
            Button(
                onClick = {
                    if (canSubmit) {
                        onSubmitProof(selectedPhotoLabel, photoUriString)
                    }
                },
                enabled = canSubmit,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isUploadingToStorage) {
                    Text("Uploading HD Proof... ⏳", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("Submit to Parents 🚀", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

// Screen Time View
@Composable
fun ChildScreenTimeView(
    childId: String = "DEFAULT_CHILD",
    liveRemainingSeconds: Int? = null,
    liveTotalAllowance: Int? = null,
    liveUsedSeconds: Int? = null,
    hasUsagePermission: Boolean = true,
    onRequestUsagePermission: () -> Unit = {}
) {
    val context = LocalContext.current
    val totalAllowance = liveTotalAllowance ?: remember(childId) { ScreenTimeManager.getTotalAllowance(context, childId) }
    val remainingSeconds = liveRemainingSeconds ?: remember(childId) { ScreenTimeManager.getRemainingSeconds(context, childId) }
    val usedSeconds = liveUsedSeconds ?: remember(childId) { ScreenTimeManager.getUsedSeconds(context, childId) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("My Screen Time", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text("Track your daily balance", fontSize = 12.sp, color = TextSecondary)

        if (!hasUsagePermission) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = WarningAmberBg),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color(0xFFFDE68A))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(22.dp))
                        Text("Usage Access Permission Required", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                    Text(
                        "Android requires Usage Access to accurately measure daily app usage on this device. HomeSync does not fabricate usage numbers.",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    Button(
                        onClick = onRequestUsagePermission,
                        colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Grant Usage Access in Settings", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardWhite), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, BorderGrey)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (hasUsagePermission) {
                    Text("${ScreenTimeManager.formatHoursAndMinutes(usedSeconds)} Used Today", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                    Text("${ScreenTimeManager.formatHoursAndMinutes(remainingSeconds)} remaining of ${totalAllowance / 3600}h limit", fontSize = 13.sp, color = InfoCyan, fontWeight = FontWeight.Bold)

                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFEFF6FF)) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = SafeGreen, modifier = Modifier.size(18.dp))
                            Text("Real device app usage measured via Android UsageStats", fontSize = 12.sp, color = BrandBlue, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Text("Usage Stats Paused", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                    Text("Grant permission above to enable real device tracking.", fontSize = 12.sp, color = TextSecondary)
                }

                Surface(shape = RoundedCornerShape(12.dp), color = WarningAmberBg) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Schedule, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(18.dp))
                        Text("Remaining Time: ${ScreenTimeManager.formatTime(remainingSeconds)}", fontSize = 12.sp, color = WarningAmber, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (remainingSeconds <= 0) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, BrandBlue)) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Great job today!", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("Today's screen-time limit has been reached. Time to rest your eyes!", fontSize = 12.sp, color = TextSecondary)
                }
            }
        }
    }
}

// Schedule Sub-View
@Composable
fun ChildScheduleView(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("My Schedule", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            IconButton(onClick = onBack) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        }

        listOf(
            "4:00 PM" to "Arrived Home",
            "6:00 PM" to "Homework Time",
            "7:00 PM" to "Free Time & Educational Games",
            "9:00 PM" to "Bedtime Curfew"
        ).forEach { (time, task) ->
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = CardWhite), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, BorderGrey)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(time, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BrandBlue)
                    Text(task, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

// Location Sub-View
@Composable
fun ChildLocationView(
    childName: String = "",
    pairingCode: String = "",
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        LiveSafetyMap(childName = childName, linkedChildCode = pairingCode, modifier = Modifier.fillMaxWidth(), isReadOnly = true)
    }
}

// Safety Tab View
@Composable
fun ChildSafetyView(
    childName: String = "",
    guardianName: String = "Guardian",
    pairingCode: String = "",
    canonicalChildUid: String = "",
    currentLocation: Triple<Double, Double, String> = Triple(0.0, 0.0, "Live Location"),
    onSendCheckIn: () -> Unit,
    onCallGuardian: () -> Unit,
    onMessageGuardian: () -> Unit,
    onOpenSosModal: () -> Unit
) {
    val context = LocalContext.current
    val activeFamilyId = remember { FamilyManager.getStoredFamilyId(context) }
    var safeZonesList by remember { mutableStateOf(SafeZoneManager.getSafeZones(context)) }

    DisposableEffect(activeFamilyId) {
        val reg = SafeZoneManager.listenSafeZones(context, activeFamilyId) { fresh ->
            safeZonesList = fresh
        }
        onDispose { reg?.remove() }
    }

    val childLat = currentLocation.first
    val childLng = currentLocation.second
    val safetyStatus = remember(childLat, childLng, safeZonesList) {
        if (childLat != 0.0 || childLng != 0.0) {
            SafeZoneManager.checkChildSafetyStatus(childLat, childLng, safeZonesList)
        } else {
            val lastLoc = LocationHelper.getLastKnownLocation(context)
            if (lastLoc != null && (lastLoc.latitude != 0.0 || lastLoc.longitude != 0.0)) {
                SafeZoneManager.checkChildSafetyStatus(lastLoc.latitude, lastLoc.longitude, safeZonesList)
            } else {
                Pair(false, "Acquiring GPS location...")
            }
        }
    }
    val isInSafeZone = safetyStatus.first

    var remainingCooldownMs by remember { mutableStateOf(0L) }
    LaunchedEffect(canonicalChildUid) {
        while (true) {
            val rem = QuickActionCooldownManager.getRemainingCooldownMs(context, canonicalChildUid, QuickActionCooldownManager.ACTION_SAFE_CHECKIN)
            remainingCooldownMs = rem
            kotlinx.coroutines.delay(1000L)
        }
    }
    val isCooldownActive = remainingCooldownMs > 0L
    val cooldownCountdown = if (isCooldownActive) {
        val totalSeconds = (remainingCooldownMs / 1000).coerceAtLeast(1)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    } else ""

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text("Safety & Support 🛡️", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text("Live interactive map, safe zones & quick emergency contact", fontSize = 12.sp, color = TextSecondary)
        }

        // Live Interactive Safety Map matching Guardian Map Experience
        LiveSafetyMap(childName = childName, guardianName = guardianName, linkedChildCode = pairingCode, modifier = Modifier.fillMaxWidth(), isReadOnly = true)

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = {
                    if (!isInSafeZone) {
                        Toast.makeText(context, "⚠️ You can only check-in (+10 Stars) when inside a designated Safe Zone!", Toast.LENGTH_LONG).show()
                    } else {
                        onSendCheckIn()
                    }
                },
                enabled = !isCooldownActive && isInSafeZone,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isInSafeZone) SafeGreen else Color(0xFF94A3B8),
                    disabledContainerColor = if (isCooldownActive) SafeGreen.copy(alpha = 0.5f) else Color(0xFFCBD5E1),
                    contentColor = Color.White,
                    disabledContentColor = if (isCooldownActive) Color.White.copy(alpha = 0.85f) else Color(0xFF64748B)
                ),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = if (isInSafeZone) Icons.Filled.CheckCircle else Icons.Filled.Shield,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = when {
                        isCooldownActive -> "I'm Safe (Cooldown: $cooldownCountdown)"
                        !isInSafeZone -> "I'm Safe (In Safe Zone Only 🛡️)"
                        else -> "I'm Safe (Send Check-In +10 Stars) 🟢"
                    },
                    fontWeight = FontWeight.Bold
                )
            }

            if (!isInSafeZone) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(15.dp))
                    Text(
                        text = if (safeZonesList.isEmpty()) "Guardian has not created any Safe Zones yet." else safetyStatus.second,
                        fontSize = 11.sp,
                        color = WarningAmber,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCallGuardian, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                Text("Call Guardian", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onMessageGuardian, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp)) {
                Text("Message Guardian", fontWeight = FontWeight.Bold)
            }
        }

        Button(
            onClick = onOpenSosModal,
            colors = ButtonDefaults.buttonColors(containerColor = RestrictionRedBg, contentColor = RestrictionRed),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Emergency Contact (SOS)", fontWeight = FontWeight.Bold)
        }
    }
}

// Profile & Read-Only My Limits View
@Composable
fun ChildProfileView(
    childName: String = "",
    pairingCode: String = "",
    starBalance: Int = 0,
    profileBitmap: Bitmap?,
    onUpdateName: (String) -> Unit = {},
    onUpdatePhoto: (Bitmap?) -> Unit = {},
    onLogout: () -> Unit,
    onOpenSchedule: () -> Unit = {}
) {
    val context = LocalContext.current
    var activeChildId by remember(pairingCode) {
        mutableStateOf(pairingCode.ifBlank { ChildIdManager.getDeviceChildId(context) })
    }
    var activeChildName by remember(childName, activeChildId) {
        mutableStateOf(if (childName.isNotBlank() && childName != "Child") childName else ChildIdManager.getChildName(context, activeChildId).ifBlank { "Child" })
    }

    var isEditingName by remember { mutableStateOf(false) }
    var editableNameText by remember(activeChildName) { mutableStateOf(activeChildName) }

    val activeFamilyId = remember { FamilyManager.getStoredFamilyId(context) }
    val canonicalChildUid = remember {
        com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: FamilyManager.getStoredUserId(context)
    }

    // WhatsApp-style Full Screen Profile Picture Viewer States
    var showFullProfileViewer by remember { mutableStateOf(false) }
    var showPhotoOptionsModal by remember { mutableStateOf(false) }
    var viewerTargetBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var viewerTargetTitle by remember { mutableStateOf("Profile Photo") }
    var viewerIsEditable by remember { mutableStateOf(false) }

    // Profile Picture state (Camera & Gallery upload support)
    var localChildProfileBitmap by remember(profileBitmap, canonicalChildUid) {
        mutableStateOf(
            profileBitmap 
                ?: com.homesync.app.util.ProfileImageManager.getProfileImage(context, key = "user_$canonicalChildUid")
        )
    }

    LaunchedEffect(profileBitmap) {
        if (profileBitmap != null && profileBitmap != localChildProfileBitmap) {
            localChildProfileBitmap = profileBitmap
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            val normalized = com.homesync.app.util.ProfileImageManager.normalizeAvatarBitmap(bitmap)
            com.homesync.app.util.ProfileImageManager.saveProfileImage(context, normalized, key = "user_$canonicalChildUid")
            localChildProfileBitmap = normalized
            onUpdatePhoto(normalized)
            Toast.makeText(context, "Profile picture updated!", Toast.LENGTH_SHORT).show()

            val effectiveFamilyId = activeFamilyId.ifBlank { FamilyManager.getStoredFamilyId(context) }
            FirebaseStorageHelper.uploadCanonicalProfilePhoto(context, canonicalChildUid, normalized, effectiveFamilyId) { photoUrl, err ->
                // uploadCanonicalProfilePhoto persists to Firestore and updates canonical cache
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val key = "user_$canonicalChildUid"
            val success = com.homesync.app.util.ProfileImageManager.saveProfileImageFromUri(context, uri, key)
            if (success) {
                val newBitmap = com.homesync.app.util.ProfileImageManager.getProfileImage(context, key)
                if (newBitmap != null) {
                    localChildProfileBitmap = newBitmap
                    onUpdatePhoto(newBitmap)
                    val effectiveFamilyId = activeFamilyId.ifBlank { FamilyManager.getStoredFamilyId(context) }
                    FirebaseStorageHelper.uploadCanonicalProfilePhoto(context, canonicalChildUid, newBitmap, effectiveFamilyId) { photoUrl, err ->
                        // uploadCanonicalProfilePhoto persists to Firestore and updates canonical cache
                    }
                }
                Toast.makeText(context, "Profile picture updated from gallery!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // EDITABLE PROFILE HEADER (Matching Guardian UI Structure)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardWhite),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, BorderGrey)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                WhatsAppProfileAvatar(
                    bitmap = localChildProfileBitmap,
                    name = activeChildName,
                    size = 90.dp,
                    isEditable = true,
                    backgroundColor = BrandBlue,
                    onClick = {
                        viewerTargetBitmap = localChildProfileBitmap
                        viewerTargetTitle = "$activeChildName (Profile Photo)"
                        viewerIsEditable = true
                        showFullProfileViewer = true
                    },
                    onCameraClick = {
                        showPhotoOptionsModal = true
                    }
                )

                // Name & Edit Controls
                if (isEditingName) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = editableNameText,
                            onValueChange = { editableNameText = it },
                            label = { Text("Hero Nickname") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (editableNameText.isNotBlank()) {
                                    val cleanName = editableNameText.trim()
                                    activeChildName = cleanName
                                    ChildIdManager.addChildProfile(context, cleanName, activeChildId)
                                    if (activeFamilyId.isNotBlank()) {
                                        FamilyManager.updateMemberName(activeFamilyId, canonicalChildUid, cleanName)
                                    }
                                    onUpdateName(cleanName)
                                    isEditingName = false
                                    Toast.makeText(context, "Saved name as $cleanName", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SafeGreen)
                        ) {
                            Text("Save", fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(activeChildName, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Edit Name",
                                tint = BrandBlue,
                                modifier = Modifier.size(16.dp).clickable { isEditingName = true }
                            )
                        }
                        Text("Hero Alias: Champion ⭐", fontSize = 12.sp, color = InfoCyan, fontWeight = FontWeight.Bold)
                    }
                }

                // Media Upload Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { cameraLauncher.launch(null) },
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(14.dp).padding(end = 4.dp))
                        Text("Take Photo", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(14.dp).padding(end = 4.dp))
                        Text("From Gallery", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // CHILD ID & PAIRING CODE CARD (For linking with Parent/Guardian)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color(0xFFBFDBFE))
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.QrCode, contentDescription = null, tint = BrandBlue)
                        Text("CHILD PAIRING CODE", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = BrandBlue, letterSpacing = 1.sp)
                    }
                    Surface(shape = RoundedCornerShape(50), color = BrandBlue) {
                        Text("Active ID", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }

                HorizontalDivider(color = Color(0xFFBFDBFE))

                if (activeFamilyId.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Family Group ID:", fontSize = 11.sp, color = TextSecondary)
                            Text(activeFamilyId, fontSize = 24.sp, fontWeight = FontWeight.Black, color = BrandBlue, letterSpacing = 2.sp)
                        }

                        Button(
                            onClick = { 
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Family ID", activeFamilyId)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Family ID $activeFamilyId copied!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp).padding(end = 4.dp))
                            Text("Copy Family ID", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Text(
                        "💡 Family Pairing ID: All Guardians and Children in your family share this single ID ($activeFamilyId).",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Unique Device ID:", fontSize = 11.sp, color = TextSecondary)
                            Text(activeChildId, fontSize = 24.sp, fontWeight = FontWeight.Black, color = TextPrimary, letterSpacing = 2.sp)
                        }

                        Button(
                            onClick = { ChildIdManager.copyIdToClipboard(context, activeChildId) },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp).padding(end = 4.dp))
                            Text("Copy ID", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Text(
                        "💡 Share this code with your Parent / Guardian to join their family.",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }
        }
    }

    if (showFullProfileViewer) {
        WhatsAppProfileViewerDialog(
            bitmap = viewerTargetBitmap,
            title = viewerTargetTitle,
            isEditable = viewerIsEditable,
            onDismiss = { showFullProfileViewer = false },
            onTakePhoto = { cameraLauncher.launch(null) },
            onChooseGallery = { galleryLauncher.launch("image/*") },
            onRemovePhoto = {
                showFullProfileViewer = false
                com.homesync.app.util.ProfileImageManager.clearProfileImage(context, "user_$canonicalChildUid")
                com.homesync.app.util.ProfileImageManager.saveCachedPhotoUrl(context, canonicalChildUid, "")
                val effectiveFamilyId = activeFamilyId.ifBlank { FamilyManager.getStoredFamilyId(context) }
                if (effectiveFamilyId.isNotBlank() && canonicalChildUid.isNotBlank()) {
                    FamilyManager.updateMemberProfilePicture(effectiveFamilyId, canonicalChildUid, "")
                }
                localChildProfileBitmap = null
                onUpdatePhoto(null)
                Toast.makeText(context, "Profile picture removed", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showPhotoOptionsModal) {
        WhatsAppPhotoOptionsModal(
            hasPhoto = (localChildProfileBitmap != null),
            onDismiss = { showPhotoOptionsModal = false },
            onTakePhoto = {
                showPhotoOptionsModal = false
                cameraLauncher.launch(null)
            },
            onChooseGallery = {
                showPhotoOptionsModal = false
                galleryLauncher.launch("image/*")
            },
            onRemovePhoto = {
                showPhotoOptionsModal = false
                com.homesync.app.util.ProfileImageManager.clearProfileImage(context, "user_$canonicalChildUid")
                com.homesync.app.util.ProfileImageManager.saveCachedPhotoUrl(context, canonicalChildUid, "")
                val effectiveFamilyId = activeFamilyId.ifBlank { FamilyManager.getStoredFamilyId(context) }
                if (effectiveFamilyId.isNotBlank() && canonicalChildUid.isNotBlank()) {
                    FamilyManager.updateMemberProfilePicture(effectiveFamilyId, canonicalChildUid, "")
                }
                localChildProfileBitmap = null
                onUpdatePhoto(null)
                Toast.makeText(context, "Profile picture removed", Toast.LENGTH_SHORT).show()
            }
        )
    }
}