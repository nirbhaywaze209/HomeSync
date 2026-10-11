package com.homesync.app.util

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth
import androidx.credentials.CredentialManager
import androidx.credentials.ClearCredentialStateRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import android.util.Log

data class UserAccount(
    val email: String,
    val password: String = "",
    val name: String,
    val role: String = "GUARDIAN",
    val childCode: String = ""
)

data class ActiveSession(
    val name: String,
    val email: String,
    val age: Int,
    val screen: String, // "GUARDIAN" or "CHILD" or "FAMILY_SETUP"
    val childId: String = "",
    val linkedChildCode: String = "",
    val familyId: String = "",
    val userId: String = ""
)

object AuthManager {
    private const val TAG = "HomeSyncAuth"
    private const val PREFS_NAME = "homesync_auth_accounts_prefs"
    private const val KEY_LAST_EMAIL = "last_logged_in_email"
    private const val KEY_LAST_NAME = "last_logged_in_name"
    private const val KEY_LAST_ROLE = "last_logged_in_role"
    private const val KEY_LAST_CHILD_CODE = "last_logged_in_child_code"

    // Active session keys
    private const val KEY_SESSION_ACTIVE = "session_is_active"
    private const val KEY_SESSION_NAME = "session_user_name"
    private const val KEY_SESSION_EMAIL = "session_user_email"
    private const val KEY_SESSION_AGE = "session_user_age"
    private const val KEY_SESSION_SCREEN = "session_target_screen"
    private const val KEY_SESSION_CHILD_ID = "session_child_id"
    private const val KEY_SESSION_LINKED_CODE = "session_linked_code"
    private const val KEY_SESSION_FAMILY_ID = "session_family_id"
    private const val KEY_SESSION_USER_ID = "session_user_id"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun cleanEmail(email: String): String = email.trim().lowercase()

    /**
     * Canonical Firebase UID helper. Returns the current authenticated UID or null.
     */
    fun getCanonicalFirebaseUid(): String? {
        return FirebaseAuth.getInstance().currentUser?.uid
    }

    /**
     * Deprecated: Firebase Authentication is the sole authority for registered accounts.
     */
    @Deprecated("Firebase Authentication is the sole authority.")
    fun isEmailRegistered(context: Context, email: String): Boolean {
        return false
    }

    /**
     * Records last logged-in account metadata without storing plaintext passwords.
     */
    fun registerAccount(
        context: Context,
        account: UserAccount
    ): Result<UserAccount> {
        val clean = cleanEmail(account.email)
        val prefs = getPrefs(context)
        prefs.edit()
            .putString(KEY_LAST_EMAIL, clean)
            .putString(KEY_LAST_NAME, account.name)
            .putString(KEY_LAST_ROLE, account.role)
            .putString(KEY_LAST_CHILD_CODE, account.childCode)
            .apply()

        return Result.success(account.copy(password = ""))
    }

    /**
     * Deprecated: Local authentication has been completely removed.
     * All authentication must go through FirebaseAuth.
     */
    @Deprecated("Do not use local authentication. Firebase Authentication is the sole authority.")
    fun authenticate(
        context: Context,
        email: String,
        passwordInput: String
    ): Result<UserAccount> {
        return Result.failure(Exception("Local password authentication disabled. Use Firebase Authentication."))
    }

    /**
     * Saves the currently active session so user remains logged in across app restarts.
     */
    fun saveActiveSession(context: Context, session: ActiveSession) {
        val prefs = getPrefs(context)
        val editor = prefs.edit()
            .putBoolean(KEY_SESSION_ACTIVE, true)
            .putString(KEY_SESSION_NAME, session.name)
            .putString(KEY_SESSION_EMAIL, session.email)
            .putInt(KEY_SESSION_AGE, session.age)
            .putString(KEY_SESSION_SCREEN, session.screen)
            .putString(KEY_SESSION_CHILD_ID, session.childId)
            .putString(KEY_SESSION_LINKED_CODE, session.linkedChildCode)
            .putString(KEY_SESSION_FAMILY_ID, session.familyId)
            .putString(KEY_SESSION_USER_ID, session.userId)

        if (session.screen.equals("GUARDIAN", ignoreCase = true) && session.name.isNotBlank() && session.name != "User") {
            editor.putString("saved_guardian_name", session.name)
        }
        editor.apply()
    }

