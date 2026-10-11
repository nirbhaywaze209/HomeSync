package com.homesync.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import com.homesync.app.ui.theme.ParentElevatedSlate
import com.homesync.app.ui.theme.ParentPaleMint
import com.homesync.app.ui.theme.ParentSage
import com.homesync.app.ui.theme.ParentTeal
import com.homesync.app.util.FamilyManager
import com.homesync.app.util.FamilyRole
import com.homesync.app.util.MemberStatus
import com.homesync.app.util.MembershipResult
import com.homesync.app.util.SyncState

enum class FamilyHubState {
    CHECKING,          // Bounded initial lookup (< 3.5s)
    NO_FAMILY,         // Create / Join tabs
    PENDING_APPROVAL,  // Waiting for Guardian approval
    FAMILY_CREATED,    // Guardian just created family
    FAMILY_APPROVED,   // User is approved member
    OFFLINE_RETRY      // Connection offline or error
}

private data class SyncBadgeConfig(
    val bg: Color,
    val border: Color,
    val tint: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val text: String
)

private fun formatFirestoreError(err: Throwable, defaultMsg: String): String {
    val rawMsg = err.message ?: ""
    if (rawMsg == "FAMILY_NOT_FOUND") {
        return "Family not found. Please verify the Family ID with your Guardian."
    }
    if (rawMsg.contains("ALREADY_MEMBER", ignoreCase = true)) {
        return "You are already an approved member of this family."
    }
    if (rawMsg == "ALREADY_PENDING") {
        return "You already have a pending join request for this family."
    }
    if (err is FirebaseFirestoreException) {
        return when (err.code) {
            FirebaseFirestoreException.Code.UNAVAILABLE ->
                "Network connection unavailable. Changes will sync automatically when back online."
            FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                "Access denied. Please check your account permissions or sign in again."
            FirebaseFirestoreException.Code.NOT_FOUND ->
                "The requested family record was not found."
            FirebaseFirestoreException.Code.ALREADY_EXISTS ->
                "This record already exists."
            FirebaseFirestoreException.Code.UNAUTHENTICATED ->
                "Session expired. Please log out and sign in again."
            else -> err.localizedMessage ?: defaultMsg
        }
    }
    val msg = err.localizedMessage ?: defaultMsg
    if (msg.contains("client is offline", ignoreCase = true)) {
        return "Network connection unavailable. Offline changes will sync automatically once connected."
    }
    return msg
}

