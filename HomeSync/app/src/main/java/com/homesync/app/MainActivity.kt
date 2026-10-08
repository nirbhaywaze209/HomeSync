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

        // Safe cleanup of oversized RTDB persistence database cache (> 5MB)
        try {
            val rtdbDb = applicationContext.getDatabasePath("homesync-app-4cee2-default-rtdb.firebaseio.com_default")
            if (rtdbDb != null && rtdbDb.exists() && rtdbDb.length() > 5 * 1024 * 1024) {
                applicationContext.deleteDatabase("homesync-app-4cee2-default-rtdb.firebaseio.com_default")
            }
        } catch (_: Exception) {}

        // Check and apply 05:30 AM reset on startup
        val deviceChildId = ChildIdManager.getDeviceChildId(applicationContext)
        ScreenTimeManager.checkAndApplyDailyReset(applicationContext, deviceChildId)

        // Immediately start foreground service on startup to guarantee instant background SOS delivery
        try {
            val session = AuthManager.getActiveSession(applicationContext)
            val storedRole = FamilyManager.getStoredUserRole(applicationContext)
            if (session?.screen == "GUARDIAN" || storedRole == FamilyRole.GUARDIAN) {
                com.homesync.app.service.HomeSyncForegroundService.startForGuardian(applicationContext)
            } else if (session?.screen == "CHILD" || storedRole == FamilyRole.CHILD) {
                com.homesync.app.service.HomeSyncForegroundService.startForChild(applicationContext)
            }
        } catch (_: Exception) {}

        setContent {
            val context = LocalContext.current

            val localSession = remember { AuthManager.getActiveSession(context) }
            val authInstance = remember { com.google.firebase.auth.FirebaseAuth.getInstance() }
            var authUser by remember { mutableStateOf(authInstance.currentUser) }
            val storedUserId = remember { FamilyManager.getStoredUserId(context).ifBlank { localSession?.userId ?: "" } }
            val storedFamilyId = remember { FamilyManager.getStoredFamilyId(context).ifBlank { localSession?.familyId ?: "" } }
            val storedStatus = remember { FamilyManager.getStoredMemberStatus(context) }
            val storedRole = remember {
                if (localSession != null && localSession.screen == "CHILD") FamilyRole.CHILD
                else if (localSession != null && localSession.screen == "GUARDIAN") FamilyRole.GUARDIAN
                else FamilyManager.getStoredUserRole(context)
            }

            val hasValidLocalSession = localSession != null && localSession.screen.isNotBlank() && localSession.screen != "LOGIN"
            val isFamilyApproved = storedFamilyId.isNotBlank() && (storedStatus == MemberStatus.APPROVED || hasValidLocalSession)

            val initialScreen = when {
                hasValidLocalSession -> {
                    if (localSession!!.screen == "CHILD") {
                        val devId = localSession.childId.ifBlank { ChildIdManager.getDeviceChildId(context) }
                        if (ScreenTimeManager.isRemoteLocked(context, devId) || ScreenTimeManager.isDeviceLocked(context, devId)) "LOCKED" else "CHILD"
                    } else {
                        localSession.screen
                    }
                }
                authUser != null && isFamilyApproved -> {
                    if (storedRole == FamilyRole.CHILD) {
                        val devId = ChildIdManager.getDeviceChildId(context)
                        if (ScreenTimeManager.isRemoteLocked(context, devId) || ScreenTimeManager.isDeviceLocked(context, devId)) "LOCKED" else "CHILD"
                    } else {
                        "GUARDIAN"
                    }
                }
                authUser != null -> "FAMILY_SETUP"
                else -> "LOGIN"
            }

            // App state management
            var currentScreen by remember { mutableStateOf(initialScreen) }
            var userName by remember {
                mutableStateOf(
                    localSession?.name?.takeIf { it.isNotBlank() && it != "User" }
                        ?: authUser?.displayName?.takeIf { !it.isNullOrBlank() }
                        ?: "User"
                )
            }
            var userEmail by remember {
                mutableStateOf(
                    localSession?.email?.takeIf { it.isNotBlank() }
                        ?: authUser?.email?.takeIf { !it.isNullOrBlank() }
                        ?: ""
                )
            }
            var userAge by remember {
                mutableStateOf(
                    if (localSession != null && localSession.age > 0) localSession.age
                    else (if (storedRole == FamilyRole.CHILD || localSession?.screen == "CHILD") 10 else 35)
                )
            }
            var currentChildId by remember {
                mutableStateOf(
                    localSession?.childId?.takeIf { it.isNotBlank() }
                        ?: ChildIdManager.getDeviceChildId(context)
                )
            }
            var linkedChildCode by remember {
                mutableStateOf(
                    localSession?.linkedChildCode?.takeIf { it.isNotBlank() }
                        ?: ""
                )
            }

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

            // Continuous Auth state listener: keeps session active and updates user on reconnect
            DisposableEffect(Unit) {
                val listener = com.google.firebase.auth.FirebaseAuth.AuthStateListener { fbAuth ->
                    val user = fbAuth.currentUser
                    authUser = user
                    if (user != null) {
                        if (currentScreen == "LOGIN") {
                            val session = AuthManager.getActiveSession(context)
                            val target = when {
                                session != null && session.screen.isNotBlank() && session.screen != "LOGIN" -> {
                                    if (session.screen == "CHILD") {
                                        val cleanDevId = session.childId.ifBlank { ChildIdManager.getDeviceChildId(context) }
                                        if (ScreenTimeManager.isRemoteLocked(context, cleanDevId) || ScreenTimeManager.isDeviceLocked(context, cleanDevId)) "LOCKED" else "CHILD"
                                    } else session.screen
                                }
                                FamilyManager.getStoredFamilyId(context).isNotBlank() && FamilyManager.getStoredMemberStatus(context) == MemberStatus.APPROVED -> {
                                    if (FamilyManager.getStoredUserRole(context) == FamilyRole.CHILD) "CHILD" else "GUARDIAN"
                                }
                                else -> "FAMILY_SETUP"
                            }
                            currentScreen = target
                        }
                        if (userName == "User" && !user.displayName.isNullOrBlank()) {
                            userName = user.displayName!!
                        }
                        if (userEmail.isBlank() && !user.email.isNullOrBlank()) {
                            userEmail = user.email!!
                        }
                    } else {
                        // Firebase currentUser is null. Only navigate to LOGIN if local session is also missing
                        val session = AuthManager.getActiveSession(context)
                        if (session == null) {
                            currentScreen = "LOGIN"
                        }
                    }
                }
                authInstance.addAuthStateListener(listener)
                onDispose {
                    authInstance.removeAuthStateListener(listener)
                }
            }

            // Synchronize FamilyManager with localSession if needed
            LaunchedEffect(Unit) {
                if (localSession != null) {
                    if (FamilyManager.getStoredFamilyId(context).isBlank() && localSession.familyId.isNotBlank()) {
                        FamilyManager.saveStoredFamilyId(context, localSession.familyId)
                    }
                    if (FamilyManager.getStoredUserId(context).isBlank() && localSession.userId.isNotBlank()) {
                        FamilyManager.saveUserId(context, localSession.userId)
                    }
                    if (localSession.screen == "CHILD") {
                        FamilyManager.saveStoredUserRole(context, FamilyRole.CHILD)
                        if (localSession.familyId.isNotBlank()) {
                            FamilyManager.saveStoredMemberStatus(context, MemberStatus.APPROVED)
                        }
                    } else if (localSession.screen == "GUARDIAN") {
                        FamilyManager.saveStoredUserRole(context, FamilyRole.GUARDIAN)
                        if (localSession.familyId.isNotBlank()) {
                            FamilyManager.saveStoredMemberStatus(context, MemberStatus.APPROVED)
                        }
                    }
                }
            }

            // Ensure persistent background service is active for either Child or Guardian
            LaunchedEffect(currentScreen) {
                if (currentScreen == "CHILD") {
                    com.homesync.app.service.HomeSyncForegroundService.startForChild(context)
                } else if (currentScreen == "GUARDIAN") {
                    com.homesync.app.service.HomeSyncForegroundService.startForGuardian(context)
                }
            }

            // Route to GUARDIAN screen if launched via Emergency SOS alert
            LaunchedEffect(intent) {
                if (intent?.getStringExtra("NAVIGATE_TO") == "EMERGENCY_SOS") {
                    val role = FamilyManager.getStoredUserRole(context)
                    if (role == FamilyRole.GUARDIAN || localSession?.screen == "GUARDIAN") {
                        currentScreen = "GUARDIAN"
                    }
                }
            }

            // Startup state machine: verify authoritative hs_users/{uid} from Firestore in background
            LaunchedEffect(authUser?.uid) {
                val user = authUser ?: authInstance.currentUser
                if (user == null) {
                    val session = AuthManager.getActiveSession(context)
                    if (session == null) {
                        currentScreen = "LOGIN"
                    }
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
                            val session = AuthManager.getActiveSession(context)
                            val hasLocalApproved = session != null && (session.screen == "CHILD" || session.screen == "GUARDIAN")

                            if (!doc.exists()) {
                                Log.i("HomeSyncAuth", "AUTH_USER_PROFILE_NOT_FOUND uid=$uid")
                                Log.i("HomeSyncAuth", "AUTH_NEW_ACCOUNT uid=$uid")
                                val existingFamId = FamilyManager.getStoredFamilyId(context).ifBlank { session?.familyId ?: "" }
                                val initialRole = if (session != null && session.screen == "CHILD") FamilyRole.CHILD
                                    else if (session != null && session.screen == "GUARDIAN") FamilyRole.GUARDIAN
                                    else (if (userAge in 1..15) FamilyRole.CHILD else FamilyRole.GUARDIAN)

                                val userPayload = mapOf(
                                    "uid" to uid,
                                    "userId" to uid,
                                    "firebaseAuthUid" to uid,
                                    "name" to (user.displayName?.ifBlank { "User" } ?: userName),
                                    "displayName" to (user.displayName?.ifBlank { "User" } ?: userName),
                                    "email" to userEmailStr,
                                    "role" to initialRole.name,
                                    "familyId" to existingFamId,
                                    "status" to (if (existingFamId.isNotBlank()) "APPROVED" else (if (initialRole == FamilyRole.GUARDIAN) "APPROVED" else "NONE")),
                                    "membershipStatus" to (if (existingFamId.isNotBlank()) "APPROVED" else "NONE"),
                                    "createdAt" to System.currentTimeMillis(),
                                    "updatedAt" to System.currentTimeMillis()
                                )
                                db.collection("hs_users").document(uid).set(userPayload, com.google.firebase.firestore.SetOptions.merge())
                                Log.i("HomeSyncAuth", "AUTH_USER_PROFILE_CREATED uid=$uid role=${initialRole.name}")
                                Log.i("HomeSyncAuth", "AUTH_ROLE_RESOLVED role=${initialRole.name}")
                                FamilyManager.saveUserId(context, uid)
                                FamilyManager.saveStoredUserRole(context, initialRole)
                                if (existingFamId.isBlank() && !hasLocalApproved) {
                                    FamilyManager.saveStoredFamilyId(context, "")
                                    FamilyManager.saveStoredMemberStatus(context, MemberStatus.PENDING)
                                    currentScreen = "FAMILY_SETUP"
                                }
                            } else {
                                Log.i("HomeSyncAuth", "AUTH_USER_PROFILE_FOUND uid=$uid")
                                val rawRole = doc.getString("role") ?: (if (userAge in 1..15) "CHILD" else "GUARDIAN")
                                val fId = doc.getString("familyId")?.trim() ?: ""
                                val rawStatus = doc.getString("membershipStatus") ?: doc.getString("status") ?: ""
                                val isApproved = rawStatus.contains("APPROV", ignoreCase = true)
                                val role = if (rawRole.contains("CHILD", ignoreCase = true)) FamilyRole.CHILD else FamilyRole.GUARDIAN
                                Log.i("HomeSyncAuth", "AUTH_ROLE_RESOLVED role=${role.name}")

                                val docName = doc.getString("name") ?: doc.getString("displayName")
                                if (!docName.isNullOrBlank()) userName = docName
                                val docEmail = doc.getString("email")
                                if (!docEmail.isNullOrBlank()) userEmail = docEmail
                                userAge = if (role == FamilyRole.CHILD) 10 else 35

                                FamilyManager.saveUserId(context, uid)
                                FamilyManager.saveStoredUserRole(context, role)

                                val effectiveFamilyId = if (fId.isNotBlank()) fId else FamilyManager.getStoredFamilyId(context).ifBlank { session?.familyId ?: "" }
                                if (effectiveFamilyId.isNotBlank()) {
                                    FamilyManager.saveStoredFamilyId(context, effectiveFamilyId)
                                }

                                if (isApproved || (hasLocalApproved && effectiveFamilyId.isNotBlank())) {
                                    FamilyManager.saveStoredMemberStatus(context, MemberStatus.APPROVED)
                                    if (effectiveFamilyId.isNotBlank()) {
                                        Log.i("HomeSyncAuth", "AUTH_FAMILY_RESTORED familyId=$effectiveFamilyId role=${role.name}")
                                    }
                                }

                                val nextScreen = when {
                                    role == FamilyRole.GUARDIAN && effectiveFamilyId.isNotBlank() && (isApproved || hasLocalApproved) -> "GUARDIAN"
                                    role == FamilyRole.CHILD && effectiveFamilyId.isNotBlank() && (isApproved || hasLocalApproved) -> {
                                        val cleanDevId = ChildIdManager.getDeviceChildId(context)
                                        if (ScreenTimeManager.isRemoteLocked(context, cleanDevId) || ScreenTimeManager.isDeviceLocked(context, cleanDevId)) "LOCKED" else "CHILD"
                                    }
                                    hasLocalApproved -> session!!.screen
                                    else -> "FAMILY_SETUP"
                                }

                                // Only update currentScreen if currently on transient setup/login, do not disrupt already active screens
                                if (currentScreen == "LOGIN" || currentScreen == "FAMILY_SETUP") {
                                    currentScreen = nextScreen
                                }

                                // Always persist the verified session locally
                                AuthManager.saveActiveSession(
                                    context = context,
                                    session = ActiveSession(
                                        name = userName,
                                        email = userEmail,
                                        age = userAge,
                                        screen = if (nextScreen == "LOCKED") "CHILD" else nextScreen,
                                        childId = currentChildId,
                                        linkedChildCode = linkedChildCode,
                                        familyId = effectiveFamilyId,
                                        userId = uid
                                    )
                                )
                            }
                        }
                        .addOnFailureListener { e ->
                            Log.w("HomeSyncAuth", "Startup hs_users read failed: ${e.message}")
                            // Under temporary network or offline conditions, retain the existing screen and session.
                        }
                }
            }

            val devChildId = if (currentChildId.isNotBlank()) currentChildId else ChildIdManager.getDeviceChildId(context)
            val isChildRole = currentScreen != "GUARDIAN" && (
                currentScreen == "CHILD" || currentScreen == "LOCKED" ||
                (userAge in 1..15) || FamilyManager.getStoredUserRole(context) == FamilyRole.CHILD
            )

            // Direct real-time lock/unlock command listener on Child device (instant <100ms response)
            DisposableEffect(isChildRole, devChildId) {
                var cancelRtdb: (() -> Unit)? = null
                if (isChildRole && devChildId.isNotBlank()) {
                    cancelRtdb = com.homesync.app.util.FirebaseRealtimeSyncManager.listenScreenTimeWithCommandDetails(devChildId) { rem, locked, _, _, cmdId, _, _, targetChildId, commandType, _, _ ->
                        val cleanTarget = targetChildId.trim().uppercase()
                        val cleanChild = devChildId.trim().uppercase()
                        if (cleanTarget.isNotBlank() && cleanTarget != "ALL" && cleanTarget != cleanChild) return@listenScreenTimeWithCommandDetails

                        val isExplicitUnlockCmd = cmdId.startsWith("UNLOCK") || commandType.contains("UNLOCK") ||
                                cmdId.startsWith("RESET") || commandType.contains("RESET") ||
                                cmdId.startsWith("GRANT") || commandType.contains("EXTRA_TIME")
                        val isExplicitLockCmd = cmdId.startsWith("LOCK") || commandType.contains("LOCK")

                        val shouldLock = if (isExplicitUnlockCmd) false else (locked || isExplicitLockCmd)

                        if (shouldLock && currentScreen != "LOCKED" && currentScreen != "LOGIN" && currentScreen != "FAMILY_SETUP") {
                            currentScreen = "LOCKED"
                        } else if (!shouldLock && currentScreen == "LOCKED") {
                            currentScreen = "CHILD"
                        }
                    }
                }
                onDispose {
                    cancelRtdb?.invoke()
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                when (currentScreen) {
                    "LOGIN" -> LoginScreen(
                        onLoginSuccess = { name, email, age, code ->
                            val loggedInUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                            if (loggedInUser == null) {
                                currentScreen = "LOGIN"
                                return@LoginScreen
                            }

                            val uid = loggedInUser.uid
                            val authEmail = loggedInUser.email ?: email
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
                                            userName = if (!docName.isNullOrBlank()) docName else (if (name.isNotBlank() && name != "User") name else (loggedInUser.displayName ?: "User"))
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
                                            userName = if (name.isNotBlank() && name != "User") name else (loggedInUser.displayName ?: "User")
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
                            onEvicted = { currentScreen = "FAMILY_SETUP" },
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
                                currentScreen = if (isChildRole) "CHILD" else "GUARDIAN"
                            }
                        )
                    }
                }
            }
        }
    }
}