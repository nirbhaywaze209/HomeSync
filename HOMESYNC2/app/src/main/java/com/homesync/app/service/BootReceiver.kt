package com.homesync.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.homesync.app.util.AuthManager
import com.homesync.app.util.FamilyManager
import com.homesync.app.util.FamilyRole

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "HomeSyncBoot"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "BootReceiver triggered with action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val session = AuthManager.getActiveSession(context)
            val storedRole = FamilyManager.getStoredUserRole(context)

            if (session?.screen == "GUARDIAN" || storedRole == FamilyRole.GUARDIAN) {
                Log.i(TAG, "Restarting Guardian monitoring service after boot")
                HomeSyncForegroundService.startForGuardian(context)
            } else if (session?.screen == "CHILD" || storedRole == FamilyRole.CHILD) {
                Log.i(TAG, "Restarting Child safety service after boot")
                HomeSyncForegroundService.startForChild(context)
            }
        }
    }
}
