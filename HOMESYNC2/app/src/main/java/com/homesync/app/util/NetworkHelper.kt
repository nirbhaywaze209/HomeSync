package com.homesync.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.firebase.auth.FirebaseAuth

enum class ConnectionStatus {
    NETWORK_AVAILABLE,
    FIREBASE_CONNECTING,
    FIREBASE_ONLINE,
    FIREBASE_OFFLINE,
    AUTHENTICATING,
    AUTHENTICATED,
    PERMISSION_DENIED,
    OTHER_ERROR
}

object NetworkHelper {
    /**
     * Fast, lightweight non-blocking check to determine if the device has an active internet connection.
     */
    fun isOnline(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                     capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                     capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                     capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Resolves the comprehensive connection and authentication status.
     */
    fun getConnectionStatus(context: Context): ConnectionStatus {
        if (!isOnline(context)) {
            return ConnectionStatus.FIREBASE_OFFLINE
        }
        val currentUser = FirebaseAuth.getInstance().currentUser
        return if (currentUser != null) {
            ConnectionStatus.AUTHENTICATED
        } else {
            ConnectionStatus.NETWORK_AVAILABLE
        }
    }
}