    /**
     * Returns true if the given name is a Sarah variant (e.g. "Sarah", "Sarah (Guardian)", "sarah@...").
     */
    fun isSarahName(name: String): Boolean =
        name.trim().lowercase().let { it == "sarah" || it.startsWith("sarah ") || it.startsWith("sarah(") || it.contains("sarah@") }

    /**
     * Gets the accurate saved Guardian name across both Guardian and Child UIs.
     * Returns empty string if no guardian has been paired or logged in yet.
     */
    fun getGuardianName(context: Context): String {
        val prefs = getPrefs(context)
        val saved = prefs.getString("saved_guardian_name", null)
        // Purge any hardcoded legacy Sarah variant that was previously stored
        if (!saved.isNullOrBlank() && isSarahName(saved)) {
            prefs.edit().remove("saved_guardian_name").apply()
        } else if (!saved.isNullOrBlank() && saved != "User" && saved != "Child") {
            return saved
        }
        val active = getActiveSession(context)
        if (active != null && active.screen.equals("GUARDIAN", ignoreCase = true) && active.name.isNotBlank() && active.name != "User" && !isSarahName(active.name)) {
            prefs.edit().putString("saved_guardian_name", active.name).apply()
            return active.name
        }
        val lastAcc = getLastLoggedInAccount(context)
        if (lastAcc != null && lastAcc.name.isNotBlank() && lastAcc.name != "User" && !isSarahName(lastAcc.name)) {
            prefs.edit().putString("saved_guardian_name", lastAcc.name).apply()
            return lastAcc.name
        }
        return "Guardian"
    }



