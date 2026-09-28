package com.homesync.app.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

enum class FamilyRole {
    GUARDIAN,
    CHILD
}

enum class MemberStatus {
    PENDING,
    APPROVED,
    REJECTED,
    REMOVED
}

enum class SyncState {
    REMOTE_CONFIRMED,
    LOCAL_PENDING_SYNC,
    OFFLINE,
    FAILED
}

sealed class MembershipResult {
    data class Approved(
        val familyId: String,
        val role: FamilyRole,
        val member: FamilyMember? = null,
        val syncState: SyncState = SyncState.REMOTE_CONFIRMED
    ) : MembershipResult()

    data class Pending(
        val familyId: String,
        val role: FamilyRole,
        val syncState: SyncState = SyncState.REMOTE_CONFIRMED
    ) : MembershipResult()

    object NoFamily : MembershipResult()

    data class OfflineOrError(
        val code: String,
        val message: String,
        val canRetry: Boolean = true,
        val cachedFallback: MembershipResult? = null
    ) : MembershipResult()
}

data class Family(
    val familyId: String = "",
    val familyName: String = "HomeSync Family",
    val createdBy: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class FamilyCreationResult(
    val family: Family,
    val syncState: SyncState
)

data class FamilyMember(
    val userId: String = "",
    val familyId: String = "",
    val name: String = "Member",
    val email: String = "",
    val role: FamilyRole = FamilyRole.GUARDIAN,
    val status: MemberStatus = MemberStatus.APPROVED,
    val profilePictureUrl: String = "",
    val joinedAt: Long = System.currentTimeMillis(),
    val lastActiveTime: Long = System.currentTimeMillis(),
    val isOnline: Boolean = true,
    val childCode: String = "",
    val phoneNumber: String = ""
)

data class FamilyJoinRequest(
    val requestId: String = "",
    val familyId: String = "",
    val userId: String = "",
    val name: String = "",
    val email: String = "",
    val role: FamilyRole = FamilyRole.CHILD,
    val status: MemberStatus = MemberStatus.PENDING,
    val requestedAt: Long = System.currentTimeMillis(),
    val childCode: String = ""
)

data class JoinRequestResult(
    val request: FamilyJoinRequest,
    val syncState: SyncState
)

class CompositeListenerRegistration(
    private val reg1: ListenerRegistration?,
    private val reg2: ListenerRegistration?
) : ListenerRegistration {
    override fun remove() {
        try { reg1?.remove() } catch (_: Exception) {}
        try { reg2?.remove() } catch (_: Exception) {}
    }
}

object FamilyManager {
    private const val TAG = "FamilyManager"
    private const val PREFS_NAME = "homesync_family_prefs"
    private const val KEY_FAMILY_ID = "active_family_id"
    private const val KEY_USER_ID = "active_user_id"
    private const val KEY_USER_ROLE = "active_user_role"
    private const val KEY_MEMBER_STATUS = "active_member_status"
    private const val KEY_CACHED_MEMBERS = "cached_family_members"
    private const val KEY_PENDING_FAMILY_ID = "pending_family_id"

    private const val COLLECTION_FAMILIES = "hs_families"
    private const val COLLECTION_USERS = "hs_users"

    private val random = SecureRandom()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Generates a sufficiently unique Family ID in the format: HS-XXXX (e.g. HS-4821).
     */
    fun generateFamilyId(): String {
        val number = 1000 + random.nextInt(9000)
        return "HS-$number"
    }

    private const val TAG_FAMILY = "HomeSyncFamily"

    /**
     * Canonical Firebase UID helper. Returns the current authenticated UID or null.
     */
    fun getCanonicalFirebaseUid(): String? {
        return FirebaseAuth.getInstance().currentUser?.uid
    }

    /**
     * Retrieves the current user's canonical HomeSync User ID.
     * Strictly binds to the authenticated FirebaseAuth currentUser UID.
     */
    fun getOrCreateUserId(context: Context, emailFallback: String = ""): String {
        val authUid = FirebaseAuth.getInstance().currentUser?.uid
        if (!authUid.isNullOrBlank()) {
            saveUserId(context, authUid)
            return authUid
        }

        val prefs = getPrefs(context)
        val saved = prefs.getString(KEY_USER_ID, null)
        if (!saved.isNullOrBlank() && !saved.startsWith("HS-", ignoreCase = true) && !saved.startsWith("u_", ignoreCase = true)) {
            return saved
        }

        return ""
    }

    fun saveUserId(context: Context, userId: String) {
        val clean = userId.trim()
        if (clean.isNotBlank()) {
            getPrefs(context).edit().putString(KEY_USER_ID, clean).apply()
        }
    }

    fun getStoredUserId(context: Context): String {
        return getPrefs(context).getString(KEY_USER_ID, "") ?: ""
    }

    fun getStoredFamilyId(context: Context): String {
        return getPrefs(context).getString(KEY_FAMILY_ID, "") ?: ""
    }

    fun saveStoredFamilyId(context: Context, familyId: String) {
        val clean = familyId.trim().uppercase()
        getPrefs(context).edit().putString(KEY_FAMILY_ID, clean).apply()
    }

    fun getPendingFamilyId(context: Context): String {
        return getPrefs(context).getString(KEY_PENDING_FAMILY_ID, "") ?: ""
    }

    fun savePendingFamilyId(context: Context, familyId: String) {
        val clean = familyId.trim().uppercase()
        getPrefs(context).edit().putString(KEY_PENDING_FAMILY_ID, clean).apply()
    }

    fun clearPendingFamilyId(context: Context) {
        getPrefs(context).edit().remove(KEY_PENDING_FAMILY_ID).apply()
    }

    fun getStoredUserRole(context: Context): FamilyRole {
        val raw = getPrefs(context).getString(KEY_USER_ROLE, FamilyRole.GUARDIAN.name)
        return try {
            FamilyRole.valueOf(raw ?: FamilyRole.GUARDIAN.name)
        } catch (_: Exception) {
            FamilyRole.GUARDIAN
        }
    }

    fun saveStoredUserRole(context: Context, role: FamilyRole) {
        getPrefs(context).edit().putString(KEY_USER_ROLE, role.name).apply()
    }

    fun getStoredMemberStatus(context: Context): MemberStatus {
        val raw = getPrefs(context).getString(KEY_MEMBER_STATUS, MemberStatus.APPROVED.name)
        return try {
            MemberStatus.valueOf(raw ?: MemberStatus.APPROVED.name)
        } catch (_: Exception) {
            MemberStatus.APPROVED
        }
    }

    fun saveStoredMemberStatus(context: Context, status: MemberStatus) {
        getPrefs(context).edit().putString(KEY_MEMBER_STATUS, status.name).apply()
    }

    /**
     * Maps a saved child tuple (childName, childCode) to their authoritative Firebase Auth UID
     * from the family member roster retrieved from FamilyManager.listenFamilyMembers.
     */
    fun resolveChildUid(childName: String, childCode: String, members: List<FamilyMember>): String {
        val cleanCode = childCode.trim()
        val cleanName = ChildIdManager.formatChildName(childName).trim().lowercase()

        // 1. Match by childCode stored in FamilyMember
        val codeMatch = members.firstOrNull {
            it.role == FamilyRole.CHILD && it.userId.isNotBlank() && it.childCode.isNotBlank() && it.childCode.equals(cleanCode, ignoreCase = true)
        }
        if (codeMatch != null) return codeMatch.userId

        // 2. Direct UID match: childCode already equals the Firebase Auth UID (must NOT start with HS-)
        if (!cleanCode.startsWith("HS-", ignoreCase = true)) {
            val directMatch = members.firstOrNull { it.role == FamilyRole.CHILD && it.userId.isNotBlank() && it.userId.equals(cleanCode, ignoreCase = true) }
            if (directMatch != null) return directMatch.userId
        }

        // 3. Authoritative match: by child name among FamilyMember.role == CHILD
        val nameMatch = members.firstOrNull {
            it.role == FamilyRole.CHILD && it.userId.isNotBlank() && (
                it.name.trim().lowercase() == cleanName ||
                ChildIdManager.formatChildName(it.name).trim().lowercase() == cleanName ||
                it.name.trim().lowercase().contains(cleanName) ||
                cleanName.contains(it.name.trim().lowercase())
            )
        }
        if (nameMatch != null) return nameMatch.userId

        // 4. Single child in family fallback
        val children = members.filter { it.role == FamilyRole.CHILD && it.userId.isNotBlank() }
        if (children.size == 1) {
            return children.first().userId
        }

        // Never return an HS- pairing code as a Firebase Auth UID
        return if (cleanCode.startsWith("HS-", ignoreCase = true)) "" else cleanCode
    }

    /**
     * Checks the user's family membership with a strict 3500ms safety timeout.
     * Evaluates local cache first, queries Firestore hs_users/{userId}, and clearly
     * reports REMOTE_CONFIRMED, LOCAL_PENDING_SYNC, OFFLINE, or NO_FAMILY.
     * GUARANTEES that onResult is invoked once and never hangs.
     */
    fun checkUserFamilyMembership(
        context: Context,
        userId: String,
        onResult: (MembershipResult) -> Unit
    ) {
        val cleanUserId = userId.trim()
        val storedUserId = getStoredUserId(context)
        val isUserMatching = cleanUserId.isNotBlank() && cleanUserId == storedUserId
        val storedFamilyId = if (isUserMatching) getStoredFamilyId(context) else ""
        val storedStatus = if (isUserMatching) getStoredMemberStatus(context) else MemberStatus.PENDING
        val storedRole = if (isUserMatching) getStoredUserRole(context) else FamilyRole.GUARDIAN
        val pendingFamilyId = if (isUserMatching) getPendingFamilyId(context) else ""

        val localFallback: MembershipResult? = when {
            storedFamilyId.isNotBlank() && storedStatus == MemberStatus.APPROVED ->
                MembershipResult.Approved(storedFamilyId, storedRole, syncState = SyncState.OFFLINE)
            pendingFamilyId.isNotBlank() && storedStatus == MemberStatus.PENDING ->
                MembershipResult.Pending(pendingFamilyId, storedRole, syncState = SyncState.OFFLINE)
            else -> null
        }

        if (cleanUserId.isBlank()) {
            if (localFallback != null) onResult(localFallback)
            else onResult(MembershipResult.NoFamily)
            return
        }

        val db = FirebaseSyncManager.getDb()
        if (db == null) {
            if (localFallback != null) {
                onResult(localFallback)
            } else {
                onResult(
                    MembershipResult.OfflineOrError(
                        code = "DATABASE_UNAVAILABLE",
                        message = "Database service unavailable. Offline mode enabled.",
                        cachedFallback = null
                    )
                )
            }
            return
        }

        var hasResponded = false
        val timeoutRunnable = Runnable {
            if (!hasResponded) {
                hasResponded = true
                Log.w(TAG, "checkUserFamilyMembership query timed out after 3500ms; falling back safely.")
                if (localFallback != null) {
                    onResult(localFallback)
                } else {
                    onResult(MembershipResult.NoFamily)
                }
            }
        }
        mainHandler.postDelayed(timeoutRunnable, 3500)

        db.collection(COLLECTION_USERS).document(cleanUserId).get()
            .addOnSuccessListener { doc ->
                if (hasResponded) return@addOnSuccessListener

                val userDocExists = doc.exists()
                if (userDocExists) {
                    Log.i("HomeSyncAuth", "AUTH_USER_PROFILE_FOUND uid=$cleanUserId")
                } else {
                    Log.i("HomeSyncAuth", "AUTH_USER_PROFILE_NOT_FOUND uid=$cleanUserId")
                    Log.i("HomeSyncAuth", "AUTH_NEW_ACCOUNT uid=$cleanUserId")
                }
                val fidFromDoc = if (userDocExists) doc.getString("familyId")?.trim() ?: "" else ""
                val rawStatus = if (userDocExists) (doc.getString("membershipStatus") ?: doc.getString("status") ?: "") else ""
                val rawRole = if (userDocExists) (doc.getString("role") ?: "") else ""

                val effectiveFamilyId = fidFromDoc.ifBlank { if (isUserMatching) storedFamilyId else "" }.ifBlank { if (isUserMatching) pendingFamilyId else "" }
                val role = if (rawRole.contains("CHILD", ignoreCase = true)) FamilyRole.CHILD
                    else if (rawRole.contains("GUARDIAN", ignoreCase = true)) FamilyRole.GUARDIAN
                    else storedRole
                Log.i("HomeSyncAuth", "AUTH_ROLE_RESOLVED role=${role.name}")

                val isDocApproved = rawStatus.contains("APPROV", ignoreCase = true)
                val isDocPending = rawStatus.contains("PEND", ignoreCase = true)

                // If user document already clearly confirms APPROVED with valid familyId
                if (effectiveFamilyId.isNotBlank() && isDocApproved) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    hasResponded = true
                    Log.i("HomeSyncAuth", "AUTH_FAMILY_RESTORED familyId=$effectiveFamilyId role=${role.name}")
                    val syncState = if (doc.metadata.hasPendingWrites()) SyncState.LOCAL_PENDING_SYNC else SyncState.REMOTE_CONFIRMED
                    saveStoredFamilyId(context, effectiveFamilyId)
                    saveStoredUserRole(context, role)
                    saveStoredMemberStatus(context, MemberStatus.APPROVED)
                    clearPendingFamilyId(context)
                    onResult(MembershipResult.Approved(effectiveFamilyId, role, syncState = syncState))
                    return@addOnSuccessListener
                }

                // If effectiveFamilyId is available, authoritatively check hs_families/{familyId}/members/{userId}
                if (effectiveFamilyId.isNotBlank()) {
                    db.collection(COLLECTION_FAMILIES).document(effectiveFamilyId).collection("members").document(cleanUserId).get()
                        .addOnSuccessListener { mDoc ->
                            if (hasResponded) return@addOnSuccessListener
                            mainHandler.removeCallbacks(timeoutRunnable)
                            hasResponded = true

                            if (mDoc.exists()) {
                                val mStatusRaw = mDoc.getString("status") ?: mDoc.getString("membershipStatus") ?: "APPROVED"
                                val isApproved = mStatusRaw.contains("APPROV", ignoreCase = true)
                                val mRoleRaw = mDoc.getString("role") ?: rawRole
                                val actualRole = if (mRoleRaw.contains("CHILD", ignoreCase = true)) FamilyRole.CHILD else FamilyRole.GUARDIAN

                                if (isApproved) {
                                    Log.i("HomeSyncAuth", "AUTH_FAMILY_RESTORED familyId=$effectiveFamilyId role=${actualRole.name}")
                                    val syncState = if (mDoc.metadata.hasPendingWrites()) SyncState.LOCAL_PENDING_SYNC else SyncState.REMOTE_CONFIRMED
                                    saveStoredFamilyId(context, effectiveFamilyId)
                                    saveStoredUserRole(context, actualRole)
                                    saveStoredMemberStatus(context, MemberStatus.APPROVED)
                                    clearPendingFamilyId(context)

                                    // Rehydrate hs_users asynchronously
                                    val userUpdates = mapOf(
                                        "userId" to cleanUserId,
                                        "familyId" to effectiveFamilyId,
                                        "status" to MemberStatus.APPROVED.name,
                                        "membershipStatus" to MemberStatus.APPROVED.name,
                                        "role" to actualRole.name,
                                        "updatedAt" to System.currentTimeMillis()
                                    )
                                    db.collection(COLLECTION_USERS).document(cleanUserId).set(userUpdates, SetOptions.merge())

                                    onResult(MembershipResult.Approved(effectiveFamilyId, actualRole, syncState = syncState))
                                    return@addOnSuccessListener
                                }
                            }

                            if (isDocPending) {
                                savePendingFamilyId(context, effectiveFamilyId)
                                saveStoredUserRole(context, role)
                                saveStoredMemberStatus(context, MemberStatus.PENDING)
                                onResult(MembershipResult.Pending(effectiveFamilyId, role, syncState = SyncState.REMOTE_CONFIRMED))
                            } else if (localFallback != null) {
                                onResult(localFallback)
                            } else {
                                onResult(MembershipResult.NoFamily)
                            }
                        }
                        .addOnFailureListener {
                            if (hasResponded) return@addOnFailureListener
                            mainHandler.removeCallbacks(timeoutRunnable)
                            hasResponded = true

                            if (isDocPending) {
                                savePendingFamilyId(context, effectiveFamilyId)
                                saveStoredUserRole(context, role)
                                saveStoredMemberStatus(context, MemberStatus.PENDING)
                                onResult(MembershipResult.Pending(effectiveFamilyId, role, syncState = SyncState.REMOTE_CONFIRMED))
                            } else if (localFallback != null) {
                                onResult(localFallback)
                            } else {
                                onResult(MembershipResult.NoFamily)
                            }
                        }
                } else {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    hasResponded = true
                    if (localFallback != null) {
                        onResult(localFallback)
                    } else {
                        onResult(MembershipResult.NoFamily)
                    }
                }
            }
            .addOnFailureListener { e ->
                if (hasResponded) return@addOnFailureListener
                mainHandler.removeCallbacks(timeoutRunnable)
                hasResponded = true

                Log.e(TAG, "checkUserFamilyMembership Firestore read failed", e)
                val isOffline = e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.UNAVAILABLE
                val isPermDenied = e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED

                val code = if (isOffline) "OFFLINE" else if (isPermDenied) "PERMISSION_DENIED" else "UNKNOWN"
                val msg = if (isOffline) {
                    "Offline. Working with local cached data."
                } else if (isPermDenied) {
                    "Permission denied. Please verify your Google account."
                } else {
                    e.localizedMessage ?: "Unable to verify family membership."
                }

                val isNetworkOnline = com.homesync.app.util.NetworkHelper.isOnline(context)
                if (localFallback != null) {
                    onResult(localFallback)
                } else if (isNetworkOnline && (isOffline || isPermDenied)) {
                    // Phone has 5G/Wi-Fi connection; document hs_users/{userId} simply does not exist yet or
                    // client is fresh. Treat as NoFamily so user can immediately Create or Join a family!
                    Log.i(TAG, "Device is online but Firestore threw $code. Treating as fresh NoFamily.")
                    onResult(MembershipResult.NoFamily)
                } else {
                    onResult(MembershipResult.OfflineOrError(code, msg, canRetry = true, cachedFallback = null))
                }
            }
    }

    /**
     * Creates a new Family record with a unique Family ID (e.g. HS-4821).
     * Bounded by a 4500ms safety timeout to guarantee that the UI exits the loading state.
     * Distinguishes REMOTE_CONFIRMED vs LOCAL_PENDING_SYNC.
     */
    fun createFamily(
        context: Context,
        creatorUserId: String,
        creatorName: String,
        creatorEmail: String,
        onComplete: (Result<FamilyCreationResult>) -> Unit
    ) {
        val authUser = FirebaseAuth.getInstance().currentUser
        val canonicalUid = authUser?.uid

        Log.i(TAG_FAMILY, "CREATE_FAMILY: Started.")
        Log.i(TAG_FAMILY, "CREATE_FAMILY: FirebaseAuth.currentUser != null: ${authUser != null}")
        Log.i(TAG_FAMILY, "CREATE_FAMILY: Firebase Auth UID: ${canonicalUid ?: "NULL"}")
        Log.i(TAG_FAMILY, "CREATE_FAMILY: Passed creatorUserId: $creatorUserId")

        if (authUser == null || canonicalUid.isNullOrBlank()) {
            val err = FirebaseFirestoreException(
                "User not authenticated in Firebase Auth. Please sign in with Google or Email.",
                FirebaseFirestoreException.Code.UNAUTHENTICATED
            )
            Log.e(TAG_FAMILY, "CREATE_FAMILY: Aborted - UNAUTHENTICATED")
            onComplete(Result.failure(err))
            return
        }

        // Strictly enforce canonical UID matching request.auth.uid
        val targetUserId = canonicalUid
        saveUserId(context, targetUserId)

        val db = FirebaseSyncManager.getDb()
        if (db == null) {
            val localFamilyId = generateFamilyId()
            saveStoredFamilyId(context, localFamilyId)
            saveStoredUserRole(context, FamilyRole.GUARDIAN)
            saveStoredMemberStatus(context, MemberStatus.APPROVED)
            clearPendingFamilyId(context)
            onComplete(
                Result.success(
                    FamilyCreationResult(
                        Family(localFamilyId, "$creatorName's Family", targetUserId),
                        SyncState.OFFLINE
                    )
                )
            )
            return
        }

        val familyId = generateFamilyId()
        val familyRef = db.collection(COLLECTION_FAMILIES).document(familyId)
        val memberRef = familyRef.collection("members").document(targetUserId)
        val userRef = db.collection(COLLECTION_USERS).document(targetUserId)

        Log.i(TAG_FAMILY, "CREATE_FAMILY: Target Family ID: $familyId")
        Log.i(TAG_FAMILY, "CREATE_FAMILY: Writing to paths:")
        Log.i(TAG_FAMILY, "  [Family] -> ${familyRef.path}")
        Log.i(TAG_FAMILY, "  [Member] -> ${memberRef.path}")
        Log.i(TAG_FAMILY, "  [User]   -> ${userRef.path}")

        val family = Family(
            familyId = familyId,
            familyName = "$creatorName's Family",
            createdBy = targetUserId,
            createdAt = System.currentTimeMillis()
        )

        // Consistency: Provide both createdBy and creatorUserId for complete rule compatibility
        val familyPayload = mapOf(
            "familyId" to family.familyId,
            "familyName" to family.familyName,
            "createdBy" to targetUserId,
            "creatorUserId" to targetUserId,
            "createdAt" to family.createdAt
        )

        val memberPayload = mapOf(
            "userId" to targetUserId,
            "familyId" to familyId,
            "name" to creatorName,
            "email" to (authUser.email ?: creatorEmail),
            "role" to FamilyRole.GUARDIAN.name,
            "status" to MemberStatus.APPROVED.name,
            "joinedAt" to System.currentTimeMillis(),
            "lastActiveTime" to System.currentTimeMillis(),
            "isOnline" to true
        )

        val userPayload = mapOf(
            "userId" to targetUserId,
            "familyId" to familyId,
            "name" to creatorName,
            "displayName" to creatorName,
            "email" to (authUser.email ?: creatorEmail),
            "role" to FamilyRole.GUARDIAN.name,
            "status" to MemberStatus.APPROVED.name,
            "membershipStatus" to MemberStatus.APPROVED.name,
            "updatedAt" to System.currentTimeMillis()
        )

        var hasFinished = false

        fun saveLocalState() {
            saveStoredFamilyId(context, familyId)
            saveStoredUserRole(context, FamilyRole.GUARDIAN)
            saveStoredMemberStatus(context, MemberStatus.APPROVED)
            clearPendingFamilyId(context)
        }

        val safetyTimeout = Runnable {
            if (!hasFinished) {
                hasFinished = true
                Log.w(TAG_FAMILY, "createFamily write awaiting remote acknowledgment; saved to offline queue: $familyId")
                saveLocalState()
                onComplete(Result.success(FamilyCreationResult(family, SyncState.LOCAL_PENDING_SYNC)))
            }
        }
        mainHandler.postDelayed(safetyTimeout, 4500)

        // Deterministic idempotent batch write
        val batch = db.batch()
        batch.set(familyRef, familyPayload, SetOptions.merge())
        batch.set(memberRef, memberPayload, SetOptions.merge())
        batch.set(userRef, userPayload, SetOptions.merge())

        batch.commit()
            .addOnSuccessListener {
                if (hasFinished) return@addOnSuccessListener
                mainHandler.removeCallbacks(safetyTimeout)
                hasFinished = true
                saveLocalState()
                Log.i(TAG_FAMILY, "CREATE_FAMILY: Confirmed remotely in Cloud Firestore! Family ID: $familyId")
                onComplete(Result.success(FamilyCreationResult(family, SyncState.REMOTE_CONFIRMED)))
            }
            .addOnFailureListener { e ->
                if (hasFinished) return@addOnFailureListener
                mainHandler.removeCallbacks(safetyTimeout)
                hasFinished = true

                val exceptionClass = e.javaClass.name
                val errorCode = (e as? FirebaseFirestoreException)?.code?.name ?: "UNKNOWN"
                val errorMsg = e.message ?: "No error message"
                Log.e(TAG_FAMILY, "CREATE_FAMILY: FAILED! class=$exceptionClass code=$errorCode message=$errorMsg", e)

                if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.UNAVAILABLE) {
                    saveLocalState()
                    Log.w(TAG_FAMILY, "createFamily queued locally while offline: $familyId")
                    onComplete(Result.success(FamilyCreationResult(family, SyncState.LOCAL_PENDING_SYNC)))
                } else {
                    onComplete(Result.failure(e))
                }
            }
    }

    /**
     * Submits a request to join an existing family.
     * Bounded by a 4500ms safety timeout to guarantee that the UI exits the loading state.
     * Distinguishes REMOTE_CONFIRMED vs LOCAL_PENDING_SYNC.
     */
    fun submitJoinRequest(
        context: Context,
        familyId: String,
        userId: String,
        name: String,
        email: String,
        role: FamilyRole,
        onComplete: (Result<JoinRequestResult>) -> Unit
    ) {
        val authUser = FirebaseAuth.getInstance().currentUser
        if (authUser == null) {
            onComplete(Result.failure(Exception("UNAUTHENTICATED: Please sign in with Google.")))
            return
        }
        val canonicalUserId = authUser.uid
        val cleanFamilyId = familyId.trim().uppercase()
        if (cleanFamilyId.isBlank()) {
            onComplete(Result.failure(Exception("Family ID cannot be blank.")))
            return
        }
        if (!cleanFamilyId.startsWith("HS-")) {
            onComplete(Result.failure(Exception("INVALID_FAMILY_ID: Format must be HS-XXXX.")))
            return
        }

        val db = FirebaseSyncManager.getDb()
        if (db == null) {
            onComplete(Result.failure(Exception("DATABASE_UNAVAILABLE: Unable to connect to Firebase.")))
            return
        }

        Log.i(TAG_FAMILY, "JOIN: Starting submission for User $canonicalUserId to Family $cleanFamilyId as ${role.name}")

        val familyRef = db.collection(COLLECTION_FAMILIES).document(cleanFamilyId)

        // 1. Verify family document exists
        familyRef.get().addOnCompleteListener { famTask ->
            if (!famTask.isSuccessful) {
                val err = famTask.exception ?: Exception("Failed to query family record.")
                Log.e(TAG_FAMILY, "JOIN: Error querying family $cleanFamilyId", err)
                onComplete(Result.failure(err))
                return@addOnCompleteListener
            }

            val famDoc = famTask.result
            if (!famDoc.exists()) {
                Log.w(TAG_FAMILY, "JOIN: Family not found: $cleanFamilyId")
                onComplete(Result.failure(Exception("FAMILY_NOT_FOUND: Family $cleanFamilyId does not exist.")))
                return@addOnCompleteListener
            }

            // 2. Check if already an approved member
            familyRef.collection("members").document(canonicalUserId).get().addOnCompleteListener { memTask ->
                if (memTask.isSuccessful && memTask.result.exists()) {
                    val mDoc = memTask.result
                    val mStatusRaw = mDoc.getString("status") ?: mDoc.getString("membershipStatus") ?: "APPROVED"
                    val isApproved = mStatusRaw.contains("APPROV", ignoreCase = true)
                    if (isApproved) {
                        val mRoleRaw = mDoc.getString("role") ?: role.name
                        val actualRole = if (mRoleRaw.contains("CHILD", ignoreCase = true)) FamilyRole.CHILD else FamilyRole.GUARDIAN
                        Log.i(TAG_FAMILY, "JOIN: User $canonicalUserId is already an approved member of $cleanFamilyId as ${actualRole.name}")
                        saveStoredFamilyId(context, cleanFamilyId)
                        saveStoredMemberStatus(context, MemberStatus.APPROVED)
                        saveStoredUserRole(context, actualRole)
                        clearPendingFamilyId(context)

                        // Rehydrate hs_users
                        val userPayload = mapOf(
                            "userId" to canonicalUserId,
                            "firebaseAuthUid" to canonicalUserId,
                            "familyId" to cleanFamilyId,
                            "name" to (mDoc.getString("name") ?: name),
                            "displayName" to (mDoc.getString("name") ?: name),
                            "email" to (mDoc.getString("email") ?: email),
                            "role" to actualRole.name,
                            "membershipStatus" to MemberStatus.APPROVED.name,
                            "status" to MemberStatus.APPROVED.name,
                            "updatedAt" to System.currentTimeMillis()
                        )
                        db.collection(COLLECTION_USERS).document(canonicalUserId).set(userPayload, SetOptions.merge())

                        val approvedReq = FamilyJoinRequest(
                            requestId = canonicalUserId,
                            familyId = cleanFamilyId,
                            userId = canonicalUserId,
                            name = mDoc.getString("name") ?: name,
                            email = mDoc.getString("email") ?: email,
                            role = actualRole,
                            status = MemberStatus.APPROVED,
                            requestedAt = mDoc.getLong("joinedAt") ?: System.currentTimeMillis()
                        )
                        onComplete(Result.success(JoinRequestResult(approvedReq, SyncState.REMOTE_CONFIRMED)))
                        return@addOnCompleteListener
                    }
                }

                // 3. Check if join request is already pending
                familyRef.collection("join_requests").document(canonicalUserId).get().addOnCompleteListener { reqTask ->
                    if (reqTask.isSuccessful && reqTask.result.exists() &&
                        reqTask.result.getString("status") == MemberStatus.PENDING.name
                    ) {
                        Log.i(TAG_FAMILY, "JOIN: User $canonicalUserId already has a pending request in $cleanFamilyId")
                        savePendingFamilyId(context, cleanFamilyId)
                        saveStoredUserRole(context, role)
                        saveStoredMemberStatus(context, MemberStatus.PENDING)

                        val existingReq = FamilyJoinRequest(
                            requestId = canonicalUserId,
                            familyId = cleanFamilyId,
                            userId = canonicalUserId,
                            name = name.trim().ifBlank { "New Member" },
                            email = email.trim(),
                            role = role,
                            status = MemberStatus.PENDING,
                            requestedAt = reqTask.result.getLong("requestedAt") ?: System.currentTimeMillis()
                        )
                        onComplete(Result.success(JoinRequestResult(existingReq, SyncState.REMOTE_CONFIRMED)))
                        return@addOnCompleteListener
                    }

                    // 4. Create deterministic join request
                    val request = FamilyJoinRequest(
                        requestId = canonicalUserId,
                        familyId = cleanFamilyId,
                        userId = canonicalUserId,
                        name = name.trim().ifBlank { "New Member" },
                        email = email.trim(),
                        role = role,
                        status = MemberStatus.PENDING,
                        requestedAt = System.currentTimeMillis()
                    )

                    val currentChildCode = if (role == FamilyRole.CHILD) ChildIdManager.getDeviceChildId(context) else ""
                    val requestPayload = mapOf(
                        "requestId" to canonicalUserId,
                        "familyId" to cleanFamilyId,
                        "userId" to canonicalUserId,
                        "name" to request.name,
                        "displayName" to request.name,
                        "email" to request.email,
                        "role" to request.role.name,
                        "status" to MemberStatus.PENDING.name,
                        "requestedAt" to request.requestedAt,
                        "childCode" to currentChildCode
                    )

                    val userPayload = mapOf(
                        "userId" to canonicalUserId,
                        "firebaseAuthUid" to canonicalUserId,
                        "familyId" to cleanFamilyId,
                        "name" to request.name,
                        "displayName" to request.name,
                        "email" to request.email,
                        "role" to role.name,
                        "membershipStatus" to MemberStatus.PENDING.name,
                        "status" to MemberStatus.PENDING.name,
                        "childCode" to currentChildCode,
                        "updatedAt" to System.currentTimeMillis()
                    )

                    var hasFinished = false
                    fun saveLocalPending() {
                        savePendingFamilyId(context, cleanFamilyId)
                        saveStoredUserRole(context, role)
                        saveStoredMemberStatus(context, MemberStatus.PENDING)
                    }

                    val safetyTimeout = Runnable {
                        if (!hasFinished) {
                            hasFinished = true
                            Log.w(TAG_FAMILY, "JOIN: submitJoinRequest awaiting remote confirmation; queued locally: $cleanFamilyId")
                            saveLocalPending()
                            onComplete(Result.success(JoinRequestResult(request, SyncState.LOCAL_PENDING_SYNC)))
                        }
                    }
                    mainHandler.postDelayed(safetyTimeout, 4500)

                    val batch = db.batch()
                    batch.set(familyRef.collection("join_requests").document(canonicalUserId), requestPayload, SetOptions.merge())
                    batch.set(db.collection(COLLECTION_USERS).document(canonicalUserId), userPayload, SetOptions.merge())

                    batch.commit()
                        .addOnSuccessListener {
                            if (hasFinished) return@addOnSuccessListener
                            mainHandler.removeCallbacks(safetyTimeout)
                            hasFinished = true
                            saveLocalPending()
                            Log.i(TAG_FAMILY, "JOIN: submitJoinRequest confirmed remotely by Firebase for $cleanFamilyId")

                            val notif = SystemNotification(
                                id = "join_req_${canonicalUserId}_${System.currentTimeMillis()}",
                                title = "New Family Join Request",
                                message = "${request.name} requested to join Family $cleanFamilyId as ${request.role.name}",
                                type = NotificationType.FAMILY_JOIN_REQUEST,
                                childName = request.name,
                                childCode = canonicalUserId,
                                actionData = cleanFamilyId,
                                targetRole = "GUARDIAN"
                            )
                            FirebaseSyncManager.sendNotificationToCloud(notif)
                            onComplete(Result.success(JoinRequestResult(request, SyncState.REMOTE_CONFIRMED)))
                        }
                        .addOnFailureListener { e ->
                            if (hasFinished) return@addOnFailureListener
                            mainHandler.removeCallbacks(safetyTimeout)
                            hasFinished = true

                            if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.UNAVAILABLE) {
                                saveLocalPending()
                                onComplete(Result.success(JoinRequestResult(request, SyncState.LOCAL_PENDING_SYNC)))
                            } else {
                                Log.e(TAG_FAMILY, "JOIN: submitJoinRequest write failed", e)
                                onComplete(Result.failure(e))
                            }
                        }
                }
            }
        }
    }

    /**
     * Listens in real-time to the status of a join request for a user.
     * Attaches dual listeners:
     * 1. hs_families/{familyId}/join_requests/{userId}
     * 2. hs_users/{userId}
     * Guarantees transition to APPROVED if either document is approved.
     * Returns a CompositeListenerRegistration that safely unregisters both.
     */
    fun listenJoinRequestStatus(
        context: Context,
        familyId: String,
        userId: String,
        onStatusChanged: (MemberStatus, SyncState) -> Unit,
        onError: (String) -> Unit = {}
    ): ListenerRegistration? {
        val cleanFamilyId = familyId.trim().uppercase()
        val cleanUserId = userId.trim()
        if (cleanFamilyId.isBlank() || cleanUserId.isBlank()) return null
        val db = FirebaseSyncManager.getDb() ?: return null

        Log.i(TAG_FAMILY, "CHILD: Attaching dual join-request listener for $cleanUserId in Family $cleanFamilyId")

        // 1. Listener on join_requests document
        val reg1 = db.collection(COLLECTION_FAMILIES)
            .document(cleanFamilyId)
            .collection("join_requests")
            .document(cleanUserId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG_FAMILY, "CHILD: join_requests listener error: ${error.code}", error)
                    if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        onError("Permission denied. Waiting for Guardian approval.")
                    } else {
                        onError(error.localizedMessage ?: "Connection error")
                    }
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val rawStatus = snapshot.getString("status") ?: MemberStatus.PENDING.name
                    val status = try { MemberStatus.valueOf(rawStatus) } catch (_: Exception) { MemberStatus.PENDING }
                    val syncState = if (snapshot.metadata.hasPendingWrites()) SyncState.LOCAL_PENDING_SYNC else SyncState.REMOTE_CONFIRMED

                    saveStoredMemberStatus(context, status)
                    if (status == MemberStatus.APPROVED) {
                        saveStoredFamilyId(context, cleanFamilyId)
                        clearPendingFamilyId(context)
                        Log.i(TAG_FAMILY, "CHILD: Detected APPROVED in join_requests document for $cleanUserId")
                    }
                    mainHandler.post { onStatusChanged(status, syncState) }
                }
            }

        // 2. Listener on hs_users document
        val reg2 = db.collection(COLLECTION_USERS)
            .document(cleanUserId)
            .addSnapshotListener { uDoc, uError ->
                if (uError != null || uDoc == null || !uDoc.exists()) return@addSnapshotListener

                val mStatusStr = uDoc.getString("membershipStatus") ?: uDoc.getString("status") ?: ""
                val uFamilyId = uDoc.getString("familyId") ?: ""

                if (uFamilyId.equals(cleanFamilyId, ignoreCase = true) && mStatusStr.equals("APPROVED", ignoreCase = true)) {
                    Log.i(TAG_FAMILY, "CHILD: Detected APPROVED in hs_users document for $cleanUserId")
                    saveStoredFamilyId(context, cleanFamilyId)
                    saveStoredMemberStatus(context, MemberStatus.APPROVED)
                    clearPendingFamilyId(context)
                    mainHandler.post { onStatusChanged(MemberStatus.APPROVED, SyncState.REMOTE_CONFIRMED) }
                } else if (mStatusStr.equals("REJECTED", ignoreCase = true)) {
                    Log.i(TAG_FAMILY, "CHILD: Detected REJECTED in hs_users document for $cleanUserId")
                    saveStoredMemberStatus(context, MemberStatus.REJECTED)
                    clearPendingFamilyId(context)
                    mainHandler.post { onStatusChanged(MemberStatus.REJECTED, SyncState.REMOTE_CONFIRMED) }
                }
            }

        return CompositeListenerRegistration(reg1, reg2)
    }

    /**
     * Directly checks the current join request status on demand (for manual user refresh).
     * Bounded by a 3000ms timeout.
     */
    fun checkJoinRequestStatusNow(
        context: Context,
        familyId: String,
        userId: String,
        onResult: (MemberStatus, SyncState) -> Unit
    ) {
        val cleanFamilyId = familyId.trim().uppercase()
        val cleanUserId = userId.trim()
        val db = FirebaseSyncManager.getDb()
        if (db == null || cleanFamilyId.isBlank() || cleanUserId.isBlank()) {
            onResult(getStoredMemberStatus(context), SyncState.OFFLINE)
            return
        }

        var handled = false
        val timeout = Runnable {
            if (!handled) {
                handled = true
                onResult(getStoredMemberStatus(context), SyncState.OFFLINE)
            }
        }
        mainHandler.postDelayed(timeout, 3000)

        db.collection(COLLECTION_FAMILIES)
            .document(cleanFamilyId)
            .collection("join_requests")
            .document(cleanUserId)
            .get()
            .addOnSuccessListener { snapshot ->
                if (handled) return@addOnSuccessListener
                mainHandler.removeCallbacks(timeout)
                handled = true

                if (snapshot.exists()) {
                    val rawStatus = snapshot.getString("status") ?: MemberStatus.PENDING.name
                    val status = try { MemberStatus.valueOf(rawStatus) } catch (_: Exception) { MemberStatus.PENDING }
                    val syncState = if (snapshot.metadata.hasPendingWrites()) SyncState.LOCAL_PENDING_SYNC else SyncState.REMOTE_CONFIRMED
                    saveStoredMemberStatus(context, status)
                    if (status == MemberStatus.APPROVED) {
                        saveStoredFamilyId(context, cleanFamilyId)
                        clearPendingFamilyId(context)
                    }
                    onResult(status, syncState)
                } else {
                    db.collection(COLLECTION_USERS).document(cleanUserId).get()
                        .addOnSuccessListener { uDoc ->
                            val rawStatus = uDoc.getString("membershipStatus") ?: uDoc.getString("status") ?: MemberStatus.PENDING.name
                            val status = try { MemberStatus.valueOf(rawStatus) } catch (_: Exception) { MemberStatus.PENDING }
                            if (status == MemberStatus.APPROVED) {
                                saveStoredFamilyId(context, cleanFamilyId)
                                saveStoredMemberStatus(context, MemberStatus.APPROVED)
                                clearPendingFamilyId(context)
                            }
                            onResult(status, SyncState.REMOTE_CONFIRMED)
                        }
                        .addOnFailureListener {
                            onResult(getStoredMemberStatus(context), SyncState.OFFLINE)
                        }
                }
            }
            .addOnFailureListener {
                if (handled) return@addOnFailureListener
                mainHandler.removeCallbacks(timeout)
                handled = true
                onResult(getStoredMemberStatus(context), SyncState.OFFLINE)
            }
    }

    /**
     * Guardian approves a pending join request.
     * Atomically updates join_requests/{userId}, members/{userId}, and hs_users/{userId}.
     */
    fun approveJoinRequest(
        familyId: String,
        request: FamilyJoinRequest,
        guardianUserId: String = "",
        onComplete: (Boolean) -> Unit
    ) {
        val cleanFamilyId = familyId.trim().uppercase()
        val db = FirebaseSyncManager.getDb()
        if (db == null) {
            onComplete(false)
            return
        }

        val resolvedGuardianId = guardianUserId.ifBlank { FirebaseAuth.getInstance().currentUser?.uid ?: "GUARDIAN" }
        Log.i(TAG_FAMILY, "APPROVAL: Guardian $resolvedGuardianId approving join request for User ${request.userId} in Family $cleanFamilyId")

        val familyRef = db.collection(COLLECTION_FAMILIES).document(cleanFamilyId)

        val reqUpdates = mapOf(
            "status" to MemberStatus.APPROVED.name,
            "reviewedBy" to resolvedGuardianId,
            "reviewedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        )

        val memberPayload = mutableMapOf<String, Any>(
            "userId" to request.userId,
            "familyId" to cleanFamilyId,
            "name" to request.name,
            "displayName" to request.name,
            "email" to request.email,
            "role" to request.role.name,
            "status" to MemberStatus.APPROVED.name,
            "joinedAt" to System.currentTimeMillis(),
            "lastActiveTime" to System.currentTimeMillis(),
            "isOnline" to true
        )
        if (request.childCode.isNotBlank()) {
            memberPayload["childCode"] = request.childCode
        }

        val userPayload = mutableMapOf<String, Any>(
            "userId" to request.userId,
            "familyId" to cleanFamilyId,
            "name" to request.name,
            "displayName" to request.name,
            "email" to request.email,
            "role" to request.role.name,
            "membershipStatus" to MemberStatus.APPROVED.name,
            "status" to MemberStatus.APPROVED.name,
            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        )
        if (request.childCode.isNotBlank()) {
            userPayload["childCode"] = request.childCode
        }

        val batch = db.batch()
        batch.set(familyRef.collection("join_requests").document(request.userId), reqUpdates, SetOptions.merge())
        batch.set(familyRef.collection("members").document(request.userId), memberPayload, SetOptions.merge())
        batch.set(db.collection(COLLECTION_USERS).document(request.userId), userPayload, SetOptions.merge())

        batch.commit()
            .addOnSuccessListener {
                Log.i(TAG_FAMILY, "APPROVAL: Successfully committed atomic approval batch for ${request.userId} in $cleanFamilyId")
                onComplete(true)
            }
            .addOnFailureListener { e ->
                Log.e(TAG_FAMILY, "APPROVAL: Failed to commit approval batch for ${request.userId}", e)
                onComplete(false)
            }
    }

    /**
     * Guardian rejects a pending join request.
     */
    fun rejectJoinRequest(
        familyId: String,
        request: FamilyJoinRequest,
        guardianUserId: String = "",
        onComplete: (Boolean) -> Unit
    ) {
        val cleanFamilyId = familyId.trim().uppercase()
        val db = FirebaseSyncManager.getDb()
        if (db == null) {
            onComplete(false)
            return
        }

        val resolvedGuardianId = guardianUserId.ifBlank { FirebaseAuth.getInstance().currentUser?.uid ?: "GUARDIAN" }
        Log.i(TAG_FAMILY, "APPROVAL: Guardian $resolvedGuardianId rejecting join request for ${request.userId}")

        val familyRef = db.collection(COLLECTION_FAMILIES).document(cleanFamilyId)
        val batch = db.batch()
        batch.set(
            familyRef.collection("join_requests").document(request.userId),
            mapOf(
                "status" to MemberStatus.REJECTED.name,
                "reviewedBy" to resolvedGuardianId,
                "reviewedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        )
        batch.set(
            db.collection(COLLECTION_USERS).document(request.userId),
            mapOf(
                "membershipStatus" to MemberStatus.REJECTED.name,
                "status" to MemberStatus.REJECTED.name,
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        )

        batch.commit()
            .addOnSuccessListener {
                Log.i(TAG_FAMILY, "APPROVAL: Successfully rejected join request for ${request.userId}")
                onComplete(true)
            }
            .addOnFailureListener { e ->
                Log.e(TAG_FAMILY, "APPROVAL: Failed to reject join request for ${request.userId}", e)
                onComplete(false)
            }
    }

    /**
     * Removes a member (e.g. Child) from the family in Cloud Firestore and unpairs them locally.
     * Deletes hs_families/{familyId}/members/{memberUserId} and resets hs_users/{memberUserId}.
     */
    fun removeFamilyMember(
        context: Context,
        familyId: String,
        memberUserId: String,
        childCode: String = "",
        childName: String = "",
        onComplete: (Boolean) -> Unit = {}
    ) {
        val cleanFamilyId = familyId.trim().uppercase()
        val cleanMemberId = memberUserId.trim()
        val cleanChildCode = childCode.trim().uppercase()
        val cleanChildName = childName.trim()

        Log.i(TAG_FAMILY, "REMOVE_MEMBER: familyId=$cleanFamilyId memberId=$cleanMemberId childCode=$cleanChildCode childName=$cleanChildName")

        // 1. Unpair and clean up locally
        if (cleanChildCode.isNotBlank()) {
            ChildIdManager.unpairChild(context, cleanChildCode, childName = cleanChildName)
        }
        if (cleanMemberId.isNotBlank()) {
            ChildIdManager.unpairChild(context, cleanMemberId, childName = cleanChildName)
            ProfileImageManager.clearProfileImage(context, "user_$cleanMemberId")
        }
        if (cleanChildName.isNotBlank()) {
            ChildIdManager.unpairChild(context, "", childName = cleanChildName)
        }
        val remainingMembers = getCachedMembers(context).filter {
            it.userId != cleanMemberId && (cleanChildCode.isBlank() || !it.childCode.equals(cleanChildCode, ignoreCase = true))
        }
        cacheMembers(context, remainingMembers)

        val db = FirebaseSyncManager.getDb()
        if (db == null || cleanFamilyId.isBlank()) {
            onComplete(true)
            return
        }

        val familyRef = db.collection(COLLECTION_FAMILIES).document(cleanFamilyId)

        // 2. Delete member record from hs_families/{familyId}/members/{memberUserId}
        if (cleanMemberId.isNotBlank() && !cleanMemberId.startsWith("HS-", ignoreCase = true)) {
            familyRef.collection("members").document(cleanMemberId).delete()
                .addOnSuccessListener {
                    Log.i(TAG_FAMILY, "REMOVE_MEMBER: Deleted members doc for $cleanMemberId")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG_FAMILY, "REMOVE_MEMBER: Failed to delete members doc for $cleanMemberId: ${e.message}")
                }

            // Also clean up any join_request doc
            familyRef.collection("join_requests").document(cleanMemberId).delete()
                .addOnSuccessListener {
                    Log.i(TAG_FAMILY, "REMOVE_MEMBER: Deleted join_request doc for $cleanMemberId")
                }
                .addOnFailureListener { /* ignored */ }

            // Reset hs_users/{memberUserId} family metadata
            val userReset = mapOf(
                "familyId" to "",
                "membershipStatus" to "NONE",
                "status" to "NONE",
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )
            db.collection(COLLECTION_USERS).document(cleanMemberId).set(userReset, SetOptions.merge())
                .addOnSuccessListener {
                    Log.i(TAG_FAMILY, "REMOVE_MEMBER: Reset hs_users familyId for $cleanMemberId")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG_FAMILY, "REMOVE_MEMBER: Note: hs_users update restricted or offline: ${e.message}")
                }
        }

        // 3. Query and delete any member documents matching childCode in members collection
        if (cleanChildCode.isNotBlank()) {
            familyRef.collection("members")
                .whereEqualTo("childCode", cleanChildCode)
                .get()
                .addOnSuccessListener { snaps ->
                    for (doc in snaps.documents) {
                        doc.reference.delete()
                        val uId = doc.getString("userId") ?: doc.id
                        if (uId.isNotBlank() && uId != cleanMemberId) {
                            db.collection(COLLECTION_USERS).document(uId).set(
                                mapOf("familyId" to "", "membershipStatus" to "NONE", "status" to "NONE"),
                                SetOptions.merge()
                            )
                        }
                    }
                }
                .addOnFailureListener { /* ignored */ }
        }

        // 4. Send cloud notification to child device
        val notif = SystemNotification(
            id = "removed_${cleanMemberId.ifBlank { cleanChildCode }}_${System.currentTimeMillis()}",
            title = "Removed from Family",
            message = "You have been removed from the family.",
            type = NotificationType.CHILD_SAFE_CHECKIN,
            targetRole = "CHILD",
            childCode = cleanChildCode.ifBlank { cleanMemberId },
            actionData = "FAMILY_REMOVED"
        )
        FirebaseSyncManager.sendNotificationToCloud(notif)

        onComplete(true)
    }

    /**
     * Listens for all members of a family in real-time.
     * Automatically performs remote profile picture cache invalidation and fresh download.
     */
    fun listenFamilyMembers(
        context: Context,
        familyId: String,
        onMembersUpdated: (List<FamilyMember>) -> Unit
    ): ListenerRegistration? {
        val cleanFamilyId = familyId.trim().uppercase()
        if (cleanFamilyId.isBlank()) return null
        val db = FirebaseSyncManager.getDb() ?: return null

        val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
        Log.i("HomeSyncFamily", "FAMILY_MEMBERS_LOAD_START familyId=$cleanFamilyId currentUserUid=$currentUid")

        return db.collection(COLLECTION_FAMILIES)
            .document(cleanFamilyId)
            .collection("members")
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e("HomeSyncFamily", "FAMILY_MEMBERS_LOAD_FAILED familyId=$cleanFamilyId error=${error.message}")
                    return@addSnapshotListener
                }
                if (snapshots == null) {
                    Log.w("HomeSyncFamily", "FAMILY_MEMBERS_LOAD_FAILED familyId=$cleanFamilyId error=snapshots_is_null")
                    return@addSnapshotListener
                }

                val list = mutableListOf<FamilyMember>()
                for (doc in snapshots.documents) {
                    val uid = doc.getString("userId")?.takeIf { it.isNotBlank() }
                        ?: doc.getString("firebaseAuthUid")?.takeIf { it.isNotBlank() }
                        ?: doc.id
                    val name = doc.getString("name")?.takeIf { it.isNotBlank() }
                        ?: doc.getString("displayName")?.takeIf { it.isNotBlank() }
                        ?: "Member"
                    val email = doc.getString("email") ?: ""
                    val rawRole = (doc.getString("role") ?: doc.getString("userRole") ?: "").trim()
                    val role: FamilyRole? = when {
                        rawRole.equals("GUARDIAN", ignoreCase = true) -> FamilyRole.GUARDIAN
                        rawRole.equals("CHILD", ignoreCase = true) -> FamilyRole.CHILD
                        else -> null
                    }

                    val rawStatus = (doc.getString("status") ?: doc.getString("membershipStatus") ?: "").trim()
                    val status: MemberStatus? = when {
                        rawStatus.equals("APPROVED", ignoreCase = true) -> MemberStatus.APPROVED
                        rawStatus.equals("PENDING", ignoreCase = true) -> MemberStatus.PENDING
                        rawStatus.equals("REJECTED", ignoreCase = true) -> MemberStatus.REJECTED
                        rawStatus.equals("REMOVED", ignoreCase = true) -> MemberStatus.REMOVED
                        else -> null
                    }

                    if (role == null) {
                        Log.w("HomeSyncFamily", "MALFORMED_MEMBER_RECORD: Skipping member $uid in family $cleanFamilyId due to unknown/malformed role: '$rawRole'")
                        continue
                    }

                    if (status == null) {
                        Log.w("HomeSyncFamily", "MALFORMED_MEMBER_RECORD: Skipping member $uid in family $cleanFamilyId due to unknown/malformed status: '$rawStatus'")
                        continue
                    }

                    val photoUrl = doc.getString("profilePictureUrl") ?: ""
                    val joinedAt = doc.getLong("joinedAt") ?: System.currentTimeMillis()
                    val lastActive = doc.getLong("lastActiveTime") ?: System.currentTimeMillis()
                    val isOnline = doc.getBoolean("isOnline") ?: true
                    val childCode = (doc.getString("childCode") ?: doc.getString("pairingCode") ?: "").trim().uppercase()
                    val phone = doc.getString("phoneNumber") ?: ""

                    if (status == MemberStatus.APPROVED) {
                        list.add(
                            FamilyMember(
                                userId = uid,
                                familyId = cleanFamilyId,
                                name = name,
                                email = email,
                                role = role,
                                status = status,
                                profilePictureUrl = photoUrl,
                                joinedAt = joinedAt,
                                lastActiveTime = lastActive,
                                isOnline = isOnline,
                                childCode = childCode,
                                phoneNumber = phone
                            )
                        )

                        // Authoritative Profile Picture Synchronization (strictly UID-based)
                        ProfileImageManager.setLatestCloudUrl(uid, photoUrl)
                        Log.i(TAG, "PROFILE_MEMBER_CHANGED uid=$uid familyId=$cleanFamilyId role=${role.name} url=$photoUrl")

                        if (photoUrl.isNotBlank()) {
                            val cachedUrl = ProfileImageManager.getCachedPhotoUrl(context, uid)
                            val urlChanged = (cachedUrl != photoUrl)
                            val localBmp = ProfileImageManager.getProfileImage(context, "user_$uid", expectedUrl = photoUrl)
                            val localMissing = (localBmp == null)

                            if (urlChanged) {
                                Log.i(TAG, "PROFILE_URL_CHANGED uid=$uid oldUrl=$cachedUrl newUrl=$photoUrl")
                                Log.i(TAG, "PROFILE_CACHE_INVALIDATED uid=$uid")
                                ProfileImageManager.clearProfileImage(context, "user_$uid")
                            }

                            if (urlChanged || localMissing) {
                                ProfileImageManager.setLatestCloudUrl(uid, photoUrl)
                                FirebaseStorageHelper.downloadAndCachePhoto(context, "user_$uid", photoUrl) { downloadedBmp ->
                                    if (downloadedBmp != null) {
                                        Log.i(TAG, "PROFILE_UI_UPDATED uid=$uid familyId=$cleanFamilyId")
                                        mainHandler.post { onMembersUpdated(list.toList()) }
                                    }
                                }
                            }
                        } else {
                            val cachedUrl = ProfileImageManager.getCachedPhotoUrl(context, uid)
                            if (cachedUrl.isNotBlank()) {
                                Log.i(TAG, "PROFILE_CACHE_INVALIDATED uid=$uid photo_removed")
                                ProfileImageManager.clearProfileImage(context, "user_$uid")
                                Log.i(TAG, "PROFILE_UI_UPDATED uid=$uid familyId=$cleanFamilyId")
                                mainHandler.post { onMembersUpdated(list.toList()) }
                            }
                        }
                    }
                }

                Log.i("HomeSyncFamily", "FAMILY_MEMBERS_LOAD_SUCCESS familyId=$cleanFamilyId currentUserUid=$currentUid member count=${list.size}")
                for (m in list) {
                    Log.i("HomeSyncFamily", "  Member: userId=${m.userId} role=${m.role.name} status=${m.status.name} name=${m.name}")
                }

                cacheMembers(context, list)
                mainHandler.post {
                    onMembersUpdated(list.toList())
                }
            }
    }

    /**
     * Listens for pending join requests for a family (for Guardian view).
     */
    fun listenPendingRequests(
        familyId: String,
        onRequestsUpdated: (List<FamilyJoinRequest>) -> Unit,
        onError: ((String) -> Unit)? = null
    ): ListenerRegistration? {
        val cleanFamilyId = familyId.trim().uppercase()
        if (cleanFamilyId.isBlank()) return null
        val db = FirebaseSyncManager.getDb() ?: return null

        Log.i(TAG_FAMILY, "GUARDIAN: Attaching listenPendingRequests for Family $cleanFamilyId")

        return db.collection(COLLECTION_FAMILIES)
            .document(cleanFamilyId)
            .collection("join_requests")
            .whereEqualTo("status", MemberStatus.PENDING.name)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG_FAMILY, "GUARDIAN: listenPendingRequests error on Family $cleanFamilyId: ${error.code} - ${error.message}", error)
                    val errCodeStr = when (error.code) {
                        FirebaseFirestoreException.Code.PERMISSION_DENIED -> "PERMISSION_DENIED"
                        FirebaseFirestoreException.Code.UNAUTHENTICATED -> "UNAUTHENTICATED"
                        FirebaseFirestoreException.Code.UNAVAILABLE -> "NETWORK_ERROR"
                        else -> "FIREBASE_ERROR: ${error.code}"
                    }
                    onError?.invoke(errCodeStr)
                    return@addSnapshotListener
                }

                if (snapshots == null || snapshots.isEmpty) {
                    Log.d(TAG_FAMILY, "GUARDIAN: Snapshot received: EMPTY_REQUESTS for Family $cleanFamilyId")
                    mainHandler.post { onRequestsUpdated(emptyList()) }
                    return@addSnapshotListener
                }

                Log.i(TAG_FAMILY, "GUARDIAN: Snapshot received: REQUESTS_RECEIVED (${snapshots.size()} requests) for Family $cleanFamilyId")
                val list = mutableListOf<FamilyJoinRequest>()
                for (doc in snapshots.documents) {
                    val reqId = doc.getString("requestId") ?: doc.id
                    val uid = doc.getString("userId") ?: reqId
                    val name = doc.getString("name") ?: doc.getString("displayName") ?: "New Member"
                    val email = doc.getString("email") ?: ""
                    val roleStr = doc.getString("role") ?: FamilyRole.CHILD.name
                    val role = try { FamilyRole.valueOf(roleStr) } catch (_: Exception) { FamilyRole.CHILD }
                    val reqAt = doc.getLong("requestedAt") ?: System.currentTimeMillis()
                    val childCode = (doc.getString("childCode") ?: doc.getString("pairingCode") ?: "").trim().uppercase()

                    list.add(
                        FamilyJoinRequest(
                            requestId = reqId,
                            familyId = cleanFamilyId,
                            userId = uid,
                            name = name,
                            email = email,
                            role = role,
                            status = MemberStatus.PENDING,
                            requestedAt = reqAt,
                            childCode = childCode
                        )
                    )
                }

                mainHandler.post {
                    onRequestsUpdated(list)
                }
            }
    }

    /**
     * Synchronizes a user's updated profile picture URL across the family in Cloud Firestore.
     */
    fun updateMemberProfilePicture(
        familyId: String,
        userId: String,
        photoUrl: String,
        onComplete: (Boolean) -> Unit = {}
    ) {
        val cleanFamilyId = familyId.trim().uppercase()
        val cleanUserId = userId.trim()
        val requesterUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""

        if (cleanUserId.isBlank() || cleanUserId.startsWith("HS-", ignoreCase = true)) {
            Log.e(TAG, "PROFILE_SYNC_FIRESTORE_UPDATE_FAILED requesterUid=$requesterUid targetGuardianUid=$cleanUserId familyId=$cleanFamilyId error=USER_ID_INVALID_OR_PAIRING_CODE")
            onComplete(false)
            return
        }

        val db = FirebaseSyncManager.getDb()
        if (db == null) {
            Log.e(TAG, "PROFILE_SYNC_FIRESTORE_UPDATE_FAILED requesterUid=$requesterUid targetGuardianUid=$cleanUserId familyId=$cleanFamilyId error=FIRESTORE_NULL")
            onComplete(false)
            return
        }

        Log.i(TAG, "PROFILE_SYNC_FIRESTORE_UPDATE_START requesterUid=$requesterUid targetGuardianUid=$cleanUserId familyId=$cleanFamilyId")

        val isCrossMemberUpdate = requesterUid.isNotBlank() && requesterUid != cleanUserId

        if (isCrossMemberUpdate) {
            // Cross-member update (e.g. Child updating Guardian photo):
            // Update ONLY hs_families/{familyId}/members/{targetGuardianUid} with profilePictureUrl.
            // Do NOT touch hs_users/{guardianUid} to prevent cross-user permission denial.
            val memberUpdates = mapOf(
                "profilePictureUrl" to photoUrl
            )
            if (cleanFamilyId.isNotBlank()) {
                db.collection(COLLECTION_FAMILIES)
                    .document(cleanFamilyId)
                    .collection("members")
                    .document(cleanUserId)
                    .set(memberUpdates, SetOptions.merge())
                    .addOnSuccessListener {
                        Log.i(TAG, "PROFILE_SYNC_FIRESTORE_UPDATE_SUCCESS requesterUid=$requesterUid targetGuardianUid=$cleanUserId familyId=$cleanFamilyId")
                        onComplete(true)
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "PROFILE_SYNC_FIRESTORE_UPDATE_FAILED requesterUid=$requesterUid targetGuardianUid=$cleanUserId familyId=$cleanFamilyId error=${e.message}", e)
                        onComplete(false)
                    }
            } else {
                Log.e(TAG, "PROFILE_SYNC_FIRESTORE_UPDATE_FAILED requesterUid=$requesterUid targetGuardianUid=$cleanUserId familyId=$cleanFamilyId error=FAMILY_ID_BLANK")
                onComplete(false)
            }
        } else {
            // Self-update case: (requester UID == target userId)
            // Update both hs_families/{familyId}/members/{userId} and hs_users/{userId}
            val updates = mapOf(
                "profilePictureUrl" to photoUrl,
                "lastActiveTime" to System.currentTimeMillis(),
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )

            val batch = db.batch()
            if (cleanFamilyId.isNotBlank()) {
                batch.set(
                    db.collection(COLLECTION_FAMILIES).document(cleanFamilyId).collection("members").document(cleanUserId),
                    updates,
                    SetOptions.merge()
                )
            }
            batch.set(
                db.collection(COLLECTION_USERS).document(cleanUserId),
                updates,
                SetOptions.merge()
            )

            batch.commit()
                .addOnSuccessListener {
                    Log.i(TAG, "PROFILE_FIRESTORE_UPDATE_SUCCESS userUid=$cleanUserId familyId=$cleanFamilyId photoUrl=$photoUrl")
                    Log.i(TAG, "PROFILE_SYNC_FIRESTORE_UPDATE_SUCCESS requesterUid=$requesterUid targetGuardianUid=$cleanUserId familyId=$cleanFamilyId")
                    onComplete(true)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "PROFILE_SYNC_FIRESTORE_UPDATE_FAILED requesterUid=$requesterUid targetGuardianUid=$cleanUserId familyId=$cleanFamilyId error=${e.message}", e)
                    onComplete(false)
                }
        }
    }

    /**
     * Updates a member's display name canonically in Firestore.
     * Updates both hs_families/{familyId}/members/{userId} and hs_users/{userId}.
     */
    fun updateMemberName(
        familyId: String,
        userId: String,
        name: String,
        onComplete: (Boolean) -> Unit = {}
    ) {
        val cleanFamilyId = familyId.trim().uppercase()
        val cleanUserId = userId.trim()
        val cleanName = name.trim()
        if (cleanFamilyId.isBlank() || cleanUserId.isBlank() || cleanName.isBlank()) {
            onComplete(false)
            return
        }

        val db = FirebaseSyncManager.getDb()
        if (db == null) {
            onComplete(false)
            return
        }

        val updates = mapOf(
            "name" to cleanName,
            "lastActiveTime" to System.currentTimeMillis()
        )

        val batch = db.batch()
        batch.set(
            db.collection(COLLECTION_FAMILIES).document(cleanFamilyId).collection("members").document(cleanUserId),
            updates,
            SetOptions.merge()
        )
        batch.set(
            db.collection(COLLECTION_USERS).document(cleanUserId),
            mapOf(
                "name" to cleanName,
                "updatedAt" to System.currentTimeMillis()
            ),
            SetOptions.merge()
        )

        batch.commit()
            .addOnSuccessListener {
                Log.d(TAG, "Successfully updated member name in Firestore for user $cleanUserId to $cleanName")
                onComplete(true)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to update member name in Firestore", e)
                onComplete(false)
            }
    }


    /**
     * Caches members in local storage for offline resiliency.
     */
    fun cacheMembers(context: Context, members: List<FamilyMember>) {
        try {
            val array = JSONArray()
            for (m in members) {
                val obj = JSONObject().apply {
                    put("userId", m.userId)
                    put("familyId", m.familyId)
                    put("name", m.name)
                    put("email", m.email)
                    put("role", m.role.name)
                    put("status", m.status.name)
                    put("profilePictureUrl", m.profilePictureUrl)
                    put("joinedAt", m.joinedAt)
                    put("phoneNumber", m.phoneNumber)
                }
                array.put(obj)
            }
            getPrefs(context).edit().putString(KEY_CACHED_MEMBERS, array.toString()).apply()
        } catch (_: Exception) {}
    }

    /**
     * Loads locally cached family members.
     */
    fun getCachedMembers(context: Context): List<FamilyMember> {
        val raw = getPrefs(context).getString(KEY_CACHED_MEMBERS, null) ?: return emptyList()
        val list = mutableListOf<FamilyMember>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val role = try { FamilyRole.valueOf(obj.optString("role", "GUARDIAN")) } catch (_: Exception) { FamilyRole.GUARDIAN }
                val status = try { MemberStatus.valueOf(obj.optString("status", "APPROVED")) } catch (_: Exception) { MemberStatus.APPROVED }
                list.add(
                    FamilyMember(
                        userId = obj.optString("userId", ""),
                        familyId = obj.optString("familyId", ""),
                        name = obj.optString("name", "Member"),
                        email = obj.optString("email", ""),
                        role = role,
                        status = status,
                        profilePictureUrl = obj.optString("profilePictureUrl", ""),
                        joinedAt = obj.optLong("joinedAt", System.currentTimeMillis()),
                        phoneNumber = obj.optString("phoneNumber", "")
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    /**
     * Updates the guardian's display name and phone number in Firestore (hs_users & hs_families members).
     */
    fun updateGuardianProfile(
        context: Context,
        familyId: String,
        guardianUid: String,
        displayName: String,
        phoneNumber: String,
        onComplete: (Boolean) -> Unit = {}
    ) {
        val cleanFamilyId = familyId.trim().uppercase().ifBlank { getStoredFamilyId(context) }
        val cleanUid = guardianUid.trim().ifBlank { getStoredUserId(context) }
        val cleanName = displayName.trim()
        val cleanPhone = phoneNumber.trim()

        if (cleanUid.isBlank()) {
            mainHandler.post { onComplete(false) }
            return
        }

        val db = FirebaseSyncManager.getDb()
        val updates = hashMapOf<String, Any>(
            "name" to cleanName,
            "phoneNumber" to cleanPhone,
            "updatedAt" to System.currentTimeMillis()
        )

        // 1. Update hs_users/{guardianUid}
        db?.collection(COLLECTION_USERS)?.document(cleanUid)
            ?.set(updates, SetOptions.merge())

        // 2. Update hs_families/{familyId}/members/{guardianUid}
        if (cleanFamilyId.isNotBlank()) {
            db?.collection(COLLECTION_FAMILIES)?.document(cleanFamilyId)
                ?.collection("members")?.document(cleanUid)
                ?.set(updates, SetOptions.merge())
                ?.addOnSuccessListener {
                    mainHandler.post { onComplete(true) }
                }
                ?.addOnFailureListener {
                    mainHandler.post { onComplete(false) }
                }
        } else {
            mainHandler.post { onComplete(true) }
        }
    }

    /**
     * Registers and updates the user's FCM token in both hs_users/{uid} and hs_families/{familyId}/members/{uid}.
     */
    /**
     * Registers and updates the user's FCM token in both hs_users/{guardianUid}.fcmToken and hs_families/{familyId}/members/{guardianUid}.fcmToken.
     * Strictly binds to FirebaseAuth currentUser UID as the primary identity.
     */
    fun registerFcmToken(context: Context, token: String) {
        val cleanToken = token.trim()
        if (cleanToken.isBlank()) return

        // 1. Firebase Auth UID MUST be the primary identity.
        val authUid = FirebaseAuth.getInstance().currentUser?.uid?.trim() ?: ""
        val uid = if (authUid.isNotBlank()) authUid else getStoredUserId(context).trim()

        // Never register a Guardian's FCM token against local UUID, pairing code, child code, or stale user ID
        if (uid.isBlank() || uid.startsWith("HS-", ignoreCase = true)) {
            Log.w(TAG, "FCM_TOKEN_REGISTER_FAILED reason=invalid_identity uid=$uid")
            return
        }

        val familyId = getStoredFamilyId(context).trim().uppercase()
        val db = FirebaseSyncManager.getDb()
        if (db == null) {
            Log.w(TAG, "FCM_TOKEN_REGISTER_FAILED reason=null_db")
            return
        }

        val updates = hashMapOf<String, Any>(
            "fcmToken" to cleanToken,
            "fcmUpdatedAt" to System.currentTimeMillis()
        )

        val maskedToken = if (cleanToken.length > 12) "${cleanToken.substring(0, 6)}...${cleanToken.substring(cleanToken.length - 4)}" else "masked_token"
        Log.i(TAG, "FCM_TOKEN_REGISTER_START uid=$uid familyId=$familyId token=$maskedToken")

        // 1. Update hs_users/{guardianUid}.fcmToken
        db.collection(COLLECTION_USERS).document(uid)
            .set(updates, SetOptions.merge())
            .addOnSuccessListener {
                Log.i(TAG, "FCM_TOKEN_REGISTER_SUCCESS uid=$uid target=hs_users")
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "FCM_TOKEN_REGISTER_FAILED uid=$uid target=hs_users error=${e.message}")
            }

        // 2. Update hs_families/{familyId}/members/{guardianUid}.fcmToken if user is part of a family
        if (familyId.isNotBlank()) {
            db.collection(COLLECTION_FAMILIES).document(familyId)
                .collection("members").document(uid)
                .set(updates, SetOptions.merge())
                .addOnSuccessListener {
                    Log.i(TAG, "FCM_TOKEN_REGISTER_SUCCESS uid=$uid target=hs_families_members")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "FCM_TOKEN_REGISTER_FAILED uid=$uid target=hs_families_members error=${e.message}")
                }
        }
    }

    /**
     * Obtains the active Firebase Messaging token and registers it immediately.
     */
    fun ensureFcmTokenRegistered(context: Context) {
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (!token.isNullOrBlank()) {
                        val prefs = context.getSharedPreferences("homesync_fcm_prefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("fcm_token", token).apply()
                        registerFcmToken(context, token)
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "FCM_TOKEN_REGISTER_FAILED reason=fetch_token_error error=${e.message}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "FCM_TOKEN_REGISTER_FAILED reason=exception error=${e.message}")
        }
    }

    /**
     * Clears transient family session state on logout without destroying the user's persistent family membership.
     * Membership belongs to the Firebase account and will rehydrate when the account logs in.
     */
    fun clearFamilySession(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_FAMILY_ID)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_ROLE)
            .remove(KEY_MEMBER_STATUS)
            .remove(KEY_CACHED_MEMBERS)
            .remove(KEY_PENDING_FAMILY_ID)
            .apply()
    }
}
