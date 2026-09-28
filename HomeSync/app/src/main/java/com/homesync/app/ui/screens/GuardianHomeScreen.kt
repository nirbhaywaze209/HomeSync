package com.homesync.app.ui.screens

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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.homesync.app.ui.components.LiveSafetyMap
import com.homesync.app.util.ChildActivityCloudItem
import com.homesync.app.util.ChildIdManager
import com.homesync.app.util.ChildProfileCloudData
import com.homesync.app.util.ChildQuest
import com.homesync.app.util.ChildQuestManager
import com.homesync.app.util.ChildRewardsManager
import com.homesync.app.util.FirebaseRealtimeSyncManager
import com.homesync.app.util.FirebaseStorageHelper
import com.homesync.app.util.FirebaseSyncManager
import com.homesync.app.util.NotificationManager
import com.homesync.app.util.TaskProofImageManager
import com.homesync.app.util.NotificationType
import com.homesync.app.util.ParentalControlManager
import com.homesync.app.util.ProfileImageManager
import com.homesync.app.util.QuestStatus
import com.homesync.app.util.ScreenTimeManager
import com.homesync.app.util.SystemNotification
import com.homesync.app.util.FamilyManager
import com.homesync.app.util.FamilyMember
import com.homesync.app.util.FamilyRole
import com.homesync.app.util.FamilyJoinRequest
import com.homesync.app.util.FamilyTaskManager
import com.homesync.app.util.MemberStatus
import com.homesync.app.ui.components.WhatsAppProfileAvatar
import com.homesync.app.ui.components.WhatsAppProfileViewerDialog
import com.homesync.app.ui.components.WhatsAppPhotoOptionsModal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Strict Brand Colors
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

data class GuardianChildItem(
    val name: String,
    val childUid: String,
    val childCode: String
)

data class ChildScreenTimeState(
    val remainingSeconds: Int = ScreenTimeManager.DEFAULT_ALLOWANCE_SECONDS,
    val isLocked: Boolean = false,
    val totalAllowanceSeconds: Int = ScreenTimeManager.DEFAULT_ALLOWANCE_SECONDS,
    val usedSeconds: Int = 0
)

/**
 * Maps a saved child tuple (childName, childCode) to their authoritative Firebase Auth UID
 * from the family member roster retrieved from FamilyManager.listenFamilyMembers.
 */
