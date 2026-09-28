package com.homesync.app.util

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth

object IdentityDiagnosticHelper {
    private const val TAG = "HomeSyncDiagnostic"

    /**
     * Prints comprehensive identity and sync path diagnostics required for verification.
     * Must be called whenever ChildHomeScreen or GuardianHomeScreen opens.
     */
    fun printIdentityDiagnostic(
        context: Context,
        screenRole: String,
        explicitChildCode: String = "",
        explicitChildUid: String = ""
    ) {
        val authUser = FirebaseAuth.getInstance().currentUser
        val authUid = authUser?.uid ?: "NONE"
        val familyId = FamilyManager.getStoredFamilyId(context).ifBlank { "NONE" }
        val role = FamilyManager.getStoredUserRole(context).name
        val membership = FamilyManager.getStoredMemberStatus(context).name
        val childCode = explicitChildCode.ifBlank { ChildIdManager.getDeviceChildId(context) }
        val targetUid = explicitChildUid.ifBlank { authUid }

        val taskPath = if (familyId != "NONE" && targetUid != "NONE" && !targetUid.startsWith("HS-")) {
            "hs_families/$familyId/members/$targetUid/tasks"
        } else {
            "hs_quests/$childCode"
        }
        val rtdbPath = "hs_screentime/$childCode"
        val storageBucket = "homesync-app-4cee2.firebasestorage.app"
        val storagePath = "task_proofs/$familyId/${targetUid}_<taskId>_hd.jpg"

        Log.i(TAG, """
            ========== HOMESYNC_IDENTITY_DIAGNOSTIC ($screenRole) ==========
            authUid=$authUid
            childCode=$childCode
            familyId=$familyId
            role=$role
            membership=$membership
            taskPath=$taskPath
            rtdbPath=$rtdbPath
            storageBucket=$storageBucket
            storagePath=$storagePath
            ================================================================
        """.trimIndent())
    }
}