    /**
     * Saves the Guardian name persistently across devices and screens.
     */
    fun saveGuardianName(context: Context, name: String) {
        val clean = name.trim()
        if (clean.isNotBlank() && clean != "User" && clean != "Child" && !isSarahName(clean)) {
            getPrefs(context).edit().putString("saved_guardian_name", clean).apply()
            try {
                val devCode = ChildIdManager.getDeviceChildId(context)
                if (devCode.isNotBlank()) {
                    FirebaseRealtimeSyncManager.syncGuardianProfile(context, clean, devCode)
                }
                val allChildren = ChildIdManager.getAllSavedChildren(context)
                for (child in allChildren) {
                    if (child.second.isNotBlank()) {
                        FirebaseRealtimeSyncManager.syncGuardianProfile(context, clean, child.second)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Gets the saved Guardian phone number.
     */
    fun getGuardianPhone(context: Context): String {
        return getPrefs(context).getString("saved_guardian_phone", "") ?: ""
    }

    /**
     * Saves the Guardian phone number persistently.
     */
    fun saveGuardianPhone(context: Context, phone: String) {
        val clean = phone.trim()
        if (clean.isNotBlank()) {
            getPrefs(context).edit().putString("saved_guardian_phone", clean).apply()
        }
    }


    /**
     * Retrieves the currently active session (if logged in).
     */
    fun getActiveSession(context: Context): ActiveSession? {
        val prefs = getPrefs(context)
        val isActive = prefs.getBoolean(KEY_SESSION_ACTIVE, false)
        if (!isActive) return null

        val name = prefs.getString(KEY_SESSION_NAME, "User") ?: "User"
        val email = prefs.getString(KEY_SESSION_EMAIL, "") ?: ""
        val age = prefs.getInt(KEY_SESSION_AGE, 0)
        val screen = prefs.getString(KEY_SESSION_SCREEN, "GUARDIAN") ?: "GUARDIAN"
        val childId = prefs.getString(KEY_SESSION_CHILD_ID, "") ?: ""
        val linkedCode = prefs.getString(KEY_SESSION_LINKED_CODE, "") ?: ""
        val familyId = prefs.getString(KEY_SESSION_FAMILY_ID, "") ?: ""
        val userId = prefs.getString(KEY_SESSION_USER_ID, "") ?: ""

        return ActiveSession(
            name = name,
            email = email,
            age = age,
            screen = screen,
            childId = childId,
            linkedChildCode = linkedCode,
            familyId = familyId,
            userId = userId
        )
    }

    /**
     * Clears the active session on logout.
     */
    fun clearActiveSession(context: Context) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putBoolean(KEY_SESSION_ACTIVE, false)
            .remove(KEY_SESSION_NAME)
            .remove(KEY_SESSION_EMAIL)
            .remove(KEY_SESSION_AGE)
            .remove(KEY_SESSION_SCREEN)
            .remove(KEY_SESSION_CHILD_ID)
            .remove(KEY_SESSION_LINKED_CODE)
            .remove(KEY_SESSION_FAMILY_ID)
            .remove(KEY_SESSION_USER_ID)
            .remove(KEY_LAST_EMAIL)
            .remove(KEY_LAST_NAME)
            .remove(KEY_LAST_ROLE)
            .remove(KEY_LAST_CHILD_CODE)
            .apply()

        Log.i(TAG, "AUTH_LOGOUT")
        try {
            FirebaseAuth.getInstance().signOut()
        } catch (_: Exception) {}

        try {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val credentialManager = CredentialManager.create(context)
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /**
     * Updates the linked child code for the active session and remembers it.
     */
    fun updateLinkedChildCode(context: Context, code: String) {
        val clean = code.trim().uppercase()
        val prefs = getPrefs(context)
        prefs.edit()
            .putString(KEY_SESSION_LINKED_CODE, clean)
            .putString(KEY_LAST_CHILD_CODE, clean)
            .apply()
    }

    /**
     * Retrieves the last logged-in account metadata (if available).
     */
    fun getLastLoggedInAccount(context: Context): UserAccount? {
        val prefs = getPrefs(context)
        val lastEmail = prefs.getString(KEY_LAST_EMAIL, null) ?: return null
        val savedName = prefs.getString(KEY_LAST_NAME, "Guardian") ?: "Guardian"
        val savedRole = prefs.getString(KEY_LAST_ROLE, "GUARDIAN") ?: "GUARDIAN"
        val savedChildCode = prefs.getString(KEY_LAST_CHILD_CODE, "") ?: ""

        return UserAccount(
            email = lastEmail,
            password = "",
            name = savedName,
            role = savedRole,
            childCode = savedChildCode
        )
    }

    /**
     * Deprecated: Password updates are handled exclusively by Firebase Authentication.
     */
    @Deprecated("Use FirebaseAuth.sendPasswordResetEmail.")
    fun updatePassword(context: Context, email: String, newPassword: String): Boolean {
        return true
    }

    /**
     * Cleans up legacy demo accounts and legacy password keys if present.
     */
    fun ensureDemoAccounts(context: Context) {
        val prefs = getPrefs(context)
        val editor = prefs.edit()
        
        // Purge any legacy plaintext user account keys from previous versions
        for (key in prefs.all.keys) {
            if (key.startsWith("user_account_")) {
                editor.remove(key)
            }
        }

        val lastEmail = prefs.getString(KEY_LAST_EMAIL, null)
        if (lastEmail.equals("sarah@homesync.app", ignoreCase = true)) {
            editor.remove(KEY_LAST_EMAIL)
        }
        val savedName = prefs.getString("saved_guardian_name", null)
        if (savedName.equals("Sarah", ignoreCase = true)) {
            editor.remove("saved_guardian_name")
        }
        val sessionName = prefs.getString(KEY_SESSION_NAME, null)
        if (sessionName.equals("Sarah", ignoreCase = true)) {
            editor.remove(KEY_SESSION_NAME)
        }
        editor.apply()
    }
}