@Composable
fun FamilySetupScreen(
    userName: String,
    userEmail: String,
    userId: String,
    initialRolePreference: FamilyRole = FamilyRole.GUARDIAN,
    onFamilyReady: (familyId: String, role: FamilyRole) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current

    var hubState by remember { mutableStateOf(FamilyHubState.CHECKING) }
    var selectedTab by remember { mutableIntStateOf(if (initialRolePreference == FamilyRole.CHILD) 1 else 0) }

    // Form inputs
    var inputFamilyId by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf(initialRolePreference) }

    // Dynamic State Values
    var createdFamilyId by remember { mutableStateOf("") }
    var pendingTargetFamilyId by remember { mutableStateOf("") }
    var activeFamilyId by remember { mutableStateOf("") }
    var activeRole by remember { mutableStateOf(initialRolePreference) }
    var currentSyncState by remember { mutableStateOf(SyncState.REMOTE_CONFIRMED) }

    var isLoading by remember { mutableStateOf(false) }
    var isCheckingStatus by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    var retryTrigger by remember { mutableIntStateOf(0) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var lastOperation by remember { mutableStateOf("") }
    var lastErrorCode by remember { mutableStateOf("") }
    var lastErrorDetails by remember { mutableStateOf("") }

    // State Machine Initialization with Guaranteed 3.5s Timeout
    LaunchedEffect(userId, retryTrigger) {
        hubState = FamilyHubState.CHECKING
        errorMessage = ""
        FamilyManager.checkUserFamilyMembership(context, userId) { result ->
            when (result) {
                is MembershipResult.Approved -> {
                    activeFamilyId = result.familyId
                    activeRole = result.role
                    currentSyncState = result.syncState
                    hubState = FamilyHubState.FAMILY_APPROVED
                    onFamilyReady(result.familyId, result.role)
                }
                is MembershipResult.Pending -> {
                    pendingTargetFamilyId = result.familyId
                    selectedRole = result.role
                    currentSyncState = result.syncState
                    hubState = FamilyHubState.PENDING_APPROVAL
                }
                is MembershipResult.NoFamily -> {
                    hubState = FamilyHubState.NO_FAMILY
                }
                is MembershipResult.OfflineOrError -> {
                    if (result.cachedFallback != null) {
                        when (result.cachedFallback) {
                            is MembershipResult.Approved -> {
                                activeFamilyId = result.cachedFallback.familyId
                                activeRole = result.cachedFallback.role
                                currentSyncState = SyncState.OFFLINE
                                hubState = FamilyHubState.FAMILY_APPROVED
                                onFamilyReady(result.cachedFallback.familyId, result.cachedFallback.role)
                            }
                            is MembershipResult.Pending -> {
                                pendingTargetFamilyId = result.cachedFallback.familyId
                                selectedRole = result.cachedFallback.role
                                currentSyncState = SyncState.OFFLINE
                                hubState = FamilyHubState.PENDING_APPROVAL
                            }
                            else -> hubState = FamilyHubState.NO_FAMILY
                        }
                    } else {
                        val isOnline = com.homesync.app.util.NetworkHelper.isOnline(context)
                        if (isOnline) {
                            // Device has network connection; unjoined state should display Create/Join tabs
                            hubState = FamilyHubState.NO_FAMILY
                        } else {
                            errorMessage = result.message
                            hubState = FamilyHubState.OFFLINE_RETRY
                        }
                    }
                }
            }
        }
    }

    // Active real-time listener for join request status while waiting
    DisposableEffect(hubState, pendingTargetFamilyId) {
        var listener: com.google.firebase.firestore.ListenerRegistration? = null
        if (hubState == FamilyHubState.PENDING_APPROVAL && pendingTargetFamilyId.isNotBlank()) {
            listener = FamilyManager.listenJoinRequestStatus(
                context = context,
                familyId = pendingTargetFamilyId,
                userId = userId,
                onStatusChanged = { status, syncState ->
                    currentSyncState = syncState
                    if (status == MemberStatus.APPROVED) {
                        Toast.makeText(context, "🎉 Family Join Request Approved!", Toast.LENGTH_LONG).show()
                        onFamilyReady(pendingTargetFamilyId, selectedRole)
                    } else if (status == MemberStatus.REJECTED) {
                        hubState = FamilyHubState.NO_FAMILY
                        errorMessage = "Your request to join Family $pendingTargetFamilyId was rejected by the Guardian."
                        FamilyManager.clearPendingFamilyId(context)
                    }
                },
                onError = { err ->
                    statusMessage = err
                }
            )
        }
        onDispose {
            listener?.remove()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F172A), Color(0xFF1E293B), Color(0xFF0F172A))
                )
            )
            .padding(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Common Header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .background(Color(0xFF0284C7), shape = RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "👨‍👩‍👧‍👦", fontSize = 24.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "HomeSync Family Hub",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = "Welcome, $userName",
                            fontSize = 12.sp,
                            color = ParentPaleMint.copy(alpha = 0.8f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // ===================================================
                // STATE 1: CHECKING STATUS (< 3.5s bounded check)
                // ===================================================
                if (hubState == FamilyHubState.CHECKING) {
                    Surface(
                        color = Color(0x1538BDF8),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFF38BDF8),
                                modifier = Modifier.size(36.dp),
                                strokeWidth = 3.5.dp
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Checking family status...",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Connecting with Firebase cloud services",
                                color = ParentPaleMint.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedButton(
                                onClick = { hubState = FamilyHubState.NO_FAMILY },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8))
                            ) {
                                Text("Skip Check / Set Up Family", fontSize = 11.sp)
                            }
                        }
                    }
                }

                // ===================================================
                // STATE 2: PENDING APPROVAL VIEW (Cross-device Waiting)
                // ===================================================
                else if (hubState == FamilyHubState.PENDING_APPROVAL) {
                    Surface(
                        color = Color(0x22F59E0B),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color(0xFFF59E0B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.HourglassTop,
                                contentDescription = "Waiting",
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(42.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Request Pending Guardian Approval",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color(0xFFFBBF24),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Your request to join Family $pendingTargetFamilyId as ${selectedRole.name} has been submitted.",
                                color = Color(0xFFE2E8F0),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            // Sync State Badge
                            val badge = when (currentSyncState) {
                                SyncState.REMOTE_CONFIRMED -> SyncBadgeConfig(
                                    Color(0x3310B981), Color(0xFF10B981), Color(0xFF34D399),
                                    Icons.Default.CloudDone, "Cloud Confirmed (Pending Guardian Action)"
                                )
                                SyncState.LOCAL_PENDING_SYNC -> SyncBadgeConfig(
                                    Color(0x33F59E0B), Color(0xFFF59E0B), Color(0xFFFBBF24),
                                    Icons.Default.HourglassTop, "Saved offline / waiting to sync"
                                )
                                SyncState.OFFLINE -> SyncBadgeConfig(
                                    Color(0x33EF4444), Color(0xFFEF4444), Color(0xFFF87171),
                                    Icons.Default.CloudOff, "Offline mode / waiting for connection"
                                )
                                SyncState.FAILED -> SyncBadgeConfig(
                                    Color(0x33EF4444), Color(0xFFEF4444), Color(0xFFF87171),
                                    Icons.Default.CloudOff, "Sync failed / please retry"
                                )
                            }
                            Surface(
                                color = badge.bg,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, badge.border)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = badge.icon,
                                        contentDescription = null,
                                        tint = badge.tint,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = badge.text,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = badge.tint
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "An existing Guardian will receive your request and approve it on their phone. Once approved, this screen will update automatically.",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            CircularProgressIndicator(
                                color = Color(0xFFF59E0B),
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        isCheckingStatus = true
                                        FamilyManager.checkJoinRequestStatusNow(context, pendingTargetFamilyId, userId) { status, syncState ->
                                            isCheckingStatus = false
                                            currentSyncState = syncState
                                            if (status == MemberStatus.APPROVED) {
                                                Toast.makeText(context, "🎉 Request Approved!", Toast.LENGTH_SHORT).show()
                                                onFamilyReady(pendingTargetFamilyId, selectedRole)
                                            } else if (status == MemberStatus.REJECTED) {
                                                hubState = FamilyHubState.NO_FAMILY
                                                errorMessage = "Your request was rejected by the Guardian."
                                                FamilyManager.clearPendingFamilyId(context)
                                            } else {
                                                Toast.makeText(context, "Still pending Guardian approval ⏳", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    enabled = !isCheckingStatus,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8))
                                ) {
                                    if (isCheckingStatus) {
                                        CircularProgressIndicator(color = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Check Status", fontSize = 11.sp)
                                        }
                                    }
                                }

                                OutlinedButton(
                                    onClick = {
                                        hubState = FamilyHubState.NO_FAMILY
                                        FamilyManager.clearPendingFamilyId(context)
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF87171))
                                ) {
                                    Text("Cancel Request", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                // ===================================================
                // STATE 3: SUCCESS CREATION VIEW (Family ID Display)
                // ===================================================
                else if (hubState == FamilyHubState.FAMILY_CREATED) {
                    Surface(
                        color = Color(0xFF064E3B),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.5.dp, Color(0xFF10B981)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "🎉 FAMILY CREATED SUCCESSFULLY!",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF34D399)
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            // Sync State Badge
                            val createdBadge = when (currentSyncState) {
                                SyncState.REMOTE_CONFIRMED -> SyncBadgeConfig(
                                    Color(0x3310B981), Color(0xFF10B981), Color(0xFF34D399),
                                    Icons.Default.CloudDone, "Confirmed in Cloud Firestore"
                                )
                                SyncState.LOCAL_PENDING_SYNC -> SyncBadgeConfig(
                                    Color(0x33F59E0B), Color(0xFFF59E0B), Color(0xFFFBBF24),
                                    Icons.Default.HourglassTop, "Saved offline / will sync when online"
                                )
                                SyncState.OFFLINE -> SyncBadgeConfig(
                                    Color(0x33EF4444), Color(0xFFEF4444), Color(0xFFF87171),
                                    Icons.Default.CloudOff, "Created offline / pending cloud sync"
                                )
                                SyncState.FAILED -> SyncBadgeConfig(
                                    Color(0x33EF4444), Color(0xFFEF4444), Color(0xFFF87171),
                                    Icons.Default.CloudOff, "Sync failed / stored locally"
                                )
                            }
                            Surface(
                                color = createdBadge.bg,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, createdBadge.border)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = createdBadge.icon,
                                        contentDescription = null,
                                        tint = createdBadge.tint,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = createdBadge.text,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = createdBadge.tint
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "YOUR FAMILY ID",
                                fontSize = 12.sp,
                                color = ParentPaleMint.copy(alpha = 0.9f),
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            // Large Family ID Display
                            Surface(
                                color = Color(0xFF0F172A),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Color(0xFF10B981)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("HomeSync Family ID", createdFamilyId))
                                        Toast.makeText(context, "Copied Family ID: $createdFamilyId 📋", Toast.LENGTH_SHORT).show()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = createdFamilyId,
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF4ADE80),
                                        letterSpacing = 3.sp
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy",
                                        tint = Color(0xFF4ADE80),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "\"Share this Family ID with your family members.\"",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "All Guardians and Children in your household will use this single ID to pair their devices.",
                                fontSize = 11.sp,
                                color = ParentPaleMint.copy(alpha = 0.75f),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(18.dp))

                            Button(
                                onClick = {
                                    onFamilyReady(createdFamilyId, FamilyRole.GUARDIAN)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                Text(
                                    text = "Continue to Guardian Dashboard 🛡️",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                // ===================================================
                // STATE 4: FAMILY APPROVED VIEW
                // ===================================================
                else if (hubState == FamilyHubState.FAMILY_APPROVED) {
                    Surface(
                        color = Color(0xFF0F291E),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color(0xFF10B981)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Connected to Family", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            Text(activeFamilyId, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Role: ${activeRole.name}", color = ParentPaleMint, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { onFamilyReady(activeFamilyId, activeRole) },
                                colors = ButtonDefaults.buttonColors(containerColor = ParentTeal),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Text("Continue to Dashboard", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // ===================================================
                // STATE 5: OFFLINE / RETRY VIEW
                // ===================================================
                else if (hubState == FamilyHubState.OFFLINE_RETRY) {
                    Surface(
                        color = Color(0x22EF4444),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.CloudOff, contentDescription = null, tint = Color(0xFFF87171), modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Connection Offline", color = Color(0xFFF87171), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = errorMessage.ifBlank { "Could not reach Firebase servers. You can retry or proceed offline." },
                                color = Color(0xFFCBD5E1),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { retryTrigger++ },
                                    colors = ButtonDefaults.buttonColors(containerColor = ParentTeal),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Retry 🔄", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { hubState = FamilyHubState.NO_FAMILY },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCBD5E1))
                                ) {
                                    Text("Continue Offline", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                // ===================================================
                // STATE 6: TABS: CREATE FAMILY vs JOIN FAMILY
                // ===================================================
                else {
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = ParentElevatedSlate,
                        contentColor = ParentTeal,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = {
                                selectedTab = 0
                                errorMessage = ""
                            },
                            text = {
                                Text(
                                    text = "✨ Create Family",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (selectedTab == 0) ParentPaleMint else ParentPaleMint.copy(alpha = 0.6f)
                                )
                            }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = {
                                selectedTab = 1
                                errorMessage = ""
                            },
                            text = {
                                Text(
                                    text = "🔗 Join Family",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = if (selectedTab == 1) Color(0xFF4ADE80) else Color(0xFF94A3B8)
                                )
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // ===================================================
                    // 1. CREATE FAMILY FLOW
                    // ===================================================
                    if (selectedTab == 0) {
                        Text(
                            text = "CREATE FAMILY",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Create a unique family group. You will become the primary Guardian / Admin and can invite other Guardians and Children to connect.",
                            fontSize = 12.sp,
                            color = ParentPaleMint.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        AnimatedVisibility(visible = errorMessage.isNotBlank()) {
                            Text(
                                text = "⚠️ $errorMessage",
                                color = Color(0xFFF87171),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        Button(
                            onClick = {
                                isLoading = true
                                errorMessage = ""
                                FamilyManager.createFamily(
                                    context = context,
                                    creatorUserId = userId,
                                    creatorName = userName,
                                    creatorEmail = userEmail
                                ) { result ->
                                    isLoading = false
                                    result.onSuccess { creation ->
                                        createdFamilyId = creation.family.familyId
                                        currentSyncState = creation.syncState
                                        hubState = FamilyHubState.FAMILY_CREATED
                                    }.onFailure { err ->
                                        val code = (err as? com.google.firebase.firestore.FirebaseFirestoreException)?.code?.name ?: "UNKNOWN"
                                        val msg = err.message ?: err.localizedMessage ?: "Unknown failure"
                                        lastOperation = "CREATE_FAMILY"
                                        lastErrorCode = code
                                        lastErrorDetails = "Path: hs_families/* | UID: ${com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "NULL"} | Error: $code: $msg"
                                        errorMessage = "${formatFirestoreError(err, "Failed to create family.")}\n[$code: $msg]"
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ParentTeal),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.GroupAdd, contentDescription = null, tint = Color.White)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Create Family",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }

                    // ===================================================
                    // 2. JOIN FAMILY FLOW
                    // ===================================================
                    if (selectedTab == 1) {
                        Text(
                            text = "JOIN FAMILY",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Enter your Family Pairing ID provided by your family's Guardian to join the family group.",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        // Field: Enter Family ID
                        OutlinedTextField(
                            value = inputFamilyId,
                            onValueChange = { inputFamilyId = it.uppercase().trim() },
                            label = { Text("Enter Family ID (e.g. HS-4821)", color = ParentPaleMint) },
                            leadingIcon = { Text("🆔") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF4ADE80),
                                unfocusedBorderColor = ParentSage,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // "What are you joining as?"
                        Text(
                            text = "What are you joining as?",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // [ Guardian ]
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedRole = FamilyRole.GUARDIAN },
                                shape = RoundedCornerShape(10.dp),
                                color = if (selectedRole == FamilyRole.GUARDIAN) ParentTeal else ParentElevatedSlate,
                                border = if (selectedRole == FamilyRole.GUARDIAN) BorderStroke(1.5.dp, Color(0xFF38BDF8)) else null
                            ) {
                                Text(
                                    text = "🛡️ Guardian",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            }

                            // [ Child ]
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedRole = FamilyRole.CHILD },
                                shape = RoundedCornerShape(10.dp),
                                color = if (selectedRole == FamilyRole.CHILD) Color(0xFFF97316) else ParentElevatedSlate,
                                border = if (selectedRole == FamilyRole.CHILD) BorderStroke(1.5.dp, Color(0xFFFDBA74)) else null
                            ) {
                                Text(
                                    text = "👦 Child",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            }
                        }

                        AnimatedVisibility(visible = errorMessage.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "⚠️ $errorMessage",
                                color = Color(0xFFF87171),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // [ Send Join Request ]
                        Button(
                            onClick = {
                                if (inputFamilyId.isBlank()) {
                                    errorMessage = "Please enter a valid Family ID (e.g. HS-4821)."
                                    return@Button
                                }
                                isLoading = true
                                errorMessage = ""

                                FamilyManager.submitJoinRequest(
                                    context = context,
                                    familyId = inputFamilyId,
                                    userId = userId,
                                    name = userName,
                                    email = userEmail,
                                    role = selectedRole
                                ) { result ->
                                    isLoading = false
                                    result.onSuccess { joinRes ->
                                        if (joinRes.request.status == MemberStatus.APPROVED) {
                                            activeFamilyId = joinRes.request.familyId
                                            activeRole = joinRes.request.role
                                            currentSyncState = joinRes.syncState
                                            hubState = FamilyHubState.FAMILY_APPROVED
                                            Toast.makeText(context, "Welcome back to Family ${joinRes.request.familyId}! 🎉", Toast.LENGTH_SHORT).show()
                                            onFamilyReady(joinRes.request.familyId, joinRes.request.role)
                                        } else {
                                            pendingTargetFamilyId = joinRes.request.familyId
                                            currentSyncState = joinRes.syncState
                                            hubState = FamilyHubState.PENDING_APPROVAL
                                            Toast.makeText(context, "Join Request Sent to Family ${joinRes.request.familyId}! ⏳", Toast.LENGTH_SHORT).show()
                                        }
                                    }.onFailure { err ->
                                        val msg = err.message ?: err.localizedMessage ?: "Unknown failure"
                                        if (msg.contains("ALREADY_MEMBER", ignoreCase = true)) {
                                            val cleanFid = inputFamilyId.trim().uppercase()
                                            activeFamilyId = cleanFid
                                            activeRole = selectedRole
                                            FamilyManager.saveStoredFamilyId(context, cleanFid)
                                            FamilyManager.saveStoredMemberStatus(context, MemberStatus.APPROVED)
                                            FamilyManager.saveStoredUserRole(context, selectedRole)
                                            hubState = FamilyHubState.FAMILY_APPROVED
                                            Toast.makeText(context, "You are already an approved member of Family $cleanFid! 🎉", Toast.LENGTH_SHORT).show()
                                            onFamilyReady(cleanFid, selectedRole)
                                            return@onFailure
                                        }
                                        val code = (err as? com.google.firebase.firestore.FirebaseFirestoreException)?.code?.name ?: "UNKNOWN"
                                        lastOperation = "SUBMIT_JOIN_REQUEST"
                                        lastErrorCode = code
                                        lastErrorDetails = "Path: hs_families/$inputFamilyId/join_requests | UID: ${com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: "NULL"} | Error: $code: $msg"
                                        errorMessage = "${formatFirestoreError(err, "Failed to submit join request.")}\n[$code: $msg]"
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.MeetingRoom, contentDescription = null, tint = Color.White)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Send Join Request",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Real-time Connection Diagnostics Card
                Surface(
                    color = Color(0xFF0F172A),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF334155)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDiagnostics = !showDiagnostics }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🛠️ Connection Diagnostics",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF94A3B8)
                            )
                            Text(
                                text = if (showDiagnostics) "Hide ▲" else "Show ▼",
                                fontSize = 11.sp,
                                color = Color(0xFF38BDF8)
                            )
                        }

                        if (showDiagnostics) {
                            Spacer(modifier = Modifier.height(10.dp))
                            val fbAuth = FirebaseAuth.getInstance().currentUser
                            val diagAuthStatus = if (fbAuth != null) "AUTHENTICATED (${fbAuth.email ?: "Google/UID"})" else "NOT_AUTHENTICATED"
                            val diagUid = fbAuth?.uid ?: "None"
                            val diagEffectiveFamilyId = activeFamilyId.ifBlank { pendingTargetFamilyId.ifBlank { inputFamilyId.ifBlank { "None" } } }
                            val diagJoinStatus = when (hubState) {
                                FamilyHubState.PENDING_APPROVAL -> "PENDING"
                                FamilyHubState.FAMILY_APPROVED -> "APPROVED"
                                FamilyHubState.FAMILY_CREATED -> "CREATED (OWNER)"
                                else -> "NOT_REQUESTED"
                            }
                            val diagLastError = errorMessage.ifBlank { statusMessage.ifBlank { "None" } }

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                DiagnosticRow("Firebase Auth", diagAuthStatus)
                                DiagnosticRow("Firebase UID", diagUid)
                                DiagnosticRow("HomeSync User ID", userId)
                                DiagnosticRow("Selected Role", selectedRole.name)
                                DiagnosticRow("Family ID", diagEffectiveFamilyId)
                                DiagnosticRow("Hub State", hubState.name)
                                DiagnosticRow("Join Request", diagJoinStatus)
                                DiagnosticRow("Sync State", currentSyncState.name)
                                DiagnosticRow("Last Operation", lastOperation.ifBlank { "NONE" })
                                DiagnosticRow("Firebase Error Code", lastErrorCode.ifBlank { "NONE" })
                                DiagnosticRow("Diagnostic Details", lastErrorDetails.ifBlank { diagLastError })
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Log out option
                TextButton(
                    onClick = onLogout
                ) {
                    Text(
                        text = "Sign out of this account",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$label:",
            fontSize = 10.sp,
            color = Color(0xFF64748B),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.42f)
        )
        Text(
            text = value,
            fontSize = 10.sp,
            color = Color(0xFFE2E8F0),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(0.58f),
            textAlign = TextAlign.End
        )
    }
}