internal fun resolveChildUid(childName: String, childCode: String, members: List<FamilyMember>): String {
    return FamilyManager.resolveChildUid(childName, childCode, members)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardianHomeScreen(
    guardianName: String = "",
    linkedChildCode: String = "",
    onLogout: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val dismissedAlertIds = remember { mutableStateListOf<String>() }
    val deviceChildId = ChildIdManager.getDeviceChildId(context)
    var activeChildCode by remember(linkedChildCode) {
        val allSaved = ChildIdManager.getAllSavedChildren(context)
        // Use linkedChildCode only if it matches a known paired child; otherwise use first saved child
        val resolvedCode = if (linkedChildCode.isNotBlank() && allSaved.any { it.second == linkedChildCode.trim().uppercase() }) {
            linkedChildCode.trim().uppercase()
        } else {
            allSaved.firstOrNull()?.second ?: linkedChildCode.ifBlank { deviceChildId }
        }
        mutableStateOf(resolvedCode)
    }
    var selectedChildUid by remember { mutableStateOf("") }

    var remainingSeconds by remember(activeChildCode) {
        mutableStateOf(ScreenTimeManager.getRemainingSeconds(context, activeChildCode))
    }
    var usedSeconds by remember(activeChildCode) {
        mutableStateOf(ScreenTimeManager.getUsedSeconds(context, activeChildCode))
    }
    var totalAllowanceSeconds by remember(activeChildCode) {
        mutableStateOf(ScreenTimeManager.getTotalAllowance(context, activeChildCode))
    }
    var isLocked by remember(activeChildCode) {
        mutableStateOf(ScreenTimeManager.isDeviceLocked(context, activeChildCode))
    }
    var screenTimeByChild by remember { mutableStateOf<Map<String, ChildScreenTimeState>>(emptyMap()) }
    var tasksByChild by remember { mutableStateOf<Map<String, List<ChildQuest>>>(emptyMap()) }
    var previewImageBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    var selectedTab by remember { mutableStateOf("Home") }
    var showExitAppConfirmationModal by remember { mutableStateOf(false) }

    BackHandler {
        if (selectedTab != "Home") {
            selectedTab = "Home"
        } else {
            showExitAppConfirmationModal = true
        }
    }

    var selectedChildForDetail by remember { mutableStateOf<String?>(null) }
    var showPairChildDialog by remember { mutableStateOf(false) }
    var inputChildCode by remember { mutableStateOf("") }
    var inputChildName by remember { mutableStateOf("") }
    var showManageChildrenModal by remember { mutableStateOf(false) }
    var inputNewChildName by remember { mutableStateOf("") }
    var inputNewChildCode by remember { mutableStateOf("") }

    // Profile & Notification Modals
    var showGuardianProfileModal by remember { mutableStateOf(false) }
    var showNotificationCenterModal by remember { mutableStateOf(false) }
    var activeGuardianName by remember(guardianName) {
        mutableStateOf(
            if (guardianName.isNotBlank() && guardianName != "User" && !com.homesync.app.util.AuthManager.isSarahName(guardianName)) {
                com.homesync.app.util.AuthManager.saveGuardianName(context, guardianName)
                guardianName
            } else {
                com.homesync.app.util.AuthManager.getGuardianName(context).ifBlank { "Guardian" }
            }
        )
    }

    // savedChildrenList is a live state — refreshes on modal changes AND when cloud listeners update it
    var savedChildrenList by remember { mutableStateOf(ChildIdManager.getAllSavedChildren(context)) }
    // Refresh savedChildrenList when manage/pair modals close (to pick up any manual additions)
    LaunchedEffect(showManageChildrenModal, showPairChildDialog) {
        if (!showManageChildrenModal && !showPairChildDialog) {
            val fresh = ChildIdManager.getAllSavedChildren(context)
            if (fresh != savedChildrenList) savedChildrenList = fresh
        }
    }

    var profileRefreshTrigger by remember { mutableStateOf(0) }
    val authUser = remember { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser }
    val currentUserId = remember { authUser?.uid ?: FamilyManager.getStoredUserId(context) }
    var activeFamilyId by remember { mutableStateOf(FamilyManager.getStoredFamilyId(context)) }
    var guardianListenerError by remember { mutableStateOf("") }
    var familyMembers by remember { mutableStateOf(FamilyManager.getCachedMembers(context)) }
    var pendingJoinRequests by remember { mutableStateOf(listOf<FamilyJoinRequest>()) }

    var guardianPhoneNumber by remember { mutableStateOf("") }
    LaunchedEffect(currentUserId, familyMembers) {
        val currentMember = familyMembers.firstOrNull { it.userId == currentUserId }
        if (currentMember != null && currentMember.phoneNumber.isNotBlank() && guardianPhoneNumber.isBlank()) {
            guardianPhoneNumber = currentMember.phoneNumber
        }
    }

    // Authoritative child list computed by prioritizing remote approved family members and merging local profiles
    val effectiveChildren: List<GuardianChildItem> = remember(familyMembers, savedChildrenList, activeFamilyId) {
        val remoteApproved = familyMembers.filter { it.role == FamilyRole.CHILD && it.status == MemberStatus.APPROVED }
        if (activeFamilyId.isNotBlank()) {
            val list = mutableListOf<GuardianChildItem>()
            for (m in remoteApproved) {
                val name = m.name.ifBlank { "Child" }
                val uid = m.userId
                val code = m.childCode.ifBlank { ChildIdManager.resolveChildCode(context, uid, name, familyMembers) }
                list.add(GuardianChildItem(name, uid, code))
            }
            for (saved in savedChildrenList) {
                if (ChildIdManager.isChildUnpaired(context, saved.second) || ChildIdManager.isChildUnpaired(context, saved.first)) continue
                if (list.none { it.childCode.equals(saved.second, ignoreCase = true) || it.childUid.equals(saved.second, ignoreCase = true) || it.name.equals(saved.first, ignoreCase = true) }) {
                    val resolvedUid = resolveChildUid(saved.first, saved.second, familyMembers)
                    val safeUid = if (resolvedUid.startsWith("HS-", ignoreCase = true)) "" else resolvedUid
                    if (safeUid.isNotBlank() && remoteApproved.none { it.userId == safeUid }) {
                        continue
                    }
                    list.add(GuardianChildItem(saved.first, safeUid, saved.second))
                }
            }
            list
        } else {
            savedChildrenList.filter { !ChildIdManager.isChildUnpaired(context, it.second) && !ChildIdManager.isChildUnpaired(context, it.first) }.map { saved ->
                val resolvedUid = resolveChildUid(saved.first, saved.second, familyMembers)
                val safeUid = if (resolvedUid.startsWith("HS-", ignoreCase = true)) "" else resolvedUid
                GuardianChildItem(saved.first, safeUid, saved.second)
            }
        }
    }

    LaunchedEffect(effectiveChildren) {
        if (effectiveChildren.isNotEmpty()) {
            val match = effectiveChildren.find { it.childCode == activeChildCode || it.childUid == selectedChildUid }
            if (match != null) {
                if (activeChildCode != match.childCode && match.childCode.isNotBlank()) {
                    activeChildCode = match.childCode
                }
                if (selectedChildUid != match.childUid && match.childUid.isNotBlank()) {
                    selectedChildUid = match.childUid
                }
            } else {
                val first = effectiveChildren.first()
                activeChildCode = first.childCode
                selectedChildUid = first.childUid
            }
        }
    }

    LaunchedEffect(selectedChildUid, activeChildCode) {
        if (selectedChildUid.isNotBlank() || activeChildCode.isNotBlank()) {
            android.util.Log.i("GuardianHomeScreen", "SCREEN_TIME_GUARDIAN_TARGET childUid=$selectedChildUid childCode=$activeChildCode")
            if (activeChildCode.isNotBlank()) {
                android.util.Log.i("GuardianHomeScreen", "SCREEN_TIME_RTDB_PATH path=hs_screentime/$activeChildCode")
            }
        }
    }

    // Authoritatively resolve Family ID from Firestore hs_users/{userId} on startup
    LaunchedEffect(currentUserId) {
        if (currentUserId.isNotBlank()) {
            val db = FirebaseSyncManager.getDb()
            if (db != null) {
                db.collection("hs_users").document(currentUserId).get()
                    .addOnSuccessListener { uDoc ->
                        if (uDoc.exists()) {
                            val fId = uDoc.getString("familyId") ?: ""
                            val status = uDoc.getString("membershipStatus") ?: uDoc.getString("status") ?: ""
                            val role = uDoc.getString("role") ?: ""
                            if (fId.isNotBlank() && status.equals("APPROVED", ignoreCase = true) && role.equals("GUARDIAN", ignoreCase = true)) {
                                android.util.Log.i("HomeSyncFamily", "GUARDIAN: Authoritatively verified Guardian $currentUserId in Family $fId")
                                if (fId != activeFamilyId) {
                                    activeFamilyId = fId
                                }
                                FamilyManager.saveStoredFamilyId(context, fId)
                                FamilyManager.saveStoredUserRole(context, FamilyRole.GUARDIAN)
                                FamilyManager.saveStoredMemberStatus(context, MemberStatus.APPROVED)

                                // Immediate one-shot fetch of family members to eliminate startup latency
                                db.collection("hs_families").document(fId).collection("members").get()
                                    .addOnSuccessListener { mSnaps ->
                                        if (mSnaps != null && !mSnaps.isEmpty) {
                                            val list = mutableListOf<FamilyMember>()
                                            for (mDoc in mSnaps.documents) {
                                                val uid = mDoc.getString("userId")?.takeIf { it.isNotBlank() }
                                                    ?: mDoc.getString("firebaseAuthUid")?.takeIf { it.isNotBlank() }
                                                    ?: mDoc.id
                                                val name = mDoc.getString("name")?.takeIf { it.isNotBlank() }
                                                    ?: mDoc.getString("displayName")?.takeIf { it.isNotBlank() }
                                                    ?: "Member"
                                                val email = mDoc.getString("email") ?: ""
                                                val rawRole = (mDoc.getString("role") ?: mDoc.getString("userRole") ?: "").trim()
                                                val mRole: FamilyRole? = when {
                                                    rawRole.equals("GUARDIAN", ignoreCase = true) -> FamilyRole.GUARDIAN
                                                    rawRole.equals("CHILD", ignoreCase = true) -> FamilyRole.CHILD
                                                    else -> null
                                                }
                                                val rawStatus = (mDoc.getString("status") ?: mDoc.getString("membershipStatus") ?: "").trim()
                                                val mStatus: MemberStatus? = when {
                                                    rawStatus.equals("APPROVED", ignoreCase = true) -> MemberStatus.APPROVED
                                                    rawStatus.equals("PENDING", ignoreCase = true) -> MemberStatus.PENDING
                                                    rawStatus.equals("REJECTED", ignoreCase = true) -> MemberStatus.REJECTED
                                                    rawStatus.equals("REMOVED", ignoreCase = true) -> MemberStatus.REMOVED
                                                    else -> null
                                                }
                                                if (mRole == null || mStatus != MemberStatus.APPROVED) {
                                                    continue
                                                }
                                                val photoUrl = mDoc.getString("profilePictureUrl") ?: ""
                                                val childCode = (mDoc.getString("childCode") ?: mDoc.getString("pairingCode") ?: "").trim().uppercase()
                                                list.add(
                                                    FamilyMember(
                                                        userId = uid,
                                                        familyId = fId,
                                                        name = name,
                                                        email = email,
                                                        role = mRole,
                                                        status = mStatus,
                                                        profilePictureUrl = photoUrl,
                                                        joinedAt = mDoc.getLong("joinedAt") ?: System.currentTimeMillis(),
                                                        childCode = childCode
                                                    )
                                                )
                                                if (mRole == FamilyRole.CHILD && childCode.isNotBlank() && childCode.startsWith("HS-", ignoreCase = true)) {
                                                    ChildIdManager.addSiblingProfile(context, name, childCode)
                                                }
                                            }
                                            if (list.isNotEmpty()) {
                                                familyMembers = list
                                                FamilyManager.cacheMembers(context, list)
                                                val fresh = ChildIdManager.getAllSavedChildren(context)
                                                if (fresh != savedChildrenList) savedChildrenList = fresh

                                            }
                                        }
                                    }
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        android.util.Log.e("HomeSyncFamily", "GUARDIAN: Error reading hs_users doc", e)
                    }
            }
        }
    }

    // Real-time Family Members and Pending Join Requests listener (cleans up registrations when disposed)
    DisposableEffect(activeFamilyId) {
        var membersReg: com.google.firebase.firestore.ListenerRegistration? = null
        var reqsReg: com.google.firebase.firestore.ListenerRegistration? = null

        if (activeFamilyId.isNotBlank()) {
            membersReg = FamilyManager.listenFamilyMembers(context, activeFamilyId) { members ->
                familyMembers = members
                for (m in members) {
                    if (m.role == FamilyRole.CHILD && m.status == MemberStatus.APPROVED) {
                        val cleanCode = m.childCode.trim().uppercase()
                        if (cleanCode.isNotBlank() && cleanCode.startsWith("HS-", ignoreCase = true)) {
                            ChildIdManager.addSiblingProfile(context, m.name, cleanCode)
                        } else {
                            // Asynchronously query hs_users/{userId} to backfill missing childCode
                            FirebaseSyncManager.getDb()?.collection("hs_users")?.document(m.userId)?.get()
                                ?.addOnSuccessListener { uDoc ->
                                    val fetchedCode = (uDoc.getString("childCode") ?: uDoc.getString("pairingCode") ?: "").trim().uppercase()
                                    if (fetchedCode.isNotBlank() && fetchedCode.startsWith("HS-", ignoreCase = true)) {
                                        ChildIdManager.addSiblingProfile(context, m.name, fetchedCode)
                                        if (activeFamilyId.isNotBlank()) {
                                            FirebaseSyncManager.getDb()?.collection("hs_families")?.document(activeFamilyId)
                                                ?.collection("members")?.document(m.userId)
                                                ?.set(mapOf("childCode" to fetchedCode), com.google.firebase.firestore.SetOptions.merge())
                                        }
                                    }
                                }
                        }
                        android.util.Log.d(
                            "GuardianHomeScreen",
                            "PROFILE_GUARDIAN_CHILD_PHOTO_LOOKUP childName=${m.name} childCode=${m.childCode} childUid=${m.userId} cacheKey=user_${m.userId}"
                        )
                        if (m.profilePictureUrl.isBlank()) {
                            ProfileImageManager.clearProfileImage(context, "user_${m.userId}")
                            ProfileImageManager.clearProfileImage(context, m.userId)
                            ProfileImageManager.clearProfileImage(context, "child_${m.userId}")
                        }
                    }
                }
                val freshSaved = ChildIdManager.getAllSavedChildren(context)
                if (freshSaved != savedChildrenList) {
                    savedChildrenList = freshSaved
                }

                val me = members.find { it.userId == currentUserId }
                if (me != null && me.profilePictureUrl.isBlank()) {
                    ProfileImageManager.clearProfileImage(context, "user_$currentUserId")
                    ProfileImageManager.clearProfileImage(context, "guardian")
                }
                profileRefreshTrigger += 1
                android.util.Log.i("GuardianHomeScreen", "PROFILE_UI_UPDATED familyId=$activeFamilyId memberCount=${members.size}")
            }
            reqsReg = FamilyManager.listenPendingRequests(
                familyId = activeFamilyId,
                onRequestsUpdated = { reqs ->
                    val prevIds = pendingJoinRequests.map { it.userId }.toSet()
                    for (newReq in reqs) {
                        if (!prevIds.contains(newReq.userId)) {
                            // Pop up heads-up notification and badge for Guardian
                            val notif = SystemNotification(
                                id = "req_alert_${newReq.userId}_${newReq.requestedAt}",
                                title = "Family Join Request",
                                message = "${newReq.name} is waiting to join your Family ($activeFamilyId) as ${newReq.role.name}",
                                type = NotificationType.FAMILY_JOIN_REQUEST,
                                childName = newReq.name,
                                childCode = newReq.userId,
                                actionData = activeFamilyId,
                                targetRole = "GUARDIAN"
                            )
                            NotificationManager.addNotification(context, notif)
                        }
                    }
                    pendingJoinRequests = reqs
                },
                onError = { err ->
                    guardianListenerError = err
                    android.util.Log.e("HomeSyncFamily", "GUARDIAN: listenPendingRequests reported error: $err")
                }
            )
        }

        onDispose {
            membersReg?.remove()
            reqsReg?.remove()
        }
    }

    var childQuestsList by remember(activeChildCode, selectedTab) {
        mutableStateOf(ChildQuestManager.getQuests(context, activeChildCode))
    }

    // Persistent Profile Picture state (Loaded from device camera/media)
    var profileBitmap by remember(profileRefreshTrigger, currentUserId) {
        mutableStateOf(
            ProfileImageManager.getProfileImage(context, key = "user_$currentUserId")
        )
    }
    LaunchedEffect(profileRefreshTrigger, currentUserId) {
        profileBitmap = ProfileImageManager.getProfileImage(context, key = "user_$currentUserId")
    }

    // Guardian name sync — authoritatively updates Firestore hs_families & hs_users
    LaunchedEffect(activeGuardianName) {
        val clean = activeGuardianName.ifBlank { com.homesync.app.util.AuthManager.getGuardianName(context) }
        if (clean.isNotBlank() && clean != "User" && clean != "Child" && !clean.equals("Sarah", ignoreCase = true)) {
            com.homesync.app.util.AuthManager.saveGuardianName(context, clean)
            if (activeFamilyId.isNotBlank() && currentUserId.isNotBlank()) {
                FamilyManager.updateMemberName(activeFamilyId, currentUserId, clean)
            }
        }
    }

    // WhatsApp-style Full Screen Profile Picture Viewer States
    var showFullProfileViewer by remember { mutableStateOf(false) }
    var showPhotoOptionsModal by remember { mutableStateOf(false) }
    var viewerTargetBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var viewerTargetTitle by remember { mutableStateOf("Profile Photo") }
    var viewerIsEditable by remember { mutableStateOf(false) }

    var selectedAvatarColor by remember { mutableStateOf(BrandBlue) }

    val cameraProfileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            ProfileImageManager.saveProfileImage(context, bitmap, key = "user_$currentUserId")
            ProfileImageManager.saveProfileImage(context, bitmap, key = "guardian")
            val normalized = ProfileImageManager.getProfileImage(context, key = "user_$currentUserId") ?: ProfileImageManager.normalizeAvatarBitmap(bitmap)
            profileBitmap = normalized
            profileRefreshTrigger += 1
            Toast.makeText(context, "Profile picture updated from camera!", Toast.LENGTH_SHORT).show()
            val requesterUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: currentUserId
            val targetGuardianUid = requesterUid.ifBlank { currentUserId }
            android.util.Log.i("GuardianHomeScreen", "PROFILE_SYNC_UPLOAD_START requesterUid=$requesterUid targetGuardianUid=$targetGuardianUid familyId=$activeFamilyId")
            FirebaseStorageHelper.uploadCanonicalProfilePhoto(context, targetGuardianUid, bitmap, activeFamilyId) { photoUrl, err ->
                if (photoUrl.isNotBlank()) {
                    android.util.Log.i("GuardianHomeScreen", "PROFILE_SYNC_UPLOAD_SUCCESS requesterUid=$requesterUid targetGuardianUid=$targetGuardianUid familyId=$activeFamilyId")
                    if (activeFamilyId.isNotBlank() && targetGuardianUid.isNotBlank()) {
                        FamilyManager.updateMemberProfilePicture(activeFamilyId, targetGuardianUid, photoUrl)
                    }
                } else {
                    android.util.Log.e("GuardianHomeScreen", "PROFILE_SYNC_UPLOAD_FAILED requesterUid=$requesterUid targetGuardianUid=$targetGuardianUid familyId=$activeFamilyId error=${err ?: "UPLOAD_FAILED"}")
                }
            }
        }
    }

    val galleryProfileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val success = ProfileImageManager.saveProfileImageFromUri(context, uri, key = "user_$currentUserId")
            if (success) {
                val newBmp = ProfileImageManager.getProfileImage(context, key = "user_$currentUserId")
                if (newBmp != null) {
                    ProfileImageManager.saveProfileImage(context, newBmp, key = "guardian")
                    profileBitmap = newBmp
                    val requesterUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: currentUserId
                    val targetGuardianUid = requesterUid.ifBlank { currentUserId }
                    android.util.Log.i("GuardianHomeScreen", "PROFILE_SYNC_UPLOAD_START requesterUid=$requesterUid targetGuardianUid=$targetGuardianUid familyId=$activeFamilyId")
                    FirebaseStorageHelper.uploadCanonicalProfilePhoto(context, targetGuardianUid, newBmp, activeFamilyId) { photoUrl, err ->
                        if (photoUrl.isNotBlank()) {
                            android.util.Log.i("GuardianHomeScreen", "PROFILE_SYNC_UPLOAD_SUCCESS requesterUid=$requesterUid targetGuardianUid=$targetGuardianUid familyId=$activeFamilyId")
                            if (activeFamilyId.isNotBlank() && targetGuardianUid.isNotBlank()) {
                                FamilyManager.updateMemberProfilePicture(activeFamilyId, targetGuardianUid, photoUrl)
                            }
                        } else {
                            android.util.Log.e("GuardianHomeScreen", "PROFILE_SYNC_UPLOAD_FAILED requesterUid=$requesterUid targetGuardianUid=$targetGuardianUid familyId=$activeFamilyId error=${err ?: "UPLOAD_FAILED"}")
                        }
                    }
                }
                Toast.makeText(context, "Profile picture updated from media!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var notificationsList by remember {
        mutableStateOf(NotificationManager.getNotifications(context))
    }
    val unreadNotifCount = remember(notificationsList) {
        NotificationManager.getUnreadCount(context)
    }

    var liveCheckInAlertNotification by remember { mutableStateOf<SystemNotification?>(null) }

    // Request Notification permission on Android 13+ for pop-up heads-up alerts
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        NotificationManager.activeDeviceRole = "GUARDIAN"
        com.homesync.app.util.IdentityDiagnosticHelper.printIdentityDiagnostic(
            context = context,
            screenRole = "GUARDIAN",
            explicitChildCode = activeChildCode,
            explicitChildUid = selectedChildUid
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Register FCM Token for Guardian Notifications
    LaunchedEffect(currentUserId) {
        if (currentUserId.isNotBlank()) {
            try {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { token ->
                        if (!token.isNullOrBlank()) {
                            android.util.Log.i("GuardianHomeScreen", "FCM token retrieved: $token")
                            FamilyManager.registerFcmToken(context, token)
                        }
                    }
                    .addOnFailureListener { e ->
                        android.util.Log.w("GuardianHomeScreen", "Failed to retrieve FCM token", e)
                    }
            } catch (e: Exception) {
                android.util.Log.w("GuardianHomeScreen", "Error accessing FirebaseMessaging", e)
            }
        }
    }

    // Live continuous Firestore Real-time Listener for notifications (cleaned up on dispose)
    DisposableEffect(Unit) {
        val listenerRegistration = FirebaseSyncManager.listenNotificationsFromCloud(context) { incomingNotif ->
            NotificationManager.addNotificationFromCloud(context, incomingNotif)
            val freshList = NotificationManager.getNotifications(context)
            notificationsList = freshList
            if (!incomingNotif.isRead && !NotificationManager.isAlertDismissed(context, incomingNotif.id) && !dismissedAlertIds.contains(incomingNotif.id) && incomingNotif.targetRole.equals("GUARDIAN", ignoreCase = true)) {
                liveCheckInAlertNotification = incomingNotif
            }
        }
        onDispose {
            listenerRegistration?.remove()
        }
    }

    // Live cloud discovery for standalone/unpaired mode — only active when not in a family
    DisposableEffect(activeFamilyId) {
        var cancelAllChildren: (() -> Unit)? = null
        if (activeFamilyId.isBlank()) {
            cancelAllChildren = FirebaseRealtimeSyncManager.listenAllChildren(context) { cloudChild ->
                if (cloudChild.childCode.isNotBlank() && cloudChild.name.isNotBlank()) {
                    val isUnpaired = ChildIdManager.isChildUnpaired(context, cloudChild.childCode)
                    if (!isUnpaired) {
                        ChildIdManager.addSiblingProfile(context, cloudChild.name, cloudChild.childCode)
                        val updated = ChildIdManager.getAllSavedChildren(context)
                        if (updated != savedChildrenList) {
                            savedChildrenList = updated
                        }
                        if (activeChildCode.isBlank()) {
                            activeChildCode = cloudChild.childCode
                        }
                    }
                }
            }
        }
        onDispose {
            cancelAllChildren?.invoke()
        }
    }

    // Live listener for all children's live screen time and active child's telemetry
    // Starts for all effective children and cleanly unregisters previous listeners on dispose
    DisposableEffect(activeChildCode, activeFamilyId, effectiveChildren) {
        val screenTimeDisposables = mutableListOf<() -> Unit>()
        var questsReg: com.google.firebase.firestore.ListenerRegistration? = null

        // Ensure Guardian FCM token is registered for SOS alerts
        FamilyManager.ensureFcmTokenRegistered(context)

        val targetCodes = (effectiveChildren.map { it.childCode.trim().uppercase() } + listOf(activeChildCode.trim().uppercase()))
            .filter { it.isNotBlank() }
            .distinct()

        for (cCode in targetCodes) {
            val cancel = FirebaseRealtimeSyncManager.listenScreenTime(cCode) { rem, locked, tot, used ->
                screenTimeByChild = screenTimeByChild + (cCode to ChildScreenTimeState(
                    remainingSeconds = rem,
                    isLocked = locked,
                    totalAllowanceSeconds = tot,
                    usedSeconds = used
                ))
                if (cCode == activeChildCode.trim().uppercase()) {
                    remainingSeconds = rem
                    isLocked = locked
                    usedSeconds = used
                    totalAllowanceSeconds = tot
                }
                ScreenTimeManager.applyRemoteUpdate(context, cCode, rem, locked, tot, used)
            }
            cancel?.let { screenTimeDisposables.add(it) }
        }

        // Live Quests from Firestore (fallback for standalone mode)
        if (activeChildCode.isNotBlank() && activeFamilyId.isBlank()) {
            questsReg = FirebaseSyncManager.listenQuestsFromCloud(context, activeChildCode) { remoteQuests ->
                ChildQuestManager.saveQuestsFromCloud(context, activeChildCode, remoteQuests)
                childQuestsList = remoteQuests
            }
        }

        onDispose {
            screenTimeDisposables.forEach { it.invoke() }
            questsReg?.remove()
        }
    }

    // Real-time snapshot listeners for all approved children tasks under active family
    DisposableEffect(activeFamilyId, effectiveChildren, familyMembers) {
        val listeners = mutableListOf<com.google.firebase.firestore.ListenerRegistration>()
        if (activeFamilyId.isNotBlank()) {
            val targetChildren = mutableListOf<Pair<String, String>>()
            val approvedRemote = familyMembers.filter { it.role == FamilyRole.CHILD && it.status == MemberStatus.APPROVED }
            for (m in approvedRemote) {
                val uid = m.userId.trim()
                val code = m.childCode.trim()
                if (uid.isNotBlank() && !uid.startsWith("HS-", ignoreCase = true)) {
                    targetChildren.add(Pair(uid, code))
                }
            }
            for (c in effectiveChildren) {
                val uid = c.childUid.trim()
                val code = c.childCode.trim()
                if (uid.isNotBlank() && !uid.startsWith("HS-", ignoreCase = true) && targetChildren.none { it.first == uid }) {
                    targetChildren.add(Pair(uid, code))
                } else if (uid.isBlank() && code.isNotBlank()) {
                    val resolvedUid = FamilyManager.resolveChildUid(c.name, code, familyMembers)
                    if (resolvedUid.isNotBlank() && !resolvedUid.startsWith("HS-", ignoreCase = true) && targetChildren.none { it.first == resolvedUid }) {
                        targetChildren.add(Pair(resolvedUid, code))
                    }
                }
            }

            for ((cUid, cCode) in targetChildren) {
                val reg = FamilyTaskManager.listenTasksForChild(
                    context = context,
                    familyId = activeFamilyId,
                    childUserId = cUid,
                    onTasksUpdated = { childTasks ->
                        android.util.Log.i("GuardianHomeScreen", "TASK_GUARDIAN_UI_UPDATED childUserId=$cUid childCode=$cCode count=${childTasks.size}")
                        for (task in childTasks) {
                            if (task.status == QuestStatus.SUBMITTED && task.photoProofUri.startsWith("https://")) {
                                android.util.Log.i("GuardianHomeScreen", "TASK_GUARDIAN_PROOF_RECEIVED taskId=${task.id} childUserId=$cUid photoProofUri=${task.photoProofUri}")
                                TaskProofImageManager.loadProofBitmap(context, task.photoProofUri, task.id) { _ ->
                                    android.util.Log.d("GuardianHomeScreen", "TASK_PROOF_PREFETCH_SUCCESS taskId=${task.id}")
                                }
                            }
                        }
                        tasksByChild = tasksByChild.toMutableMap().apply {
                            put(cUid, childTasks)
                            if (cCode.isNotBlank()) put(cCode, childTasks)
                        }
                        if (cUid == selectedChildUid || cCode == activeChildCode) {
                            childQuestsList = childTasks
                        }
                    }
                )
                if (reg != null) listeners.add(reg)
            }
        }
        onDispose {
            listeners.forEach { it.remove() }
        }
    }



    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = CardWhite,
                contentColor = TextSecondary,
                tonalElevation = 8.dp
            ) {
                val tabs = listOf(
                    "Home" to Icons.Filled.Home,
                    "Map" to Icons.Filled.Map,
                    "Tasks" to Icons.Filled.Checklist,
                    "Settings" to Icons.Filled.Settings
                )
                tabs.forEach { (title, icon) ->
                    val isSelected = selectedTab == title
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selectedTab = title },
                        icon = { Icon(imageVector = icon, contentDescription = title) },
                        label = { Text(title, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, fontSize = 11.sp) },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(SoftBg)
        ) {
            when (selectedTab) {
                "Map" -> GuardianMapTabView(activeChildCode = activeChildCode, activeGuardianName = activeGuardianName)
                "Tasks" -> GuardianTasksTabView(
                    activeChildCode = activeChildCode,
                    activeFamilyId = activeFamilyId,
                    currentUserId = currentUserId,
                    familyMembers = familyMembers,
                    selectedChildUid = selectedChildUid,
                    tasksByChild = tasksByChild,
                    onTasksByChildUpdated = { updatedMap -> tasksByChild = updatedMap }
                )
                "Settings" -> {
                    val targetChildCode = activeChildCode.ifBlank { effectiveChildren.firstOrNull()?.childCode ?: "" }
                    GuardianSettingsTabView(
                        activeChildCode = targetChildCode,
                        onLogout = onLogout,
                        onPairNewChild = { showPairChildDialog = true }
                    )
                }
                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    // Header Section with soft blue curved backdrop
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = Color(0xFFEFF6FF),
                                shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
                            )
                            .padding(horizontal = 20.dp, vertical = 20.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            // Top Row: Parent Profile & Notification Bell Icon
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Profile Avatar & Name (Clickable for Profile Info & Media Upload)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.clickable { showGuardianProfileModal = true }
                                ) {
                                    WhatsAppProfileAvatar(
                                        bitmap = profileBitmap,
                                        name = activeGuardianName,
                                        size = 52.dp,
                                        isEditable = false,
                                        backgroundColor = selectedAvatarColor,
                                        onClick = {
                                            viewerTargetBitmap = profileBitmap
                                            viewerTargetTitle = "$activeGuardianName (Profile Photo)"
                                            viewerIsEditable = true
                                            showFullProfileViewer = true
                                        }
                                    )
                                    Column {
                                        Text("Good evening", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                activeGuardianName,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Profile Options", tint = TextSecondary, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }

                                // Top Toolbar Action: Notification Bell Only
                                IconButton(
                                    onClick = { showNotificationCenterModal = true },
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(CardWhite, CircleShape)
                                ) {
                                    BadgedBox(
                                        badge = {
                                            if (unreadNotifCount > 0) {
                                                Badge(containerColor = RestrictionRed) {
                                                    Text(unreadNotifCount.toString(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Filled.Notifications, contentDescription = "Notifications", tint = TextPrimary)
                                    }
                                }
                            }

                            // Family ID & Status Indicator Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (activeFamilyId.isNotBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = Color(0xFF0F172A),
                                        border = BorderStroke(1.dp, BrandBlue.copy(alpha = 0.6f)),
                                        modifier = Modifier.clickable {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Family ID", activeFamilyId))
                                            Toast.makeText(context, "Copied Family ID: $activeFamilyId 📋", Toast.LENGTH_SHORT).show()
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text("👨‍👩‍👧‍👦 Family: $activeFamilyId", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = Color(0xFF38BDF8), modifier = Modifier.size(13.dp))
                                        }
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = SafeGreenBg,
                                    border = BorderStroke(1.dp, Color(0xFFBBF7D0))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = SafeGreen, modifier = Modifier.size(14.dp))
                                        Text("Family Connected", color = SafeGreen, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }
                            }

                            // Pending Join Requests Notice Pill (Prominently alerts Guardian to pending approvals)
                            if (pendingJoinRequests.isNotEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = WarningAmberBg,
                                    border = BorderStroke(1.5.dp, WarningAmber),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { showManageChildrenModal = true }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(Icons.Filled.GroupAdd, contentDescription = null, tint = WarningAmber, modifier = Modifier.size(20.dp))
                                            Column {
                                                Text(
                                                    text = "🔔 ${pendingJoinRequests.size} Pending Join Request(s)",
                                                    color = Color(0xFF92400E),
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 12.sp
                                                )
                                                Text(
                                                    text = "Tap here to review and approve new family members",
                                                    color = Color(0xFFB45309),
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }
                                        Surface(
                                            color = BrandBlue,
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "Review",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // FAMILY MEMBERS SECTION (Horizontal Scrollable Row with clean human names)
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("FAMILY MEMBERS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp)
                                    TextButton(onClick = { showManageChildrenModal = true }) {
                                        Text("Manage / Edit", color = BrandBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (effectiveChildren.isEmpty()) {
                                        Text(
                                            text = "No family members paired yet",
                                            fontSize = 12.sp,
                                            color = TextSecondary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    } else {
                                        effectiveChildren.forEach { childItem ->
                                            val childName = childItem.name
                                            val childCode = childItem.childCode
                                            val childUid = childItem.childUid
                                            val isSelected = childCode == activeChildCode || (activeChildCode.isBlank() && childUid == selectedChildUid)
                                            val cleanName = ChildIdManager.formatChildName(childName)
                                            android.util.Log.d(
                                                "GuardianHomeScreen",
                                                "PROFILE_GUARDIAN_CHILD_PHOTO_LOOKUP childName=$childName childCode=$childCode childUid=$childUid cacheKey=user_$childUid"
                                            )
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                modifier = Modifier.clickable {
                                                    activeChildCode = childCode
                                                    selectedChildUid = childUid
                                                    childQuestsList = if (activeFamilyId.isNotBlank()) {
                                                        tasksByChild[childUid] ?: tasksByChild[childCode] ?: emptyList()
                                                    } else {
                                                        ChildQuestManager.getQuests(context, childCode)
                                                    }
                                                    remainingSeconds = ScreenTimeManager.getRemainingSeconds(context, childCode)
                                                    isLocked = ScreenTimeManager.isDeviceLocked(context, childCode)
                                                    selectedChildForDetail = cleanName
                                                }
                                            ) {
                                                val childPhotoBitmap = remember(profileRefreshTrigger, childCode, childUid) {
                                                    ProfileImageManager.getProfileImageForUser(context, childUid)
                                                        ?: (if (childUid != childCode) ProfileImageManager.getProfileImageForUser(context, childCode) else null)
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .size(56.dp)
                                                        .clip(CircleShape)
                                                        .background(if (cleanName.lowercase().contains("maya")) Color(0xFF8B5CF6) else BrandBlue)
                                                        .border(if (isSelected) 3.dp else 0.dp, if (isSelected) SafeGreen else Color.Transparent, CircleShape),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (childPhotoBitmap != null) {
                                                        Image(
                                                            bitmap = childPhotoBitmap.asImageBitmap(),
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
                                                    modifier = Modifier.padding(top = 4.dp).widthIn(max = 80.dp)
                                                )
                                                Text(if (isSelected) "Active" else "Safe", fontSize = 10.sp, color = if (isSelected) BrandBlue else SafeGreen, fontWeight = FontWeight.Medium)
                                            }
                                        }
                                    }

                                    // Add Child Button in Family Members section
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.clickable { showPairChildDialog = true }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(CircleShape)
                                                .background(CardWhite)
                                                .border(1.dp, BorderGrey, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Filled.Add, contentDescription = "Add Member", tint = TextSecondary)
                                        }
                                        Text("Add", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextSecondary, modifier = Modifier.padding(top = 4.dp))
                                        Text(" ", fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Main Content Section
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        // Four Quick-Glance Cards
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("OVERVIEW", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                GlanceCard(
                                    title = "Protected Children",
                                    value = "${effectiveChildren.size} Children",
                                    subtitle = "Active protection",
                                    icon = Icons.Filled.ChildCare,
                                    iconTint = BrandBlue,
                                    iconBg = Color(0xFFEFF6FF),
                                    modifier = Modifier.weight(1f)
                                )
                                val allCurrentQuests = if (activeFamilyId.isNotBlank() && tasksByChild.isNotEmpty()) {
                                    tasksByChild[selectedChildUid] ?: tasksByChild[activeChildCode] ?: tasksByChild.values.flatten()
                                } else {
                                    childQuestsList
                                }
                                GlanceCard(
                                    title = "Pending Tasks",
                                    value = "${allCurrentQuests.count { it.status == QuestStatus.SUBMITTED || it.status == QuestStatus.PENDING }} Tasks",
                                    subtitle = "${allCurrentQuests.count { it.status == QuestStatus.SUBMITTED }} pending review",
                                    icon = Icons.Filled.Checklist,
                                    iconTint = WarningAmber,
                                    iconBg = WarningAmberBg,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { selectedTab = "Tasks" }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                GlanceCard(
                                    title = "Screen Time",
                                    value = if (isLocked) "Locked" else "${ScreenTimeManager.formatHoursAndMinutes(remainingSeconds)} Left",
                                    subtitle = if (isLocked) "Device is locked by Guardian" else "Used: ${ScreenTimeManager.formatHoursAndMinutes(usedSeconds)} (Limit: ${totalAllowanceSeconds / 3600}h)",
                                    icon = Icons.Filled.Schedule,
                                    iconTint = if (isLocked) RestrictionRed else InfoCyan,
                                    iconBg = if (isLocked) RestrictionRedBg else Color(0xFFE0F2FE),
                                    modifier = Modifier.weight(1f)
                                )
                                GlanceCard(
                                    title = "Devices",
                                    value = "${effectiveChildren.size} Online",
                                    subtitle = "All synced",
                                    icon = Icons.Filled.Smartphone,
                                    iconTint = BrandBlue,
                                    iconBg = Color(0xFFEFF6FF),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        // Child Task Photo Proof Verification Section (Task remains INCOMPLETE until verified!)
                        val pendingVerifications = remember(activeFamilyId, tasksByChild, childQuestsList, activeChildCode) {
                            if (activeFamilyId.isNotBlank() && tasksByChild.isNotEmpty()) {
                                tasksByChild.flatMap { (cId, qList) ->
                                    qList.filter { it.status == QuestStatus.SUBMITTED }
                                        .map { q -> if (q.childUserId.isBlank()) q.copy(childUserId = cId) else q }
                                }
                            } else {
                                childQuestsList.filter { it.status == QuestStatus.SUBMITTED }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Filled.PhotoCamera, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(16.dp))
                                    Text("CHILD TASK PHOTO VERIFICATIONS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp)
                                }
                                if (pendingVerifications.isNotEmpty()) {
                                    Surface(shape = RoundedCornerShape(50), color = Color(0xFFFEF3C7)) {
                                        Text(
                                            text = "${pendingVerifications.size} Pending Review",
                                            color = Color(0xFFD97706),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            if (pendingVerifications.isEmpty()) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = CardWhite),
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, BorderGrey)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = SafeGreen, modifier = Modifier.size(20.dp))
                                        Text("All child task photo proof submissions are verified", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                                    }
                                }
                            } else {
                                pendingVerifications.forEach { quest ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, Color(0xFFFDE68A))
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text(quest.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                                    Text("Submitted by Child • +${quest.rewardStars} Stars Reward", fontSize = 11.sp, color = BrandBlue, fontWeight = FontWeight.SemiBold)
                                                }
                                                Surface(shape = RoundedCornerShape(50), color = Color(0xFFFEF3C7)) {
                                                    Text("Pending Approval", fontSize = 10.sp, color = Color(0xFFD97706), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                                }
                                            }

                                            // Photo Proof Preview Box with reactive high-res loading
                                            var proofBitmap by remember(quest.photoProofUri, quest.id) {
                                                mutableStateOf(com.homesync.app.util.TaskProofImageManager.getProofBitmap(context, quest.photoProofUri, quest.id))
                                            }
                                            LaunchedEffect(quest.photoProofUri, quest.id) {
                                                com.homesync.app.util.TaskProofImageManager.loadProofBitmap(context, quest.photoProofUri, quest.id) { loaded ->
                                                    proofBitmap = loaded
                                                }
                                            }

                                            Surface(
                                                color = Color.White,
                                                shape = RoundedCornerShape(12.dp),
                                                border = BorderStroke(1.dp, Color(0xFFFCD34D)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                    ) {
                                                        Icon(Icons.Filled.Image, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(20.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text("Attached Photo Proof", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                                                            Text(
                                                                text = quest.photoProofLabel.ifBlank { "Task_Photo_Submission.jpg" },
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = TextPrimary
                                                            )
                                                        }
                                                    }

                                                    if (proofBitmap != null) {
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .height(160.dp)
                                                                .clip(RoundedCornerShape(10.dp))
                                                                .clickable { previewImageBitmap = proofBitmap }
                                                        ) {
                                                            Image(
                                                                bitmap = proofBitmap!!.asImageBitmap(),
                                                                contentDescription = "Task photo proof thumbnail",
                                                                contentScale = ContentScale.Crop,
                                                                modifier = Modifier.fillMaxSize()
                                                            )
                                                            Surface(
                                                                color = Color.Black.copy(alpha = 0.6f),
                                                                shape = RoundedCornerShape(20),
                                                                modifier = Modifier
                                                                    .align(Alignment.BottomEnd)
                                                                    .padding(6.dp)
                                                            ) {
                                                                Text("🔍 Tap to Enlarge", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                                                            }
                                                        }
                                                    } else if (quest.photoProofUri.isNotBlank()) {
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .height(100.dp)
                                                                .clip(RoundedCornerShape(10.dp))
                                                                .background(Color(0xFFF3F4F6)),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                            ) {
                                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = BrandBlue)
                                                                Text("Loading high-res photo proof...", fontSize = 11.sp, color = TextSecondary)
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Button(
                                                    onClick = {
                                                        val targetChild = if (quest.childUserId.isNotBlank() && !quest.childUserId.startsWith("HS-")) {
                                                            quest.childUserId
                                                        } else if (selectedChildUid.isNotBlank() && !selectedChildUid.startsWith("HS-")) {
                                                            selectedChildUid
                                                        } else {
                                                            resolveChildUid("Child", activeChildCode, familyMembers)
                                                        }
                                                        if (activeFamilyId.isNotBlank()) {
                                                            FamilyTaskManager.verifyTask(
                                                                context = context,
                                                                familyId = activeFamilyId,
                                                                childUserId = targetChild,
                                                                questId = quest.id,
                                                                approve = true,
                                                                rewardStars = quest.rewardStars
                                                            ) { success ->
                                                                if (success) {
                                                                    Toast.makeText(context, "Approved! ${quest.rewardStars} Stars awarded to child!", Toast.LENGTH_SHORT).show()
                                                                }
                                                            }
                                                        } else {
                                                            childQuestsList = ChildQuestManager.verifyQuest(context, targetChild.ifBlank { activeChildCode }, quest.id, approve = true)
                                                            Toast.makeText(context, "Approved! ${quest.rewardStars} Stars awarded to child!", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = SafeGreen),
                                                    shape = RoundedCornerShape(10.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                                                    Text("Approve Task", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                }

                                                OutlinedButton(
                                                    onClick = {
                                                        val targetChild = if (quest.childUserId.isNotBlank() && !quest.childUserId.startsWith("HS-")) {
                                                            quest.childUserId
                                                        } else if (selectedChildUid.isNotBlank() && !selectedChildUid.startsWith("HS-")) {
                                                            selectedChildUid
                                                        } else {
                                                            resolveChildUid("Child", activeChildCode, familyMembers)
                                                        }
                                                        if (activeFamilyId.isNotBlank()) {
                                                            FamilyTaskManager.verifyTask(
                                                                context = context,
                                                                familyId = activeFamilyId,
                                                                childUserId = targetChild,
                                                                questId = quest.id,
                                                                approve = false
                                                            ) { success ->
                                                                if (success) {
                                                                    Toast.makeText(context, "Requested re-do from child", Toast.LENGTH_SHORT).show()
                                                                }
                                                            }
                                                        } else {
                                                            childQuestsList = ChildQuestManager.verifyQuest(context, targetChild.ifBlank { activeChildCode }, quest.id, approve = false)
                                                            Toast.makeText(context, "Requested re-do from child", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    shape = RoundedCornerShape(10.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = RestrictionRed, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                                                    Text("Request Re-do", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RestrictionRed)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Live Remote Device Control & Screen Time Section (Multi-child support & Bulk Actions)
                        val targetChildrenList = if (effectiveChildren.isNotEmpty()) {
                            effectiveChildren
                        } else {
                            listOf(GuardianChildItem(name = "Child", childUid = selectedChildUid, childCode = activeChildCode))
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = CardWhite),
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(1.dp, BorderGrey)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                // Section Title
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(Icons.Filled.Schedule, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(20.dp))
                                        Text("REMOTE SCREEN TIME & LOCK", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = SoftBg,
                                        border = BorderStroke(1.dp, BorderGrey)
                                    ) {
                                        Text(
                                            text = "${targetChildrenList.size} ${if (targetChildrenList.size == 1) "Child" else "Children"}",
                                            color = TextSecondary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                        )
                                    }
                                }

                                // Global Bulk Actions (when multiple children exist)
                                if (targetChildrenList.size > 1) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = SoftBg,
                                        border = BorderStroke(1.dp, BorderGrey),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("FAMILY BULK ACTIONS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 0.5.sp)
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(
                                                    onClick = {
                                                        val allCodes = targetChildrenList.map { it.childCode }
                                                        android.util.Log.i("HomeSyncLatency", "LOCK_BUTTON_CLICK childCode=ALL locked=true timestamp=${System.currentTimeMillis()}")
                                                        ScreenTimeManager.setAllChildrenLocked(context, allCodes, true)
                                                        screenTimeByChild = screenTimeByChild.mapValues { it.value.copy(isLocked = true, remainingSeconds = 0) }
                                                        isLocked = true
                                                        remainingSeconds = 0
                                                        Toast.makeText(context, "🔒 Locked all children devices", Toast.LENGTH_SHORT).show()
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = RestrictionRed),
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text("🔒 Lock All", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }

                                                Button(
                                                    onClick = {
                                                        val allCodes = targetChildrenList.map { it.childCode }
                                                        ScreenTimeManager.setAllChildrenLocked(context, allCodes, false)
                                                        screenTimeByChild = screenTimeByChild.mapValues {
                                                            val rem = if (it.value.remainingSeconds > 0) it.value.remainingSeconds else 1800
                                                            it.value.copy(isLocked = false, remainingSeconds = rem)
                                                        }
                                                        isLocked = false
                                                        if (remainingSeconds <= 0) remainingSeconds = 1800
                                                        Toast.makeText(context, "🔓 Unlocked all children (+30m grace)", Toast.LENGTH_SHORT).show()
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = SafeGreen),
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text("🔓 Unlock All", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }

                                                Button(
                                                    onClick = {
                                                        val allCodes = targetChildrenList.map { it.childCode }
                                                        ScreenTimeManager.resetAllChildrenScreenTime(context, allCodes)
                                                        screenTimeByChild = screenTimeByChild.mapValues {
                                                            it.value.copy(
                                                                isLocked = false,
                                                                remainingSeconds = ScreenTimeManager.DEFAULT_ALLOWANCE_SECONDS,
                                                                totalAllowanceSeconds = ScreenTimeManager.DEFAULT_ALLOWANCE_SECONDS,
                                                                usedSeconds = 0
                                                            )
                                                        }
                                                        isLocked = false
                                                        remainingSeconds = ScreenTimeManager.DEFAULT_ALLOWANCE_SECONDS
                                                        totalAllowanceSeconds = ScreenTimeManager.DEFAULT_ALLOWANCE_SECONDS
                                                        usedSeconds = 0
                                                        Toast.makeText(context, "🔄 Reset all children to 6 hours", Toast.LENGTH_SHORT).show()
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                                    modifier = Modifier.weight(1.1f)
                                                ) {
                                                    Text("🔄 Reset All (6h)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }

                                // Per-Child Control Cards
                                targetChildrenList.forEach { child ->
                                    val childCode = child.childCode
                                    val childName = ChildIdManager.formatChildName(child.name)
                                    val childState = screenTimeByChild[childCode]
                                        ?: (if (childCode == activeChildCode) {
                                            ChildScreenTimeState(remainingSeconds, isLocked, totalAllowanceSeconds, usedSeconds)
                                        } else {
                                            ChildScreenTimeState(
                                                remainingSeconds = ScreenTimeManager.getRemainingSeconds(context, childCode),
                                                isLocked = ScreenTimeManager.isDeviceLocked(context, childCode),
                                                totalAllowanceSeconds = ScreenTimeManager.getTotalAllowance(context, childCode),
                                                usedSeconds = ScreenTimeManager.getUsedSeconds(context, childCode)
                                            )
                                        })
                                    val childLocked = childState.isLocked
                                    val childRem = childState.remainingSeconds
                                    val childUsed = childState.usedSeconds
                                    val childLimit = childState.totalAllowanceSeconds

                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (childCode == activeChildCode) Color(0xFFF1F5F9) else SoftBg,
                                        border = BorderStroke(1.dp, if (childCode == activeChildCode) BrandBlue.copy(alpha = 0.5f) else BorderGrey),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            // Child Header Row: Avatar/Initial + Name + Code + Status Badge
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(28.dp)
                                                            .clip(CircleShape)
                                                            .background(if (childName.lowercase().contains("maya")) Color(0xFF8B5CF6) else BrandBlue),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(childName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                    }
                                                    Column {
                                                        Text(childName, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                                        Text("Code: $childCode", fontSize = 10.sp, color = TextSecondary)
                                                    }
                                                }

                                                Surface(
                                                    shape = RoundedCornerShape(50),
                                                    color = if (childLocked) RestrictionRedBg else SafeGreenBg
                                                ) {
                                                    Text(
                                                        text = if (childLocked) "🔒 Locked" else "🟢 Active (${childRem / 60}m left)",
                                                        color = if (childLocked) RestrictionRed else SafeGreen,
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                    )
                                                }
                                            }

                                            // Usage details
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text(
                                                    text = "Used: ${ScreenTimeManager.formatHoursAndMinutes(childUsed)}",
                                                    fontSize = 11.sp,
                                                    color = TextSecondary,
                                                    fontWeight = FontWeight.Medium
                                                )
                                                Text(
                                                    text = "Daily Limit: ${childLimit / 3600}h",
                                                    fontSize = 11.sp,
                                                    color = TextSecondary,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }

                                            // Individual Action Row 1: Lock/Unlock + 15m + 30m
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Button(
                                                    onClick = {
                                                        val newLock = !childLocked
                                                        android.util.Log.i("HomeSyncLatency", "LOCK_BUTTON_CLICK childCode=$childCode locked=$newLock timestamp=${System.currentTimeMillis()}")
                                                        ScreenTimeManager.setDeviceLocked(context, childCode, newLock)
                                                        val newRem = if (!newLock) {
                                                            val updated = ScreenTimeManager.getRemainingSeconds(context, childCode)
                                                            if (updated > 0) updated else 1800
                                                        } else 0
                                                        val updatedState = childState.copy(isLocked = newLock, remainingSeconds = newRem)
                                                        screenTimeByChild = screenTimeByChild + (childCode to updatedState)
                                                        if (childCode == activeChildCode) {
                                                            isLocked = newLock
                                                            remainingSeconds = newRem
                                                        }
                                                        Toast.makeText(
                                                            context,
                                                            if (newLock) "Remote Locked $childName" else "Remote Unlocked $childName (+30m grace)",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = if (childLocked) SafeGreen else RestrictionRed),
                                                    shape = RoundedCornerShape(10.dp),
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                                                    modifier = Modifier.weight(1.2f)
                                                ) {
                                                    Text(if (childLocked) "Unlock $childName" else "Lock $childName", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }

                                                Button(
                                                    onClick = {
                                                        if (childState.totalAllowanceSeconds > 0) {
                                                            ScreenTimeManager.applyRemoteUpdate(
                                                                context, childCode,
                                                                childState.remainingSeconds, childState.isLocked,
                                                                childState.totalAllowanceSeconds, childState.usedSeconds
                                                            )
                                                        }
                                                        ScreenTimeManager.grantExtraTime(context, childCode, 900)
                                                        val updatedRem = ScreenTimeManager.getRemainingSeconds(context, childCode)
                                                        val updatedTot = ScreenTimeManager.getTotalAllowance(context, childCode)
                                                        val updatedState = childState.copy(isLocked = false, remainingSeconds = updatedRem, totalAllowanceSeconds = updatedTot)
                                                        screenTimeByChild = screenTimeByChild + (childCode to updatedState)
                                                        if (childCode == activeChildCode) {
                                                            isLocked = false
                                                            remainingSeconds = updatedRem
                                                            totalAllowanceSeconds = updatedTot
                                                        }
                                                        Toast.makeText(context, "Granted +15m Extra to $childName", Toast.LENGTH_SHORT).show()
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                                                    shape = RoundedCornerShape(10.dp),
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                                                    modifier = Modifier.weight(0.9f)
                                                ) {
                                                    Text("+15m", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }

                                                Button(
                                                    onClick = {
                                                        if (childState.totalAllowanceSeconds > 0) {
                                                            ScreenTimeManager.applyRemoteUpdate(
                                                                context, childCode,
                                                                childState.remainingSeconds, childState.isLocked,
                                                                childState.totalAllowanceSeconds, childState.usedSeconds
                                                            )
                                                        }
                                                        ScreenTimeManager.grantExtraTime(context, childCode, 1800)
                                                        val updatedRem = ScreenTimeManager.getRemainingSeconds(context, childCode)
                                                        val updatedTot = ScreenTimeManager.getTotalAllowance(context, childCode)
                                                        val updatedState = childState.copy(isLocked = false, remainingSeconds = updatedRem, totalAllowanceSeconds = updatedTot)
                                                        screenTimeByChild = screenTimeByChild + (childCode to updatedState)
                                                        if (childCode == activeChildCode) {
                                                            isLocked = false
                                                            remainingSeconds = updatedRem
                                                            totalAllowanceSeconds = updatedTot
                                                        }
                                                        Toast.makeText(context, "Granted +30m Extra to $childName", Toast.LENGTH_SHORT).show()
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                                                    shape = RoundedCornerShape(10.dp),
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                                                    modifier = Modifier.weight(0.9f)
                                                ) {
                                                    Text("+30m", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }

                                            // Individual Action Row 2: Per-Child Reset to 6 Hours
                                            Button(
                                                onClick = {
                                                    ScreenTimeManager.resetToSixHoursAsCommand(context, childCode)
                                                    val resetAllowance = ScreenTimeManager.DEFAULT_ALLOWANCE_SECONDS
                                                    val updatedState = childState.copy(
                                                        isLocked = false,
                                                        remainingSeconds = resetAllowance,
                                                        totalAllowanceSeconds = resetAllowance,
                                                        usedSeconds = 0
                                                    )
                                                    screenTimeByChild = screenTimeByChild + (childCode to updatedState)
                                                    if (childCode == activeChildCode) {
                                                        isLocked = false
                                                        remainingSeconds = resetAllowance
                                                        totalAllowanceSeconds = resetAllowance
                                                        usedSeconds = 0
                                                    }
                                                    Toast.makeText(context, "🔄 Reset $childName to 6 hours screen time", Toast.LENGTH_SHORT).show()
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                                shape = RoundedCornerShape(10.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("🔄 Reset $childName to 6 Hours", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                    }
                }
            }
        }
    }

    // Guardian Profile Info & Logout Modal (With Camera & Device Media Photo Picker)
    if (showGuardianProfileModal) {
        AlertDialog(
            onDismissRequest = { showGuardianProfileModal = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Guardian Profile Info", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    IconButton(onClick = { showGuardianProfileModal = false }) {
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
                    // Profile Pic Avatar Display & Media Upload Controls
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        WhatsAppProfileAvatar(
                            bitmap = profileBitmap,
                            name = activeGuardianName,
                            size = 90.dp,
                            isEditable = true,
                            backgroundColor = selectedAvatarColor,
                            onClick = {
                                viewerTargetBitmap = profileBitmap
                                viewerTargetTitle = "$activeGuardianName (Profile Photo)"
                                viewerIsEditable = true
                                showFullProfileViewer = true
                            },
                            onCameraClick = {
                                showPhotoOptionsModal = true
                            }
                        )

                        Text(activeGuardianName, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.padding(top = 8.dp))
                        val activeSession = remember { com.homesync.app.util.AuthManager.getActiveSession(context) }
                        val guardianEmail = activeSession?.email?.ifBlank {
                            com.homesync.app.util.AuthManager.getLastLoggedInAccount(context)?.email?.ifBlank { "guardian@homesync.app" } ?: "guardian@homesync.app"
                        } ?: "guardian@homesync.app"
                        Text("$guardianEmail • Guardian Account", fontSize = 12.sp, color = TextSecondary)
                    }

                    HorizontalDivider(color = BorderGrey)

                    // Profile Pic Upload Options (Camera or Device Gallery)
                    Text("CHANGE PROFILE PICTURE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { cameraProfileLauncher.launch(null) },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                            Text("Take Photo", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { galleryProfileLauncher.launch("image/*") },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp).padding(end = 4.dp))
                            Text("Choose Media", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (profileBitmap != null) {
                        TextButton(
                            onClick = {
                                ProfileImageManager.clearProfileImage(context, "user_$currentUserId")
                                ProfileImageManager.clearProfileImage(context, "guardian")
                                ProfileImageManager.saveCachedPhotoUrl(context, currentUserId, "")
                                if (activeFamilyId.isNotBlank() && currentUserId.isNotBlank()) {
                                    FamilyManager.updateMemberProfilePicture(activeFamilyId, currentUserId, "")
                                }
                                profileBitmap = null
                                profileRefreshTrigger += 1
                                Toast.makeText(context, "Profile picture removed", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text("Remove Photo (Use Initials)", color = RestrictionRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    HorizontalDivider(color = BorderGrey)

                    Text("AVATAR BACKGROUND COLOR", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(
                            BrandBlue,
                            Color(0xFF8B5CF6),
                            SafeGreen,
                            Color(0xFFEA580C),
                            DeepNavy
                        ).forEach { color ->
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(if (selectedAvatarColor == color) 3.dp else 0.dp, TextPrimary, CircleShape)
                                    .clickable { selectedAvatarColor = color }
                            )
                        }
                    }

                    HorizontalDivider(color = BorderGrey)

                    // Edit Display Name Option
                    OutlinedTextField(
                        value = activeGuardianName,
                        onValueChange = { activeGuardianName = it },
                        label = { Text("Guardian Display Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = guardianPhoneNumber,
                        onValueChange = { guardianPhoneNumber = it },
                        label = { Text("Phone Number (for Child Calls & SMS)") },
                        placeholder = { Text("+1 (555) 000-0000") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            FamilyManager.updateGuardianProfile(
                                context = context,
                                familyId = activeFamilyId,
                                guardianUid = currentUserId,
                                displayName = activeGuardianName,
                                phoneNumber = guardianPhoneNumber
                            ) { success ->
                                Toast.makeText(context, if (success) "Profile saved successfully! ✓" else "Failed to save profile.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SafeGreen),
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("Save Profile Changes", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    // Logout Guardian Account Button inside Profile
                    Button(
                        onClick = {
                            showGuardianProfileModal = false
                            onLogout()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RestrictionRed),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Filled.Logout, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Logout Guardian Account", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {},
            containerColor = CardWhite
        )
    }

    // Real-Time Popup Alert Modal for Incoming Child Check-Ins & SOS Alerts
    liveCheckInAlertNotification?.let { notif ->
        AlertDialog(
            onDismissRequest = {
                dismissedAlertIds.add(notif.id)
                liveCheckInAlertNotification = null
                coroutineScope.launch(Dispatchers.IO) {
                    NotificationManager.markAsRead(context, notif.id)
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = when (notif.type) {
                            NotificationType.SOS_EMERGENCY -> "🚨"
                            NotificationType.CHILD_SAFE_CHECKIN -> "🟢"
                            NotificationType.TASK_PHOTO_SUBMITTED -> "📷"
                            else -> "🔔"
                        },
                        fontSize = 24.sp
                    )
                    Column {
                        Text(notif.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("Live Alert from Child Device", fontSize = 11.sp, color = InfoCyan, fontWeight = FontWeight.Bold)
                    }
                }
            },
            text = {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SoftBg),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderGrey)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Child Name:", fontSize = 11.sp, color = TextSecondary)
                            Text(notif.childName.ifBlank { "Child" }, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                        if (notif.childCode.isNotBlank()) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Child ID / Pairing Code:", fontSize = 11.sp, color = TextSecondary)
                                Text(notif.childCode, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BrandBlue)
                            }
                        }
                        HorizontalDivider(color = BorderGrey, modifier = Modifier.padding(vertical = 4.dp))
                        Text(notif.message, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)

                        if (notif.type == NotificationType.TASK_PHOTO_SUBMITTED && notif.actionData.isNotBlank()) {
                            val alertProofBitmap = remember(notif.actionData) {
                                com.homesync.app.util.TaskProofImageManager.getProofBitmap(context, "", notif.actionData)
                            }
                            if (alertProofBitmap != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(140.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                ) {
                                    Image(
                                        bitmap = alertProofBitmap.asImageBitmap(),
                                        contentDescription = "Live alert photo proof",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        dismissedAlertIds.add(notif.id)
                        liveCheckInAlertNotification = null
                        if (notif.type == NotificationType.TASK_PHOTO_SUBMITTED) {
                            selectedTab = "Tasks"
                        }
                        coroutineScope.launch(Dispatchers.IO) {
                            NotificationManager.markAsRead(context, notif.id)
                            val freshNotifs = NotificationManager.getNotifications(context)
                            val freshQuests = ChildQuestManager.getQuests(context, activeChildCode)
                            withContext(Dispatchers.Main) {
                                notificationsList = freshNotifs
                                childQuestsList = freshQuests
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (notif.type == NotificationType.SOS_EMERGENCY) RestrictionRed else SafeGreen
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        if (notif.type == NotificationType.TASK_PHOTO_SUBMITTED) "View & Verify Task 📷" else "Acknowledge Alert 💚",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    dismissedAlertIds.add(notif.id)
                    liveCheckInAlertNotification = null
                    coroutineScope.launch(Dispatchers.IO) {
                        NotificationManager.markAsRead(context, notif.id)
                        val freshNotifs = NotificationManager.getNotifications(context)
                        withContext(Dispatchers.Main) {
                            notificationsList = freshNotifs
                        }
                    }
                }) {
                    Text("Dismiss", color = TextSecondary)
                }
            },
            containerColor = CardWhite,
            shape = RoundedCornerShape(18.dp)
        )
    }

    if (showFullProfileViewer) {
        WhatsAppProfileViewerDialog(
            bitmap = viewerTargetBitmap,
            title = viewerTargetTitle,
            isEditable = viewerIsEditable,
            onDismiss = { showFullProfileViewer = false },
            onTakePhoto = { cameraProfileLauncher.launch(null) },
            onChooseGallery = { galleryProfileLauncher.launch("image/*") },
            onRemovePhoto = {
                ProfileImageManager.clearProfileImage(context, "user_$currentUserId")
                ProfileImageManager.clearProfileImage(context, "guardian")
                ProfileImageManager.saveCachedPhotoUrl(context, currentUserId, "")
                if (activeFamilyId.isNotBlank() && currentUserId.isNotBlank()) {
                    FamilyManager.updateMemberProfilePicture(activeFamilyId, currentUserId, "")
                }
                profileBitmap = null
                profileRefreshTrigger += 1
                Toast.makeText(context, "Profile picture removed", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showPhotoOptionsModal) {
        WhatsAppPhotoOptionsModal(
            hasPhoto = (profileBitmap != null),
            onDismiss = { showPhotoOptionsModal = false },
            onTakePhoto = {
                showPhotoOptionsModal = false
                cameraProfileLauncher.launch(null)
            },
            onChooseGallery = {
                showPhotoOptionsModal = false
                galleryProfileLauncher.launch("image/*")
            },
            onRemovePhoto = {
                showPhotoOptionsModal = false
                ProfileImageManager.clearProfileImage(context, "user_$currentUserId")
                ProfileImageManager.clearProfileImage(context, "guardian")
                ProfileImageManager.saveCachedPhotoUrl(context, currentUserId, "")
                if (activeFamilyId.isNotBlank() && currentUserId.isNotBlank()) {
                    FamilyManager.updateMemberProfilePicture(activeFamilyId, currentUserId, "")
                }
                profileBitmap = null
                profileRefreshTrigger += 1
                Toast.makeText(context, "Profile picture removed", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Notification Center Modal
    if (showNotificationCenterModal) {
        AlertDialog(
            onDismissRequest = { showNotificationCenterModal = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Notifications, contentDescription = null, tint = BrandBlue)
                        Text("Notification Center", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                    IconButton(onClick = { showNotificationCenterModal = false }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            NotificationManager.markAllAsRead(context)
                            notificationsList = NotificationManager.getNotifications(context)
                        }) {
                            Text("Mark all read", fontSize = 11.sp, color = BrandBlue, fontWeight = FontWeight.Bold)
                        }

                        TextButton(onClick = {
                            NotificationManager.clearAll(context)
                            notificationsList = emptyList()
                        }) {
                            Text("Clear all", fontSize = 11.sp, color = RestrictionRed, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (notificationsList.isEmpty()) {
                        Text("No notifications right now.", fontSize = 13.sp, color = TextSecondary, modifier = Modifier.padding(vertical = 20.dp))
                    } else {
                        notificationsList.forEach { notif ->
                            val notifBg = if (notif.isRead) SoftBg else Color(0xFFEFF6FF)
                            val notifBorder = if (notif.isRead) BorderGrey else BrandBlue

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        NotificationManager.markAsRead(context, notif.id)
                                        notificationsList = NotificationManager.getNotifications(context)
                                        if (notif.type == NotificationType.TASK_PHOTO_SUBMITTED) {
                                            showNotificationCenterModal = false
                                            selectedTab = "Tasks"
                                        }
                                    },
                                colors = CardDefaults.cardColors(containerColor = notifBg),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, notifBorder)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.Top,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    val iconVector = when (notif.type) {
                                        NotificationType.TASK_PHOTO_SUBMITTED -> Icons.Filled.PhotoCamera
                                        NotificationType.CHILD_SAFE_CHECKIN -> Icons.Filled.CheckCircle
                                        NotificationType.SOS_EMERGENCY -> Icons.Filled.Warning
                                        NotificationType.SAFE_ZONE_EVENT -> Icons.Filled.Place
                                        NotificationType.FAMILY_JOIN_REQUEST -> Icons.Filled.GroupAdd
                                    }
                                    val iconTint = when (notif.type) {
                                        NotificationType.TASK_PHOTO_SUBMITTED -> WarningAmber
                                        NotificationType.CHILD_SAFE_CHECKIN -> SafeGreen
                                        NotificationType.SOS_EMERGENCY -> RestrictionRed
                                        NotificationType.SAFE_ZONE_EVENT -> BrandBlue
                                        NotificationType.FAMILY_JOIN_REQUEST -> WarningAmber
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(iconTint.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(iconVector, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
                                    }

                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(notif.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                            Text("Just now", fontSize = 10.sp, color = TextSecondary)
                                        }
                                        Text(notif.message, fontSize = 12.sp, color = TextSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            containerColor = CardWhite
        )
    }

    // Child Detail Overlay Dialog
    selectedChildForDetail?.let { childName ->
        val childItemForDetail = effectiveChildren.find { ChildIdManager.formatChildName(it.name).equals(ChildIdManager.formatChildName(childName), ignoreCase = true) }
        val childCodeForDetail = childItemForDetail?.childCode ?: activeChildCode
        val childUidForDetail = childItemForDetail?.childUid ?: resolveChildUid(childName, childCodeForDetail, familyMembers)
        val childDetailPhoto = remember(profileRefreshTrigger, childUidForDetail, childCodeForDetail) {
            ProfileImageManager.getProfileImageForUser(context, childUidForDetail)
                ?: (if (childUidForDetail != childCodeForDetail) ProfileImageManager.getProfileImageForUser(context, childCodeForDetail) else null)
        }
        ChildDetailDialog(
            childName = childName,
            childCode = childCodeForDetail,
            childUid = childUidForDetail,
            childPhotoBitmap = childDetailPhoto,
            tasksByChild = tasksByChild,
            onDismiss = { selectedChildForDetail = null },
            onNavigateTab = { tab ->
                selectedChildForDetail = null
                selectedTab = tab
            }
        )
    }

    // Manage Family Members & Pending Requests Modal
    if (showManageChildrenModal) {
        AlertDialog(
            onDismissRequest = { showManageChildrenModal = false },
            title = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Family Management", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    IconButton(onClick = { showManageChildrenModal = false }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Family ID Sharing Card
                    if (activeFamilyId.isNotBlank()) {
                        Surface(
                            color = Color(0xFF0F172A),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Family ID", activeFamilyId))
                                    Toast.makeText(context, "Copied Family ID: $activeFamilyId 📋", Toast.LENGTH_SHORT).show()
                                }
                        ) {
                            Column(modifier = Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("YOUR UNIQUE FAMILY ID", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8), letterSpacing = 1.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(activeFamilyId, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF38BDF8), letterSpacing = 2.sp)
                                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("Share this Family ID with all Guardians & Children", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
                            }
                        }
                    }

                    if (guardianListenerError.isNotBlank()) {
                        Surface(
                            color = RestrictionRedBg,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, RestrictionRed),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Family Sync Note: $guardianListenerError",
                                fontSize = 11.sp,
                                color = RestrictionRed,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    // ========================================================
                    // 1. PENDING JOIN REQUESTS SECTION (Guardian Approval)
                    // ========================================================
                    if (pendingJoinRequests.isNotEmpty()) {
                        Surface(
                            color = WarningAmberBg,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, WarningAmber),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Filled.GroupAdd, contentDescription = null, tint = Color(0xFFB45309), modifier = Modifier.size(18.dp))
                                    Text("PENDING JOIN REQUESTS (${pendingJoinRequests.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF92400E), letterSpacing = 0.5.sp)
                                }

                                pendingJoinRequests.forEach { request ->
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = CardWhite),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, BorderGrey),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                Column {
                                                    Text(request.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                                    Text("Role: ${request.role.name} • ${request.email}", fontSize = 11.sp, color = TextSecondary)
                                                }
                                                Surface(
                                                    color = if (request.role == FamilyRole.GUARDIAN) BrandBlue.copy(alpha = 0.15f) else Color(0xFFF97316).copy(alpha = 0.15f),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        request.role.name,
                                                        color = if (request.role == FamilyRole.GUARDIAN) BrandBlue else Color(0xFFF97316),
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }

                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(
                                                    onClick = {
                                                        FamilyManager.approveJoinRequest(activeFamilyId, request, guardianUserId = currentUserId) { success ->
                                                            if (success) {
                                                                Toast.makeText(context, "Approved ${request.name}!", Toast.LENGTH_SHORT).show()
                                                            } else {
                                                                Toast.makeText(context, "Approval failed. Please check permissions.", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = SafeGreen),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text("APPROVE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }

                                                OutlinedButton(
                                                    onClick = {
                                                        FamilyManager.rejectJoinRequest(activeFamilyId, request, guardianUserId = currentUserId) { success ->
                                                            if (success) {
                                                                Toast.makeText(context, "Rejected ${request.name}", Toast.LENGTH_SHORT).show()
                                                            } else {
                                                                Toast.makeText(context, "Rejection failed.", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    },
                                                    border = BorderStroke(1.dp, RestrictionRed),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Text("REJECT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = RestrictionRed)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ========================================================
                    // 2. FAMILY GUARDIANS (Multiple Guardians Support)
                    // ========================================================
                    val guardiansList = familyMembers.filter { it.role == FamilyRole.GUARDIAN }
                    Text("FAMILY GUARDIANS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp)
                    if (guardiansList.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = CardWhite),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderGrey)
                        ) {
                            Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(modifier = Modifier.size(36.dp).clip(CircleShape).background(BrandBlue), contentAlignment = Alignment.Center) {
                                    Text(activeGuardianName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Column {
                                    Text(activeGuardianName, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Text("Primary Guardian (Admin)", fontSize = 11.sp, color = SafeGreen, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    } else {
                        guardiansList.forEach { guardian ->
                            val gPhoto = remember(guardian.userId, profileRefreshTrigger) {
                                ProfileImageManager.getProfileImage(context, key = "user_${guardian.userId}")
                            }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = CardWhite),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, BorderGrey)
                            ) {
                                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Box(modifier = Modifier.size(38.dp).clip(CircleShape).background(BrandBlue), contentAlignment = Alignment.Center) {
                                        if (gPhoto != null) {
                                            Image(bitmap = gPhoto.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        } else {
                                            Text(guardian.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Column {
                                        Text(guardian.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        Text(if (guardian.email.isNotBlank()) guardian.email else "Guardian • Online", fontSize = 11.sp, color = TextSecondary)
                                    }
                                }
                            }
                        }
                    }

                    // ========================================================
                    // 3. FAMILY CHILDREN (Multiple Children Support)
                    // ========================================================
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("FAMILY CHILDREN", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp)
                        if (effectiveChildren.isNotEmpty() || savedChildrenList.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    for (c in effectiveChildren) {
                                        val safeUid = if (c.childUid.isNotBlank() && !c.childUid.startsWith("HS-", ignoreCase = true)) {
                                            c.childUid
                                        } else {
                                            resolveChildUid(c.name, c.childCode, familyMembers)
                                        }
                                        FamilyManager.removeFamilyMember(
                                            context = context,
                                            familyId = activeFamilyId,
                                            memberUserId = safeUid,
                                            childCode = c.childCode,
                                            childName = c.name
                                        )
                                    }
                                    ChildIdManager.clearAllChildren(context)
                                    savedChildrenList = emptyList()
                                    familyMembers = familyMembers.filter { it.role != FamilyRole.CHILD }
                                    activeChildCode = ""
                                    selectedChildUid = ""
                                    childQuestsList = emptyList()
                                    Toast.makeText(context, "Removed all children from family", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("Clear All", color = RestrictionRed, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }

                    if (effectiveChildren.isEmpty()) {
                        Surface(
                            color = SoftBg,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderGrey),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "No children paired yet. Share your Family ID with your child or add below.",
                                fontSize = 12.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    } else {
                        effectiveChildren.forEach { childItem ->
                            val childName = childItem.name
                            val childCode = childItem.childCode
                            val childUid = childItem.childUid
                            val isSelected = childCode == activeChildCode || (activeChildCode.isBlank() && childUid == selectedChildUid)
                            val cleanName = ChildIdManager.formatChildName(childName)
                            android.util.Log.d(
                                "GuardianHomeScreen",
                                "PROFILE_GUARDIAN_CHILD_PHOTO_LOOKUP childName=$childName childCode=$childCode childUid=$childUid cacheKey=user_$childUid"
                            )
                            val cPhoto = remember(childCode, childUid, profileRefreshTrigger) {
                                ProfileImageManager.getProfileImageForUser(context, childUid)
                                    ?: (if (childUid != childCode) ProfileImageManager.getProfileImageForUser(context, childCode) else null)
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) Color(0xFFEFF6FF) else CardWhite
                                ),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, if (isSelected) BrandBlue else BorderGrey)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                                        Box(modifier = Modifier.size(38.dp).clip(CircleShape).background(Color(0xFFF97316)), contentAlignment = Alignment.Center) {
                                            if (cPhoto != null) {
                                                Image(bitmap = cPhoto.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                            } else {
                                                Text(cleanName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        Column(modifier = Modifier.padding(end = 6.dp)) {
                                            Text(
                                                text = cleanName,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "ID: $childCode • ${if (isSelected) "Active Device" else "Paired Device"}",
                                                fontSize = 11.sp,
                                                color = if (isSelected) BrandBlue else TextSecondary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (!isSelected) {
                                            Button(
                                                onClick = {
                                                    activeChildCode = childCode
                                                    selectedChildUid = childUid
                                                    childQuestsList = if (activeFamilyId.isNotBlank()) {
                                                        tasksByChild[childUid] ?: tasksByChild[childCode] ?: emptyList()
                                                    } else {
                                                        ChildQuestManager.getQuests(context, childCode)
                                                    }
                                                    remainingSeconds = ScreenTimeManager.getRemainingSeconds(context, childCode)
                                                    isLocked = ScreenTimeManager.isDeviceLocked(context, childCode)
                                                    Toast.makeText(context, "Switched active child to $cleanName", Toast.LENGTH_SHORT).show()
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                            ) {
                                                Text("Select", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }

                                        IconButton(
                                            onClick = {
                                                val safeUid = if (childUid.isNotBlank() && !childUid.startsWith("HS-", ignoreCase = true)) {
                                                    childUid
                                                } else {
                                                    resolveChildUid(childName, childCode, familyMembers)
                                                }
                                                FamilyManager.removeFamilyMember(
                                                    context = context,
                                                    familyId = activeFamilyId,
                                                    memberUserId = safeUid,
                                                    childCode = childCode,
                                                    childName = childName
                                                ) {
                                                    val updatedList = ChildIdManager.getAllSavedChildren(context)
                                                    savedChildrenList = updatedList
                                                    familyMembers = familyMembers.filter {
                                                        it.userId != safeUid && (childCode.isBlank() || !it.childCode.equals(childCode, ignoreCase = true))
                                                    }
                                                    if (childCode == activeChildCode || (safeUid.isNotBlank() && safeUid == selectedChildUid)) {
                                                        val remaining = effectiveChildren.filter {
                                                            it.childCode != childCode && (safeUid.isBlank() || it.childUid != safeUid)
                                                        }
                                                        if (remaining.isNotEmpty()) {
                                                            activeChildCode = remaining.first().childCode
                                                            selectedChildUid = remaining.first().childUid
                                                            childQuestsList = ChildQuestManager.getQuests(context, activeChildCode)
                                                        } else {
                                                            activeChildCode = ""
                                                            selectedChildUid = ""
                                                            childQuestsList = emptyList()
                                                        }
                                                    }
                                                    Toast.makeText(context, "Removed $cleanName from family", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.size(34.dp).background(RestrictionRedBg, CircleShape)
                                        ) {
                                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = RestrictionRed, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = BorderGrey)
                    Text("ADD / PAIR NEW CHILD", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp)

                    OutlinedTextField(
                        value = inputNewChildName,
                        onValueChange = { inputNewChildName = it },
                        label = { Text("Child Name (Compulsory)") },
                        placeholder = { Text("Enter child's name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = inputNewChildCode,
                        onValueChange = { inputNewChildCode = it },
                        label = { Text("Pairing Code (Optional HS-XXXXXX)") },
                        placeholder = { Text("Enter pairing code (e.g. HS-XXXXXX)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val formattedName = ChildIdManager.formatChildName(inputNewChildName)
                        if (inputNewChildName.isBlank() || inputNewChildName.contains("@")) {
                            Toast.makeText(context, "Please enter a valid Child Name (cannot be empty or an email address)", Toast.LENGTH_LONG).show()
                            ChildIdManager.addChildProfile(context, formattedName, inputNewChildCode)
                            val remaining = ChildIdManager.getAllSavedChildren(context)
                            savedChildrenList = remaining
                            val newCode = if (inputNewChildCode.isNotBlank()) inputNewChildCode.trim().uppercase() else remaining.last().second
                            activeChildCode = newCode
                            childQuestsList = if (activeFamilyId.isNotBlank()) {
                                tasksByChild[selectedChildUid] ?: tasksByChild[newCode] ?: emptyList()
                            } else {
                                ChildQuestManager.getQuests(context, newCode)
                            }

                            // Sync Guardian Profile to newly saved/paired child
                            val gName = activeGuardianName.ifBlank { com.homesync.app.util.AuthManager.getGuardianName(context) }.ifBlank { "Guardian" }
                            FirebaseRealtimeSyncManager.syncGuardianProfile(context, gName, newCode, "")
                            FirebaseSyncManager.syncGuardianProfileToCloud(context, gName, newCode, "")

                            // Send handshake notification to child device
                            com.homesync.app.util.FirebaseSyncManager.sendNotificationToCloud(
                                com.homesync.app.util.SystemNotification(
                                    title = "Guardian Connected! 🛡️",
                                    message = "$gName has successfully linked with your device.",
                                    type = com.homesync.app.util.NotificationType.CHILD_SAFE_CHECKIN,
                                    childName = formattedName,
                                    childCode = newCode,
                                    targetRole = "CHILD"
                                )
                            )

                            inputNewChildName = ""
                            inputNewChildCode = ""
                            showManageChildrenModal = false
                            Toast.makeText(context, "Saved & paired child profile for $formattedName", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SafeGreen),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Save & Pair Child", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showManageChildrenModal = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = CardWhite
        )
    }

    // Pair Child Dialog
    if (showPairChildDialog) {
        AlertDialog(
            onDismissRequest = { showPairChildDialog = false },
            title = { Text("Pair a Child Device", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Enter the child's name and the unique device code (HS-XXXXXX).", color = TextSecondary, fontSize = 13.sp)
                    
                    OutlinedTextField(
                        value = inputChildName,
                        onValueChange = { inputChildName = it },
                        label = { Text("Child Name (Optional)") },
                        placeholder = { Text("Enter child's name") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandBlue,
                            focusedLabelColor = BrandBlue
                        )
                    )

                    OutlinedTextField(
                        value = inputChildCode,
                        onValueChange = { inputChildCode = it },
                        label = { Text("Child Device Code (HS-XXXXXX)") },
                        placeholder = { Text("Enter child device code") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BrandBlue,
                            focusedLabelColor = BrandBlue
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanCode = inputChildCode.trim().uppercase()
                        if (cleanCode.isNotBlank()) {
                            val derivedName = if (inputChildName.isNotBlank()) {
                                ChildIdManager.formatChildName(inputChildName)
                            } else {
                                ChildIdManager.getChildName(context, cleanCode).ifBlank {
                                    ChildIdManager.formatChildName("Child " + cleanCode.takeLast(4))
                                }
                            }
                            ChildIdManager.addChildProfile(context, derivedName, cleanCode)
                            val remaining = ChildIdManager.getAllSavedChildren(context)
                            savedChildrenList = remaining
                            activeChildCode = cleanCode
                            childQuestsList = if (activeFamilyId.isNotBlank()) {
                                tasksByChild[selectedChildUid] ?: tasksByChild[cleanCode] ?: emptyList()
                            } else {
                                ChildQuestManager.getQuests(context, cleanCode)
                            }

                            // Sync Guardian Profile to this newly paired child
                            val gName = activeGuardianName.ifBlank { com.homesync.app.util.AuthManager.getGuardianName(context) }.ifBlank { "Guardian" }
                            FirebaseRealtimeSyncManager.syncGuardianProfile(context, gName, cleanCode, "")
                            FirebaseSyncManager.syncGuardianProfileToCloud(context, gName, cleanCode, "")


                            // Send handshake notification to the child device
                            com.homesync.app.util.FirebaseSyncManager.sendNotificationToCloud(
                                com.homesync.app.util.SystemNotification(
                                    title = "Guardian Connected! 🛡️",
                                    message = "$gName has successfully linked with your device.",
                                    type = com.homesync.app.util.NotificationType.CHILD_SAFE_CHECKIN,
                                    childName = derivedName,
                                    childCode = cleanCode,
                                    targetRole = "CHILD"
                                )
                            )

                            inputChildCode = ""
                            inputChildName = ""
                            showPairChildDialog = false
                            Toast.makeText(context, "Paired successfully with $derivedName ($cleanCode)", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Please enter a valid Child Device Code", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                ) {
                    Text("Pair Device", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPairChildDialog = false }) {
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

    // Full-Screen Zoomable & Pannable Photo Proof Viewer for GuardianHomeScreen
    previewImageBitmap?.let { bmp ->
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }

        Dialog(
            onDismissRequest = { previewImageBitmap = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.8f, 5f)
                                offset = if (scale > 1f) {
                                    Offset(
                                        x = offset.x + pan.x,
                                        y = offset.y + pan.y
                                    )
                                } else {
                                    Offset.Zero
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Task photo proof full screen zoomable",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
                    )
                }

                IconButton(
                    onClick = { previewImageBitmap = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(24.dp)
                        .size(40.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

// Compact Quick-Glance Card Component
@Composable
private fun GlanceCard(
    title: String,
    value: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    iconBg: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BorderGrey),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(iconBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
                }
            }
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
            Text(subtitle, fontSize = 11.sp, color = SafeGreen, fontWeight = FontWeight.Medium)
        }
    }
}

// Child Detail Dialog
@Composable
private fun ChildDetailDialog(
    childName: String,
    childCode: String = "",
    childUid: String = "",
    childPhotoBitmap: Bitmap? = null,
    tasksByChild: Map<String, List<ChildQuest>> = emptyMap(),
    onDismiss: () -> Unit,
    onNavigateTab: (String) -> Unit
) {
    val cleanCode = childCode.trim().uppercase()
    var liveRemainingSeconds by remember(cleanCode) { mutableStateOf<Int?>(null) }
    var liveUsedSeconds by remember(cleanCode) { mutableStateOf<Int?>(null) }
    var liveAllowanceSeconds by remember(cleanCode) { mutableStateOf<Int?>(null) }
    var liveIsLocked by remember(cleanCode) { mutableStateOf<Boolean?>(null) }

    DisposableEffect(cleanCode) {
        val cancel = if (cleanCode.isNotBlank()) {
            FirebaseRealtimeSyncManager.listenScreenTime(cleanCode) { rem, locked, tot, used ->
                liveRemainingSeconds = rem
                liveIsLocked = locked
                liveAllowanceSeconds = tot
                liveUsedSeconds = used
            }
        } else null
        onDispose {
            cancel?.invoke()
        }
    }

    val tasksForChild = tasksByChild[childUid] ?: tasksByChild[cleanCode] ?: emptyList()
    val remainingTasksCount = tasksForChild.count { it.status != QuestStatus.APPROVED }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(BrandBlue),
                        contentAlignment = Alignment.Center
                    ) {
                        if (childPhotoBitmap != null) {
                            Image(
                                bitmap = childPhotoBitmap.asImageBitmap(),
                                contentDescription = childName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(childName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    Column {
                        Text(childName, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                        Text(
                            text = when (liveIsLocked) {
                                true -> "Locked"
                                false -> "Online"
                                null -> "Connecting..."
                            },
                            fontSize = 11.sp,
                            color = when (liveIsLocked) {
                                true -> RestrictionRed
                                false -> SafeGreen
                                null -> WarningAmber
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
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
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SoftBg),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, BorderGrey)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Location: Not available", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                            val screenTimeText = if (liveRemainingSeconds != null) {
                                val rem = ScreenTimeManager.formatHoursAndMinutes(liveRemainingSeconds!!)
                                "Screen Time: $rem left"
                            } else {
                                "Screen Time: Loading..."
                            }
                            Text(screenTimeText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BrandBlue)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Battery: Not available", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                            val taskCountText = if (tasksForChild.isNotEmpty() || tasksByChild.isNotEmpty()) {
                                "Tasks: $remainingTasksCount remaining"
                            } else {
                                "Tasks: None"
                            }
                            Text(taskCountText, fontSize = 12.sp, color = WarningAmber, fontWeight = FontWeight.Bold)
                        }
                        if (liveUsedSeconds != null && liveAllowanceSeconds != null) {
                            val used = ScreenTimeManager.formatHoursAndMinutes(liveUsedSeconds!!)
                            val allowanceH = liveAllowanceSeconds!! / 3600
                            Text("Used: $used today (Allowance: ${allowanceH}h)", fontSize = 11.sp, color = TextSecondary)
                        }
                    }
                }

                Text("CONTROL CENTER", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary, letterSpacing = 1.sp)

                val controls = listOf(
                    "Screen Time" to Icons.Filled.Schedule,
                    "Apps & Websites" to Icons.Filled.Category,
                    "App Limits" to Icons.Filled.Lock,
                    "Safe Zones" to Icons.Filled.Map,
                    "Curfew" to Icons.Filled.NightsStay,
                    "School Mode" to Icons.Filled.School,
                    "Tasks" to Icons.Filled.Checklist,
                    "Device Protection" to Icons.Filled.Security
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    controls.chunked(2).forEach { rowPair ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowPair.forEach { (ctrlTitle, ctrlIcon) ->
                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            when (ctrlTitle) {
                                                "Safe Zones" -> onNavigateTab("Map")
                                                "Tasks" -> onNavigateTab("Tasks")
                                                else -> onNavigateTab("Settings")
                                            }
                                        },
                                    colors = CardDefaults.cardColors(containerColor = CardWhite),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, BorderGrey)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFFEFF6FF)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(ctrlIcon, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(16.dp))
                                        }
                                        Text(ctrlTitle, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        containerColor = CardWhite
    )
}

// Map Tab View
@Composable
fun GuardianMapTabView(activeChildCode: String, activeGuardianName: String = "Guardian") {
    val context = LocalContext.current
    val savedChildren = remember(activeChildCode) { ChildIdManager.getAllSavedChildren(context) }
    val isChildConnected = savedChildren.isNotEmpty() && activeChildCode.isNotBlank()
    val cleanChildName = if (isChildConnected) ChildIdManager.getChildName(context, activeChildCode) else ""

    val savedLoc = remember(activeChildCode, isChildConnected) {
        if (isChildConnected) ChildIdManager.getChildLocation(context, activeChildCode) else null
    }
    val currentAddress = if (isChildConnected) (savedLoc?.third ?: "Live GPS Tracking Active") else "No Child Connected"
    val latLngText = if (isChildConnected) (if (savedLoc != null) "Lat: %.4f, Lng: %.4f".format(savedLoc.first, savedLoc.second) else "Live GPS Connected") else "Pair a child device to monitor location"

    var isChildOnline by remember(activeChildCode) { mutableStateOf(false) }
    var childLastSeen by remember(activeChildCode) { mutableStateOf(0L) }

    DisposableEffect(activeChildCode) {
        var cancel: (() -> Unit)? = null
        if (activeChildCode.isNotBlank()) {
            cancel = FirebaseRealtimeSyncManager.listenChildProfile(context, activeChildCode) { prof ->
                isChildOnline = prof.isConnected
                childLastSeen = prof.lastActiveTime
            }
        }
        onDispose {
            cancel?.invoke()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 8.dp)
    ) {
        LiveSafetyMap(
            childName = cleanChildName,
            guardianName = activeGuardianName,
            linkedChildCode = if (isChildConnected) activeChildCode else "",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp)
        ) {
            // Telemetry Info Card (Showing Both Guardian & Child Live Info or No Child state)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = CardWhite),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            border = BorderStroke(1.dp, BorderGrey)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (isChildConnected) {
                    // Header Row when Child is Connected
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(BrandBlue),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(cleanChildName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            }
                            Column {
                                Text(cleanChildName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text("Inside Safe Zone", fontSize = 12.sp, color = SafeGreen, fontWeight = FontWeight.Bold)
                            }
                        }
                        val now = System.currentTimeMillis()
                        val isRecentlyActive = isChildOnline || (now - childLastSeen < 90_000L && childLastSeen > 0L)
                        val statusText = if (isRecentlyActive) "Live Online" else if (childLastSeen > 0L) "Last seen ${((now - childLastSeen) / 60000).coerceAtLeast(1)}m ago" else "Live GPS Sync"
                        val statusColor = if (isRecentlyActive) SafeGreen else BrandBlue
                        val statusBg = if (isRecentlyActive) SafeGreenBg else Color(0xFFEFF6FF)

                        Surface(shape = RoundedCornerShape(50), color = statusBg, border = BorderStroke(1.dp, statusColor.copy(alpha = 0.3f))) {
                            Text(statusText, fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                    }

                    HorizontalDivider(color = BorderGrey)

                    // Guardian ("Me") Status Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(SafeGreen))
                        Column {
                            Text("👤 MY LOCATION (GUARDIAN)", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                            Text("Parent Device Active • Live Tracking", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                    }

                    // Child Location Address Card
                    Surface(shape = RoundedCornerShape(12.dp), color = SoftBg, border = BorderStroke(1.dp, BorderGrey)) {
                        Column(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(BrandBlue))
                                Text("🧒 CHILD LOCATION ($cleanChildName)", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                            }
                            Text(currentAddress, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text(latLngText, fontSize = 11.sp, color = BrandBlue, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    // Header Row when NO Child is Connected
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(TextSecondary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.ChildCare, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                            }
                            Column {
                                Text("No Child Connected", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text("No active child device linked", fontSize = 12.sp, color = TextSecondary)
                            }
                        }
                        Surface(shape = RoundedCornerShape(50), color = WarningAmberBg, border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.3f))) {
                            Text("Standby", fontSize = 11.sp, color = WarningAmber, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                    }

                    HorizontalDivider(color = BorderGrey)

                    // Guardian ("Me") Status Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(SafeGreen))
                        Column {
                            Text("👤 MY LOCATION (GUARDIAN)", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                            Text("Parent Device Active • GPS Ready", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                    }

                    // Notice Card
                    Surface(shape = RoundedCornerShape(12.dp), color = SoftBg, border = BorderStroke(1.dp, BorderGrey)) {
                        Column(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("🧒 CHILD LOCATION", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                            Text("No Child Connected", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("Pair a child device using their Unique ID to view real-time location and safe zones.", fontSize = 11.sp, color = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}
}

// Activity Tab View
@Composable
fun GuardianActivityTabView(activeChildCode: String) {
    val context = LocalContext.current
    var liveActivities by remember(activeChildCode) {
        mutableStateOf<List<com.homesync.app.util.ChildActivityCloudItem>>(emptyList())
    }

    DisposableEffect(activeChildCode) {
        var reg: com.google.firebase.firestore.ListenerRegistration? = null
        if (activeChildCode.isNotBlank()) {
            reg = FirebaseSyncManager.listenChildActivitiesFromCloud(context, activeChildCode) { list ->
                liveActivities = list
            }
        }
        onDispose {
            reg?.remove()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Activity Log", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text("Real-time safety events, tasks & notifications", fontSize = 12.sp, color = TextSecondary)

        if (liveActivities.isEmpty()) {
            listOf(
                Triple("4:32 PM", "Child entered Home Safe Zone", SafeGreen),
                Triple("3:10 PM", "Screen-time limit active", WarningAmber),
                Triple("2:45 PM", "Completed task: Math Homework", BrandBlue)
            ).forEach { (time, desc, color) ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardWhite),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, BorderGrey)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
                        Column {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(ChildIdManager.getChildName(context, activeChildCode).ifBlank { "Child" }, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(time, fontSize = 10.sp, color = TextSecondary)
                            }
                            Text(desc, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        } else {
            liveActivities.forEach { item ->
                val timeStr = remember(item.timestamp) {
                    val sdf = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                    sdf.format(java.util.Date(item.timestamp))
                }
                val chipColor = when (item.category) {
                    "TASK" -> BrandBlue
                    "CHECKIN" -> SafeGreen
                    else -> WarningAmber
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardWhite),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, BorderGrey)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(chipColor))
                        Column {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(ChildIdManager.getChildName(context, item.childCode).ifBlank { "Child" }, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text(timeStr, fontSize = 10.sp, color = TextSecondary)
                            }
                            Text(item.title, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
                            if (item.detail.isNotBlank()) {
                                Text(item.detail, fontSize = 12.sp, color = TextSecondary, modifier = Modifier.padding(top = 1.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class DisplayTaskItem(
    val quest: ChildQuest,
    val childUid: String,
    val childCode: String,
    val childName: String
)

// Tasks Tab View
@Composable
fun GuardianTasksTabView(
    activeChildCode: String,
    activeFamilyId: String = "",
    currentUserId: String = "",
    familyMembers: List<FamilyMember> = emptyList(),
    selectedChildUid: String = "",
    tasksByChild: Map<String, List<ChildQuest>> = emptyMap(),
    onTasksByChildUpdated: ((Map<String, List<ChildQuest>>) -> Unit)? = null
) {
    val context = LocalContext.current
    val approvedChildren: List<GuardianChildItem> = remember(familyMembers) {
        val remote = familyMembers.filter { it.role == FamilyRole.CHILD && it.status == MemberStatus.APPROVED }
        if (remote.isNotEmpty()) {
            remote.map { m ->
                val name = m.name.ifBlank { "Child" }
                val uid = m.userId
                val code = m.childCode.ifBlank { ChildIdManager.resolveChildCode(context, uid, name, familyMembers) }
                GuardianChildItem(name, uid, code)
            }
        } else {
            val saved = ChildIdManager.getAllSavedChildren(context)
            if (saved.isNotEmpty()) {
                saved.map { (sName, sCode) ->
                    val resolvedUid = resolveChildUid(sName, sCode, familyMembers)
                    val safeUid = if (resolvedUid.startsWith("HS-", ignoreCase = true)) "" else resolvedUid
                    GuardianChildItem(sName, safeUid, sCode)
                }
            } else if (activeChildCode.isNotBlank()) {
                val resolvedUid = resolveChildUid("Child", activeChildCode, familyMembers)
                val safeUid = if (selectedChildUid.isNotBlank() && !selectedChildUid.startsWith("HS-")) selectedChildUid else (if (!resolvedUid.startsWith("HS-")) resolvedUid else "")
                listOf(GuardianChildItem("Child", safeUid, activeChildCode))
            } else {
                emptyList()
            }
        }
    }

    var selectedFilterChildUid by remember(activeChildCode, selectedChildUid, approvedChildren) {
        val defaultFilter = approvedChildren.firstOrNull { it.childUid.isNotBlank() && !it.childUid.startsWith("HS-") }?.childUid ?: "ALL"
        mutableStateOf(if (approvedChildren.size == 1) defaultFilter else "ALL")
    }
    var selectedTargetChildUid by remember(activeChildCode, selectedChildUid, approvedChildren) {
        val defaultTarget = approvedChildren.firstOrNull { it.childUid.isNotBlank() && !it.childUid.startsWith("HS-") }?.childUid
            ?: (if (selectedChildUid.isNotBlank() && !selectedChildUid.startsWith("HS-")) selectedChildUid else "ALL")
        mutableStateOf(defaultTarget)
    }

    var showAddTaskModal by remember { mutableStateOf(false) }
    var newTaskTitle by remember { mutableStateOf("") }
    var newTaskStars by remember { mutableStateOf("25") }
    var newTaskDue by remember { mutableStateOf("Due 8:00 PM") }
    var previewImageBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    val displayTasks = remember(selectedFilterChildUid, approvedChildren, tasksByChild) {
        val list = mutableListOf<DisplayTaskItem>()
        if (selectedFilterChildUid == "ALL") {
            for (child in approvedChildren) {
                val childTasks = if (activeFamilyId.isNotBlank()) {
                    tasksByChild[child.childUid] ?: emptyList()
                } else {
                    tasksByChild[child.childUid] ?: ChildQuestManager.getQuests(context, child.childCode)
                }
                for (q in childTasks) {
                    list.add(DisplayTaskItem(q, child.childUid, child.childCode, child.name))
                }
            }
        } else {
            val child = approvedChildren.find { it.childUid == selectedFilterChildUid }
            val cName = child?.name ?: "Child"
            val cCode = child?.childCode ?: ""
            val childTasks = if (activeFamilyId.isNotBlank()) {
                tasksByChild[selectedFilterChildUid] ?: emptyList()
            } else {
                tasksByChild[selectedFilterChildUid] ?: (if (cCode.isNotBlank()) ChildQuestManager.getQuests(context, cCode) else emptyList())
            }
            for (q in childTasks) {
                list.add(DisplayTaskItem(q, selectedFilterChildUid, cCode, cName))
            }
        }
        list
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Child Quests & Verification", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text("Assign tasks & verify photo proof submissions", fontSize = 11.sp, color = TextSecondary)
            }
            Button(
                onClick = { 
                    selectedTargetChildUid = if (selectedFilterChildUid != "ALL") selectedFilterChildUid else (if (approvedChildren.isNotEmpty()) approvedChildren.first().childUid else (if (selectedChildUid.isNotBlank()) selectedChildUid else activeChildCode))
                    showAddTaskModal = true 
                },
                colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Add Task", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                }
            }
        }

        // Child Filter Row (All Children vs Specific Child)
        if (approvedChildren.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isAllSelected = selectedFilterChildUid == "ALL"
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isAllSelected) BrandBlue else SoftBg,
                    border = BorderStroke(1.dp, if (isAllSelected) BrandBlue else BorderGrey),
                    modifier = Modifier.clickable { selectedFilterChildUid = "ALL" }
                ) {
                    Text(
                        "All Children",
                        color = if (isAllSelected) Color.White else TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                approvedChildren.forEach { child ->
                    val isSelected = selectedFilterChildUid == child.childUid
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSelected) BrandBlue else SoftBg,
                        border = BorderStroke(1.dp, if (isSelected) BrandBlue else BorderGrey),
                        modifier = Modifier.clickable { selectedFilterChildUid = child.childUid }
                    ) {
                        Text(
                            child.name,
                            color = if (isSelected) Color.White else TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        if (displayTasks.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CardWhite),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, BorderGrey)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = SafeGreen, modifier = Modifier.size(36.dp))
                    Text("No Tasks Yet", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("Create tasks above to help kids build good daily habits.", fontSize = 12.sp, color = TextSecondary)
                }
            }
        } else {
            displayTasks.forEach { taskItem ->
                val quest = taskItem.quest
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardWhite),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, BorderGrey)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(quest.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = BrandBlue.copy(alpha = 0.1f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Text("👤 ${taskItem.childName}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = BrandBlue)
                                        }
                                    }
                                }
                                Text("${quest.dueTime} • +${quest.rewardStars} Stars Reward", fontSize = 11.sp, color = BrandBlue, fontWeight = FontWeight.Bold)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Surface(shape = RoundedCornerShape(50), color = Color(quest.status.badgeColorHex).copy(alpha = 0.15f)) {
                                    Text(quest.status.label, color = Color(quest.status.badgeColorHex), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                }
                                 IconButton(
                                    onClick = {
                                        if (activeFamilyId.isNotBlank()) {
                                            val deleteUid = quest.childUserId.ifBlank { taskItem.childUid }
                                            FamilyTaskManager.deleteTask(context, activeFamilyId, deleteUid, quest.id) { success ->
                                                if (success) {
                                                    Toast.makeText(context, "Task deleted", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        } else {
                                            ChildQuestManager.deleteQuest(context, taskItem.childCode, quest.id)
                                            Toast.makeText(context, "Task deleted", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete Task", tint = RestrictionRed, modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        // Task Photo Proof Image & Label Section
                        if (quest.photoProofLabel.isNotBlank() || quest.photoProofUri.isNotBlank() || quest.status == QuestStatus.SUBMITTED) {
                            var proofBitmap by remember(quest.photoProofUri, quest.id) {
                                mutableStateOf(com.homesync.app.util.TaskProofImageManager.getProofBitmap(context, quest.photoProofUri, quest.id))
                            }
                            LaunchedEffect(quest.photoProofUri, quest.id) {
                                com.homesync.app.util.TaskProofImageManager.loadProofBitmap(context, quest.photoProofUri, quest.id) { loaded ->
                                    proofBitmap = loaded
                                }
                            }

                            Surface(
                                color = Color.White,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, BorderGrey),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Filled.Image, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(20.dp))
                                        Text(
                                            if (quest.photoProofLabel.isNotBlank()) "Photo Proof: ${quest.photoProofLabel}" else "Task Photo Proof Received",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                    }

                                    if (proofBitmap != null) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(180.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable { previewImageBitmap = proofBitmap }
                                        ) {
                                            androidx.compose.foundation.Image(
                                                bitmap = proofBitmap!!.asImageBitmap(),
                                                contentDescription = "Task photo proof",
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            Surface(
                                                color = Color.Black.copy(alpha = 0.6f),
                                                shape = RoundedCornerShape(20),
                                                modifier = Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .padding(8.dp)
                                            ) {
                                                Text("🔍 Tap to Enlarge", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                                            }
                                        }
                                    } else if (quest.photoProofUri.isNotBlank()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(100.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xFFF3F4F6)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = BrandBlue)
                                                Text("Loading high-res photo proof...", fontSize = 11.sp, color = TextSecondary)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (quest.status == QuestStatus.SUBMITTED) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (activeFamilyId.isNotBlank()) {
                                            val verifyUid = quest.childUserId.ifBlank { taskItem.childUid }
                                            FamilyTaskManager.verifyTask(
                                                context = context,
                                                familyId = activeFamilyId,
                                                childUserId = verifyUid,
                                                questId = quest.id,
                                                approve = true,
                                                rewardStars = quest.rewardStars
                                            ) { success ->
                                                if (success) {
                                                    Toast.makeText(context, "Approved! ${quest.rewardStars} Stars awarded to ${taskItem.childName}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        } else {
                                            ChildQuestManager.verifyQuest(context, taskItem.childCode, quest.id, approve = true)
                                            Toast.makeText(context, "Approved! ${quest.rewardStars} Stars awarded to ${taskItem.childName}", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = SafeGreen),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Approve Task", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = {
                                        if (activeFamilyId.isNotBlank()) {
                                            val verifyUid = quest.childUserId.ifBlank { taskItem.childUid }
                                            FamilyTaskManager.verifyTask(
                                                context = context,
                                                familyId = activeFamilyId,
                                                childUserId = verifyUid,
                                                questId = quest.id,
                                                approve = false
                                            ) { success ->
                                                if (success) {
                                                    Toast.makeText(context, "Requested re-do from ${taskItem.childName}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        } else {
                                            ChildQuestManager.verifyQuest(context, taskItem.childCode, quest.id, approve = false)
                                            Toast.makeText(context, "Requested re-do from ${taskItem.childName}", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Request Re-do", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = RestrictionRed)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Photo Proof Enlarged Image Dialog with Pinch-to-Zoom & Pan
    previewImageBitmap?.let { bmp ->
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

        AlertDialog(
            onDismissRequest = { previewImageBitmap = null },
            title = { Text("📷 Task Photo Proof", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1.2f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                        offset = Offset.Zero
                                    }
                                    android.util.Log.d("GuardianHomeScreen", "TASK_PROOF_VIEW_ZOOM_CHANGED doubleTap scale=$scale")
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val oldScale = scale
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                if (scale > 1f) {
                                    val maxX = (size.width.toFloat() * (scale - 1f)) / 2f
                                    val maxY = (size.height.toFloat() * (scale - 1f)) / 2f
                                    offset = Offset(
                                        x = (offset.x + pan.x).coerceIn(-maxX, maxX),
                                        y = (offset.y + pan.y).coerceIn(-maxY, maxY)
                                    )
                                } else {
                                    offset = Offset.Zero
                                }
                                if (scale != oldScale) {
                                    android.util.Log.d("GuardianHomeScreen", "TASK_PROOF_VIEW_ZOOM_CHANGED scale=$scale offsetX=${offset.x} offsetY=${offset.y}")
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Task photo proof enlarged",
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
                    )
                    if (scale > 1.05f) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .clickable {
                                    scale = 1f
                                    offset = androidx.compose.ui.geometry.Offset.Zero
                                }
                        ) {
                            Text("Reset Zoom", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { previewImageBitmap = null },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue)
                ) {
                    Text("Close", fontWeight = FontWeight.Bold)
                }
            },
            containerColor = CardWhite
        )
    }

    if (showAddTaskModal) {
        AlertDialog(
            onDismissRequest = { showAddTaskModal = false },
            title = {
                Text("Assign New Task", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Assign To Child Selection Chips
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Assign Task To:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (approvedChildren.size > 1) {
                                val isAllSelected = selectedTargetChildUid == "ALL"
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (isAllSelected) BrandBlue else SoftBg,
                                    border = BorderStroke(1.dp, if (isAllSelected) BrandBlue else BorderGrey),
                                    modifier = Modifier.clickable { selectedTargetChildUid = "ALL" }
                                ) {
                                    Text(
                                        "All Children",
                                        color = if (isAllSelected) Color.White else TextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }

                            approvedChildren.forEach { child ->
                                val isSelected = selectedTargetChildUid == child.childUid
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (isSelected) BrandBlue else SoftBg,
                                    border = BorderStroke(1.dp, if (isSelected) BrandBlue else BorderGrey),
                                    modifier = Modifier.clickable { selectedTargetChildUid = child.childUid }
                                ) {
                                    Text(
                                        child.name,
                                        color = if (isSelected) Color.White else TextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = newTaskTitle,
                        onValueChange = { newTaskTitle = it },
                        label = { Text("Task Title") },
                        placeholder = { Text("e.g. Read 20 pages of science book") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newTaskStars,
                        onValueChange = { newTaskStars = it },
                        label = { Text("Reward Stars") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newTaskDue,
                        onValueChange = { newTaskDue = it },
                        label = { Text("Due Time") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newTaskTitle.isNotBlank()) {
                            val stars = newTaskStars.toIntOrNull() ?: 25
                            val dueTime = newTaskDue.trim().ifBlank { "Due 8:00 PM" }

                            if (selectedTargetChildUid == "ALL") {
                                for (child in approvedChildren) {
                                    val safeUid = if (!child.childUid.startsWith("HS-")) child.childUid else resolveChildUid(child.name, child.childCode, familyMembers)
                                    if (activeFamilyId.isNotBlank()) {
                                        if (safeUid.isNotBlank() && !safeUid.startsWith("HS-")) {
                                            FamilyTaskManager.createTask(
                                                familyId = activeFamilyId,
                                                childUserId = safeUid,
                                                title = newTaskTitle.trim(),
                                                rewardStars = stars,
                                                dueTime = dueTime
                                            ) { res ->
                                                res.onSuccess { createdTask ->
                                                    android.util.Log.i("GuardianHomeScreen", "TASK_CREATE_LOCAL_STATE_UPDATED taskId=${createdTask.id} childUserId=$safeUid")
                                                    val currentTasks = tasksByChild[safeUid] ?: emptyList()
                                                    if (currentTasks.none { it.id == createdTask.id }) {
                                                        val updated = tasksByChild.toMutableMap().apply {
                                                            put(safeUid, listOf(createdTask) + currentTasks)
                                                        }
                                                        onTasksByChildUpdated?.invoke(updated)
                                                    }
                                                }
                                            }
                                        } else {
                                            android.util.Log.e("GuardianHomeScreen", "TASK_IDENTITY_ERROR: cannot assign task to ${child.name}, childUid is invalid ($safeUid)")
                                        }
                                    } else {
                                        ChildQuestManager.addCustomQuest(context, child.childCode, newTaskTitle.trim(), stars, dueTime)
                                    }
                                }
                                Toast.makeText(context, "New task assigned to all children! 🌟", Toast.LENGTH_SHORT).show()
                            } else {
                                val targetChild = approvedChildren.find { it.childUid == selectedTargetChildUid }
                                val rawUid = targetChild?.childUid ?: selectedTargetChildUid
                                val safeUid = if (!rawUid.startsWith("HS-")) rawUid else resolveChildUid(targetChild?.name ?: "Child", targetChild?.childCode ?: activeChildCode, familyMembers)
                                if (activeFamilyId.isNotBlank()) {
                                    if (safeUid.isNotBlank() && !safeUid.startsWith("HS-")) {
                                        FamilyTaskManager.createTask(
                                            familyId = activeFamilyId,
                                            childUserId = safeUid,
                                            title = newTaskTitle.trim(),
                                            rewardStars = stars,
                                            dueTime = dueTime
                                        ) { res ->
                                            res.onSuccess { createdTask ->
                                                android.util.Log.i("GuardianHomeScreen", "TASK_CREATE_LOCAL_STATE_UPDATED taskId=${createdTask.id} childUserId=$safeUid")
                                                val currentTasks = tasksByChild[safeUid] ?: emptyList()
                                                if (currentTasks.none { it.id == createdTask.id }) {
                                                    val updated = tasksByChild.toMutableMap().apply {
                                                        put(safeUid, listOf(createdTask) + currentTasks)
                                                    }
                                                    onTasksByChildUpdated?.invoke(updated)
                                                }
                                            }
                                        }
                                    } else {
                                        android.util.Log.e("GuardianHomeScreen", "TASK_IDENTITY_ERROR: cannot assign task, childUid is invalid ($safeUid)")
                                    }
                                } else {
                                    ChildQuestManager.addCustomQuest(context, targetChild?.childCode ?: selectedTargetChildUid, newTaskTitle.trim(), stars, dueTime)
                                }
                                val targetName = targetChild?.name ?: "Child"
                                Toast.makeText(context, "New task assigned to $targetName! 🌟", Toast.LENGTH_SHORT).show()
                            }

                            showAddTaskModal = false
                            newTaskTitle = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Assign Task", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddTaskModal = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}

// Settings Tab View
@Composable
fun GuardianSettingsTabView(
    activeChildCode: String,
    onLogout: () -> Unit,
    onPairNewChild: () -> Unit
) {
    val context = LocalContext.current
    val activeFamilyId = remember { FamilyManager.getStoredFamilyId(context) }
    var rules by remember(activeChildCode) { mutableStateOf(ParentalControlManager.getRules(context, activeChildCode)) }

    DisposableEffect(activeChildCode) {
        val cancel = ParentalControlManager.listenRulesFromCloud(context, activeChildCode) { updatedRules ->
            rules = updatedRules
        }
        onDispose { cancel?.invoke() }
    }

    var sliderValue by remember(activeChildCode, rules.maxDailyAllowanceMinutes, rules.maxDailyAllowanceHours) {
        val initialHours = if (rules.maxDailyAllowanceMinutes > 0) {
            rules.maxDailyAllowanceMinutes / 60f
        } else if (rules.maxDailyAllowanceHours > 0) {
            rules.maxDailyAllowanceHours
        } else {
            val stored = ScreenTimeManager.getTotalAllowance(context, activeChildCode)
            if (stored > 0) stored / 3600f else 4.0f
        }
        mutableStateOf(initialHours.coerceIn(0.5f, 8f))
    }

    val totalMinutes = (sliderValue * 60).roundToInt()
    val displayHours = totalMinutes / 60
    val displayMinutes = totalMinutes % 60
    val displayStr = if (displayMinutes > 0) "${displayHours}h ${displayMinutes}m / day" else "${displayHours}h / day"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("Parental Controls", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text("Configure rules & security limits", fontSize = 12.sp, color = TextSecondary)

        SettingsSectionCard(title = "SCREEN TIME", icon = Icons.Filled.Schedule, accentColor = BrandBlue) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Daily Time Limit", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text(displayStr, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = BrandBlue)
                }
                Slider(
                    value = sliderValue,
                    onValueChange = { newVal ->
                        sliderValue = (newVal * 2).roundToInt() / 2f
                    },
                    valueRange = 0.5f..8f,
                    steps = 14,
                    colors = SliderDefaults.colors(thumbColor = BrandBlue, activeTrackColor = BrandBlue)
                )
            }
        }

        SettingsSectionCard(title = "SCHEDULE", icon = Icons.Filled.CalendarMonth, accentColor = BrandBlue) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Curfew Lock", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text("${rules.curfewStartTime} – ${rules.curfewEndTime}", fontSize = 11.sp, color = TextSecondary)
                }
                Switch(
                    checked = rules.curfewEnabled,
                    onCheckedChange = {
                        val updated = rules.copy(curfewEnabled = it)
                        rules = updated
                        ParentalControlManager.syncRulesToCloud(context, updated, activeFamilyId)
                        Toast.makeText(context, if (it) "Curfew enabled" else "Curfew disabled", Toast.LENGTH_SHORT).show()
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = BrandBlue)
                )
            }
        }

        var geofenceAlertsEnabled by remember { mutableStateOf(true) }
        SettingsSectionCard(title = "SAFETY & LOCATION", icon = Icons.Filled.Shield, accentColor = SafeGreen) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Safe Zone Geofencing", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text("Alert on entry & exit", fontSize = 11.sp, color = TextSecondary)
                }
                Switch(
                    checked = geofenceAlertsEnabled,
                    onCheckedChange = {
                        geofenceAlertsEnabled = it
                        Toast.makeText(context, if (it) "Geofence alerts active" else "Geofence alerts muted", Toast.LENGTH_SHORT).show()
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = SafeGreen)
                )
            }
        }

        Button(
            onClick = {
                val finalMinutes = (sliderValue * 60).roundToInt()
                val allowanceSeconds = finalMinutes * 60
                val updatedRules = rules.copy(
                    maxDailyAllowanceMinutes = finalMinutes,
                    maxDailyAllowanceHours = sliderValue
                )
                rules = updatedRules
                ParentalControlManager.syncRulesToCloud(context, updatedRules, activeFamilyId)
                if (activeChildCode.isNotBlank()) {
                    ScreenTimeManager.setDailyAllowanceAsCommand(context, activeChildCode, allowanceSeconds)
                }
                Toast.makeText(context, "Rules & Daily Limit ($displayStr) synced to child device", Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = BrandBlue),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Apply Rules to Child Device", fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RestrictionRed),
            border = BorderStroke(1.dp, RestrictionRed)
        ) {
            Text("Logout Guardian Account")
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, BorderGrey)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(18.dp))
                Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary, letterSpacing = 1.sp)
            }
            HorizontalDivider(color = BorderGrey)
            content()
        }
    }
}