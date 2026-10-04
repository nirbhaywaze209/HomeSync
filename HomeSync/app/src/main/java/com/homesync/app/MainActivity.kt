package com.homesync.app

import android.os.Build
import android.os.Bundle
import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.messaging.FirebaseMessaging
import com.homesync.app.ui.screens.ChildHomeScreen
import com.homesync.app.ui.screens.ChildLoginScreen
import com.homesync.app.ui.screens.FamilySetupScreen
import com.homesync.app.ui.screens.GuardianHomeScreen
import com.homesync.app.ui.screens.LockoutScreen
import com.homesync.app.ui.screens.LoginScreen
import com.homesync.app.util.ActiveSession
import com.homesync.app.util.AuthManager
import com.homesync.app.util.ChildIdManager
import com.homesync.app.util.FamilyManager
import com.homesync.app.util.FamilyRole
import com.homesync.app.util.MemberStatus
import android.util.Log
import com.homesync.app.util.FirebaseSyncManager
import com.homesync.app.util.ScreenTimeManager

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.homesync.app.util.NotificationManager.ensureChannels(applicationContext)
        com.homesync.app.util.HomeSyncMessagingService.ensureEmergencyChannel(applicationContext)

        // Request POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Register/Refresh FCM token immediately on app start
        FamilyManager.ensureFcmTokenRegistered(applicationContext)

        // Check and apply 05:30 AM reset on startup
        val deviceChildId = ChildIdManager.getDeviceChildId(applicationContext)
        ScreenTimeManager.checkAndApplyDailyReset(applicationContext, deviceChildId)

        setContent {
            val context = LocalContext.current

            val currentAuthUser = remember { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser }
            val storedUserId = remember { FamilyManager.getStoredUserId(context) }
            val storedFamilyId = remember { FamilyManager.getStoredFamilyId(context) }
            val storedStatus = remember { FamilyManager.getStoredMemberStatus(context) }
            val storedRole = remember { FamilyManager.getStoredUserRole(context) }
            val isUserMatching = currentAuthUser != null && storedUserId.isNotBlank() && storedUserId == currentAuthUser.uid
            val isFamilyApproved = isUserMatching && storedFamilyId.isNotBlank() && storedStatus == MemberStatus.APPROVED

            val initialScreen = if (currentAuthUser == null) {
                "LOGIN"
            } else if (isFamilyApproved) {
                if (storedRole == FamilyRole.CHILD) "CHILD" else "GUARDIAN"
            } else {
                "FAMILY_SETUP"
            }

            // App state management
            var currentScreen by remember { mutableStateOf(initialScreen) }
            var userName by remember { mutableStateOf(currentAuthUser?.displayName ?: "User") }
            var userEmail by remember { mutableStateOf(currentAuthUser?.email ?: "") }
            var userAge by remember { mutableStateOf(if (storedRole == FamilyRole.CHILD) 10 else 35) }
            var currentChildId by remember { mutableStateOf(ChildIdManager.getDeviceChildId(context)) }
            var linkedChildCode by remember { mutableStateOf("") }

            val performLogout: () -> Unit = {
                Log.i("HomeSyncAuth", "AUTH_LOGOUT")
                AuthManager.clearActiveSession(context)
                FamilyManager.clearFamilySession(context)
                userName = "User"
                userEmail = ""
                userAge = 35
                linkedChildCode = ""
                currentChildId = ""
                currentScreen = "LOGIN"
            }

            // Startup state machine: verify authoritative hs_users/{uid} from Firestore
            LaunchedEffect(currentAuthUser?.uid) {
                val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                if (user == null) {
                    currentScreen = "LOGIN"
                    return@LaunchedEffect
                }

                val uid = user.uid
                val userEmailStr = user.email ?: ""
                Log.i("HomeSyncAuth", "AUTH_FIREBASE_UID uid=$uid")
                Log.i("HomeSyncAuth", "AUTH_FIREBASE_EMAIL email=$userEmailStr")

                val db = FirebaseSyncManager.getDb()
                if (db != null) {
                    db.collection("hs_users").document(uid).get()
                        .addOnSuccessListener { doc ->
                            if (!doc.exists()) {
                                Log.i("HomeSyncAuth", "AUTH_USER_PROFILE_NOT_FOUND uid=$uid")
                                Log.i("HomeSyncAuth", "AUTH_NEW_ACCOUNT uid=$uid")
                                val initialRole = if (userAge in 1..15) FamilyRole.CHILD else FamilyRole.GUARDIAN
                                val userPayload = mapOf(
                                    "uid" to uid,
                                    "userId" to uid,
                                    "firebaseAuthUid" to uid,
                                    "name" to (user.displayName?.ifBlank { "User" } ?: "User"),
                                    "displayName" to (user.displayName?.ifBlank { "User" } ?: "User"),
                                    "email" to userEmailStr,
                                    "role" to initialRole.name,
                                    "familyId" to "",
                                    "status" to (if (initialRole == FamilyRole.GUARDIAN) "APPROVED" else "NONE"),
                                    "membershipStatus" to "NONE",
                                    "createdAt" to System.currentTimeMillis(),
                                    "updatedAt" to System.currentTimeMillis()
                                )
                                db.collection("hs_users").document(uid).set(userPayload, com.google.firebase.firestore.SetOptions.merge())
                                Log.i("HomeSyncAuth", "AUTH_USER_PROFILE_CREATED uid=$uid role=${initialRole.name}")
                                Log.i("HomeSyncAuth", "AUTH_ROLE_RESOLVED role=${initialRole.name}")
                                FamilyManager.saveUserId(context, uid)
                                FamilyManager.saveStoredUserRole(context, initialRole)
                                FamilyManager.saveStoredFamilyId(context, "")
                                FamilyManager.saveStoredMemberStatus(context, MemberStatus.PENDING)
                                currentScreen = "FAMILY_SETUP"
                            } else {
                                Log.i("HomeSyncAuth", "AUTH_USER_PROFILE_FOUND uid=$uid")
                                val rawRole = doc.getString("role") ?: "GUARDIAN"
                                val fId = doc.getString("familyId")?.trim() ?: ""
                                val rawStatus = doc.getString("membershipStatus") ?: doc.getString("status") ?: ""
                                val isApproved = rawStatus.contains("APPROV", ignoreCase = true)
                                val role = if (rawRole.contains("CHILD", ignoreCase = true)) FamilyRole.CHILD else FamilyRole.GUARDIAN
                                Log.i("HomeSyncAuth", "AUTH_ROLE_RESOLVED role=${role.name}")

                                userName = doc.getString("name") ?: user.displayName ?: "User"
                                userEmail = doc.getString("email") ?: user.email ?: ""
                                userAge = if (role == FamilyRole.CHILD) 10 else 35

                                FamilyManager.saveUserId(context, uid)
                                FamilyManager.saveStoredUserRole(context, role)
                                FamilyManager.saveStoredFamilyId(context, fId)
                                if (isApproved) {
                                    FamilyManager.saveStoredMemberStatus(context, MemberStatus.APPROVED)
                                    if (fId.isNotBlank()) {
                                        Log.i("HomeSyncAuth", "AUTH_FAMILY_RESTORED familyId=$fId role=${role.name}")
                                    }
                                } else {
                                    FamilyManager.saveStoredMemberStatus(context, MemberStatus.PENDING)
                                }

                                val nextScreen = when {
                                    role == FamilyRole.GUARDIAN && fId.isNotBlank() && isApproved -> "GUARDIAN"
                                    role == FamilyRole.CHILD && fId.isNotBlank() && isApproved -> {
                                        val cleanDevId = ChildIdManager.getDeviceChildId(context)
                                        if (ScreenTimeManager.isRemoteLocked(context, cleanDevId) || ScreenTimeManager.isDeviceLocked(context, cleanDevId)) "LOCKED" else "CHILD"
                                    }
                                    else -> "FAMILY_SETUP"
                                }
                                currentScreen = nextScreen
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.w("HomeSyncAuth", "Startup hs_users read failed: ${e.message}")
                            currentScreen = "FAMILY_SETUP"
                        }
                }
            }

            val devChildId = if (currentChildId.isNotBlank()) currentChildId else ChildIdManager.getDeviceChildId(context)
            val isChild = (userAge in 1..15) || currentScreen == "CHILD" || currentScreen == "LOCKED" || FamilyManager.getStoredUserRole(context) == FamilyRole.CHILD

            // Enforce authoritative lock state continuously on Child device
            LaunchedEffect(isChild, devChildId) {
                if (isChild && devChildId.isNotBlank()) {
                    while (true) {
                        kotlinx.coroutines.delay(1000L)
                        val currentlyLocked = ScreenTimeManager.isRemoteLocked(context, devChildId) || ScreenTimeManager.isDeviceLocked(context, devChildId)
                        if (currentlyLocked && currentScreen != "LOCKED") {
                            currentScreen = "LOCKED"
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                when (currentScreen) {
                    "LOGIN" -> LoginScreen(
                        onLoginSuccess = { name, email, age, code ->
                            val authUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                            if (authUser == null) {
                                currentScreen = "LOGIN"
                                return@LoginScreen
                            }

                            val uid = authUser.uid
                            val authEmail = authUser.email ?: email
                            Log.i("HomeSyncAuth", "AUTH_FIREBASE_UID uid=$uid")
                            Log.i("HomeSyncAuth", "AUTH_FIREBASE_EMAIL email=$authEmail")

                            val cleanPassedCode = code.trim().uppercase()

                            val db = FirebaseSyncManager.getDb()
                            if (db != null) {
                                db.collection("hs_users").document(uid).get()
                                    .addOnSuccessListener { doc ->
                                        val finalRole: FamilyRole
                                        val finalFamilyId: String
                                        val finalStatus: MemberStatus

                                        if (doc.exists()) {
                                            Log.i("HomeSyncAuth", "AUTH_USER_PROFILE_FOUND uid=$uid")
                                            val rawRole = doc.getString("role") ?: (if (age in 1..15) "CHILD" else "GUARDIAN")
                                            val rawStatus = doc.getString("membershipStatus") ?: doc.getString("status") ?: ""
                                            finalFamilyId = doc.getString("familyId")?.trim() ?: ""
                                            finalRole = if (rawRole.contains("CHILD", ignoreCase = true)) FamilyRole.CHILD else FamilyRole.GUARDIAN
                                            val isApproved = rawStatus.contains("APPROV", ignoreCase = true)
                                            finalStatus = if (isApproved) MemberStatus.APPROVED else MemberStatus.PENDING
                                            Log.i("HomeSyncAuth", "AUTH_ROLE_RESOLVED role=${finalRole.name}")
                                            if (isApproved && finalFamilyId.isNotBlank()) {
                                                Log.i("HomeSyncAuth", "AUTH_FAMILY_RESTORED familyId=$finalFamilyId role=${finalRole.name}")
                                            }

                                            val docName = doc.getString("name") ?: doc.getString("displayName")
                                            userName = if (!docName.isNullOrBlank()) docName else (if (name.isNotBlank() && name != "User") name else (authUser.displayName ?: "User"))
                                            val docEmail = doc.getString("email")
                                            userEmail = if (!docEmail.isNullOrBlank()) docEmail else authEmail
                                            userAge = if (finalRole == FamilyRole.CHILD) 10 else 35

                                            val savedChildCode = doc.getString("childCode") ?: doc.getString("childId") ?: ""
                                            if (finalRole == FamilyRole.CHILD) {
                                                currentChildId = if (savedChildCode.isNotBlank()) savedChildCode else (if (cleanPassedCode.isNotBlank()) cleanPassedCode else ChildIdManager.getDeviceChildId(context))
                                                linkedChildCode = currentChildId
                                            } else {
                                                linkedChildCode = if (savedChildCode.isNotBlank()) savedChildCode else cleanPassedCode
                                                AuthManager.saveGuardianName(context, userName)
                                            }
                                        } else {
                                            Log.i("HomeSyncAuth", "AUTH_USER_PROFILE_NOT_FOUND uid=$uid")
                                            Log.i("HomeSyncAuth", "AUTH_NEW_ACCOUNT uid=$uid")
                                            finalRole = if (age in 1..15) FamilyRole.CHILD else FamilyRole.GUARDIAN
                                            finalFamilyId = ""
                                            finalStatus = if (finalRole == FamilyRole.GUARDIAN) MemberStatus.APPROVED else MemberStatus.PENDING
                                            userName = if (name.isNotBlank() && name != "User") name else (authUser.displayName ?: "User")
                                            userEmail = authEmail
                                            userAge = if (finalRole == FamilyRole.CHILD) 10 else 35

                                            if (finalRole == FamilyRole.CHILD) {
                                                currentChildId = if (cleanPassedCode.isNotBlank()) cleanPassedCode else ChildIdManager.getDeviceChildId(context)
                                                linkedChildCode = currentChildId
                                            } else {
                                                linkedChildCode = cleanPassedCode
                                                AuthManager.saveGuardianName(context, userName)
                                            }

                                            val userPayload = mapOf(
                                                "uid" to uid,
                                                "userId" to uid,
                                                "firebaseAuthUid" to uid,
                                                "name" to userName,
                                                "displayName" to userName,
                                                "email" to userEmail,
                                                "role" to finalRole.name,
                                                "familyId" to "",
                                                "status" to (if (finalRole == FamilyRole.GUARDIAN) "APPROVED" else "NONE"),
                                                "membershipStatus" to "NONE",
                                                "createdAt" to System.currentTimeMillis(),
                                                "updatedAt" to System.currentTimeMillis()
                                            )
                                            db.collection("hs_users").document(uid).set(userPayload, com.google.firebase.firestore.SetOptions.merge())
                                            Log.i("HomeSyncAuth", "AUTH_USER_PROFILE_CREATED uid=$uid role=${finalRole.name}")
                                            Log.i("HomeSyncAuth", "AUTH_ROLE_RESOLVED role=${finalRole.name}")
                                        }

                                        FamilyManager.saveUserId(context, uid)
                                        FamilyManager.saveStoredFamilyId(context, finalFamilyId)
                                        FamilyManager.saveStoredUserRole(context, finalRole)
                                        FamilyManager.saveStoredMemberStatus(context, finalStatus)

                                        val targetScreen = when {
                                            finalRole == FamilyRole.GUARDIAN && finalFamilyId.isNotBlank() && finalStatus == MemberStatus.APPROVED -> "GUARDIAN"
                                            finalRole == FamilyRole.CHILD && finalFamilyId.isNotBlank() && finalStatus == MemberStatus.APPROVED -> {
                                                val cleanDevId = ChildIdManager.getDeviceChildId(context)
                                                if (ScreenTimeManager.isRemoteLocked(context, cleanDevId) || ScreenTimeManager.isDeviceLocked(context, cleanDevId)) "LOCKED" else "CHILD"
                                            }
                                            else -> "FAMILY_SETUP"
                                        }

                                        currentScreen = targetScreen
                                        AuthManager.saveActiveSession(
                                            context = context,
                                            session = ActiveSession(
                                                name = userName,
                                                email = userEmail,
                                                age = userAge,
                                                screen = targetScreen,
                                                childId = currentChildId,
                                                linkedChildCode = linkedChildCode,
                                                familyId = finalFamilyId,
                                                userId = uid
                                            )
                                        )
                                    }
                                    .addOnFailureListener { e ->
                                        Log.w("HomeSyncAuth", "Firestore hs_users read failed: ${e.message}")
                                        currentScreen = "FAMILY_SETUP"
                                    }
                            } else {
                                currentScreen = "FAMILY_SETUP"
                            }
                        }
                    )
                    "FAMILY_SETUP" -> {
                        val currentUserId = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                            ?: FamilyManager.getOrCreateUserId(context, userEmail)
                        val defaultRole = FamilyManager.getStoredUserRole(context)
                        FamilySetupScreen(
                            userName = userName,
                            userEmail = userEmail,
                            userId = currentUserId,
                            initialRolePreference = defaultRole,
                            onFamilyReady = { familyId, role ->
                                val targetScreen = if (role == FamilyRole.CHILD) "CHILD" else "GUARDIAN"
                                currentScreen = targetScreen
                                AuthManager.saveActiveSession(
                                    context = context,
                                    session = ActiveSession(
                                        name = userName,
                                        email = userEmail,
                                        age = if (role == FamilyRole.CHILD) 10 else 35,
                                        screen = targetScreen,
                                        childId = currentChildId,
                                        linkedChildCode = linkedChildCode,
                                        familyId = familyId,
                                        userId = currentUserId
                                    )
                                )
                            },
                            onLogout = performLogout
                        )
                    }
                    "CHILD" -> {
                        val activeChildId = if (currentChildId.isNotBlank()) currentChildId else ChildIdManager.getDeviceChildId(context)
                        ScreenTimeManager.checkAndApplyDailyReset(context, activeChildId)
                        ChildHomeScreen(
                            childName = userName,
                            pairingCode = activeChildId,
                            onTriggerSOS = { /* SOS sent via notification & RTDB, keep child on screen */ },
                            onLockout = { currentScreen = "LOCKED" },
                            onLogout = performLogout
                        )
                    }
                    "CHILD_LOGIN" -> {
                        val activeChildId = if (currentChildId.isNotBlank()) currentChildId else ChildIdManager.getDeviceChildId(context)
                        ChildLoginScreen(
                            pairingCode = activeChildId,
                            onScanQrCode = { /* QR scanning placeholder */ },
                            onNeedHelp = { /* Help flow placeholder */ }
                        )
                    }
                    "GUARDIAN" -> GuardianHomeScreen(
                        guardianName = userName,
                        linkedChildCode = if (linkedChildCode.isNotBlank()) linkedChildCode else ChildIdManager.getDeviceChildId(context),
                        onLogout = performLogout
                    )
                    "LOCKED" -> {
                        val targetChildId = if (currentChildId.isNotBlank()) currentChildId else if (linkedChildCode.isNotBlank()) linkedChildCode else ChildIdManager.getDeviceChildId(context)
                        ScreenTimeManager.checkAndApplyDailyReset(context, targetChildId)
                        LockoutScreen(
                            childId = targetChildId,
                            onUnlock = {
                                currentScreen = if (isChild) "CHILD" else "LOGIN"
                            }
                        )
                    }
                }
            }
        }
    }
}