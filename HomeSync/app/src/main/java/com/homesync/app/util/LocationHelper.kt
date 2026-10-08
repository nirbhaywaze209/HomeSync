package com.homesync.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object LocationHelper {

    private val addressCache = LruCache<String, String>(100)

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun getLastKnownLocation(context: Context): LatLng? {
        if (!hasLocationPermission(context)) return null
        return try {
            val locManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            val gpsLoc = locManager?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            val netLoc = locManager?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            val best = when {
                gpsLoc != null && netLoc != null -> if (gpsLoc.time >= netLoc.time) gpsLoc else netLoc
                gpsLoc != null -> gpsLoc
                else -> netLoc
            }
            if (best != null) LatLng(best.latitude, best.longitude) else null
        } catch (_: SecurityException) {
            null
        }
    }

    fun getAddressFromLocation(context: Context, lat: Double, lng: Double): String {
        return resolveAddress(context, lat, lng)
    }

    private fun buildCacheKey(lat: Double, lng: Double): String {
        return String.format(Locale.US, "%.4f_%.4f", lat, lng)
    }

    /**
     * Gets the latest device location using FusedLocationProviderClient with high accuracy.
     * First attempts getCurrentLocation for an exact live fix, falling back to lastLocation or single request.
     */
    fun fetchCurrentDeviceLocation(
        context: Context,
        onSuccess: (LatLng, String) -> Unit,
        onFailure: (String) -> Unit = {}
    ) {
        if (!hasLocationPermission(context)) {
            onFailure("Location permission not granted")
            return
        }

        val fusedClient = LocationServices.getFusedLocationProviderClient(context)

        try {
            fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { liveLoc ->
                    if (liveLoc != null) {
                        val latLng = LatLng(liveLoc.latitude, liveLoc.longitude)
                        val cachedAddress = addressCache.get(buildCacheKey(liveLoc.latitude, liveLoc.longitude))
                        val fallbackAddress = cachedAddress ?: String.format(Locale.getDefault(), "Lat: %.4f, Lng: %.4f", liveLoc.latitude, liveLoc.longitude)
                        onSuccess(latLng, fallbackAddress)
                    } else {
                        // Fallback to lastLocation if current location returned null
                        fusedClient.lastLocation.addOnSuccessListener { lastLoc ->
                            if (lastLoc != null) {
                                val latLng = LatLng(lastLoc.latitude, lastLoc.longitude)
                                val cachedAddress = addressCache.get(buildCacheKey(lastLoc.latitude, lastLoc.longitude))
                                val fallbackAddress = cachedAddress ?: String.format(Locale.getDefault(), "Lat: %.4f, Lng: %.4f", lastLoc.latitude, lastLoc.longitude)
                                onSuccess(latLng, fallbackAddress)
                            } else {
                                requestSingleUpdate(context, onSuccess, onFailure)
                            }
                        }.addOnFailureListener {
                            requestSingleUpdate(context, onSuccess, onFailure)
                        }
                    }
                }.addOnFailureListener {
                    // Try lastLocation on failure
                    fusedClient.lastLocation.addOnSuccessListener { lastLoc ->
                        if (lastLoc != null) {
                            val latLng = LatLng(lastLoc.latitude, lastLoc.longitude)
                            val cachedAddress = addressCache.get(buildCacheKey(lastLoc.latitude, lastLoc.longitude))
                            val fallbackAddress = cachedAddress ?: String.format(Locale.getDefault(), "Lat: %.4f, Lng: %.4f", lastLoc.latitude, lastLoc.longitude)
                            onSuccess(latLng, fallbackAddress)
                        } else {
                            requestSingleUpdate(context, onSuccess, onFailure)
                        }
                    }.addOnFailureListener { e ->
                        onFailure(e.localizedMessage ?: "Failed to get device location")
                    }
                }
        } catch (e: SecurityException) {
            onFailure("Security exception: ${e.localizedMessage}")
        }
    }

    private fun requestSingleUpdate(
        context: Context,
        onSuccess: (LatLng, String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (!hasLocationPermission(context)) return

        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMaxUpdates(1)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation
                if (loc != null) {
                    val latLng = LatLng(loc.latitude, loc.longitude)
                    val cachedAddress = addressCache.get(buildCacheKey(loc.latitude, loc.longitude))
                    val fallbackAddress = cachedAddress ?: String.format(Locale.getDefault(), "Lat: %.4f, Lng: %.4f", loc.latitude, loc.longitude)
                    onSuccess(latLng, fallbackAddress)
                }
                fusedClient.removeLocationUpdates(this)
            }
        }

        try {
            fusedClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            onFailure(e.localizedMessage ?: "Permission error")
        }
    }

    fun startContinuousLocationUpdates(
        context: Context,
        onUpdate: (LatLng, String) -> Unit
    ): LocationCallback? {
        if (!hasLocationPermission(context)) return null

        val fusedClient = LocationServices.getFusedLocationProviderClient(context)

        // Trigger immediate quick fix while continuous listener warms up
        fetchCurrentDeviceLocation(context, onSuccess = onUpdate, onFailure = {})

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15000L)
            .setMinUpdateIntervalMillis(10000L)
            .setMinUpdateDistanceMeters(10f)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val latLng = LatLng(loc.latitude, loc.longitude)
                val cachedAddress = addressCache.get(buildCacheKey(loc.latitude, loc.longitude))
                val fallbackAddress = cachedAddress ?: String.format(Locale.getDefault(), "Lat: %.4f, Lng: %.4f", loc.latitude, loc.longitude)
                onUpdate(latLng, fallbackAddress)
            }
        }

        try {
            fusedClient.requestLocationUpdates(locationRequest, callback, Looper.getMainLooper())
            return callback
        } catch (e: SecurityException) {
            return null
        }
    }

    fun stopLocationUpdates(context: Context, callback: LocationCallback?) {
        if (callback == null) return
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        fusedClient.removeLocationUpdates(callback)
    }

    /**
     * Resolves human-readable street/neighborhood asynchronously on Dispatchers.IO.
     * Skips network geocoding immediately when offline to prevent freezing and timeouts.
     */
    suspend fun resolveAddressAsync(context: Context, lat: Double, lng: Double): String = withContext(Dispatchers.IO) {
        val key = buildCacheKey(lat, lng)
        addressCache.get(key)?.let { return@withContext it }

        val fallback = String.format(Locale.getDefault(), "Lat: %.4f, Lng: %.4f", lat, lng)
        if (!NetworkHelper.isOnline(context)) {
            return@withContext fallback
        }

        try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            val result = if (!addresses.isNullOrEmpty()) {
                val addr = addresses[0]
                val subLocality = addr.subLocality ?: addr.thoroughfare ?: addr.featureName ?: ""
                val locality = addr.locality ?: addr.adminArea ?: ""
                if (subLocality.isNotBlank() && locality.isNotBlank()) {
                    "$subLocality, $locality"
                } else if (locality.isNotBlank()) {
                    locality
                } else {
                    String.format(Locale.getDefault(), "Lat: %.4f, Lng: %.4f", lat, lng)
                }
            } else {
                String.format(Locale.getDefault(), "Lat: %.4f, Lng: %.4f", lat, lng)
            }
            addressCache.put(key, result)
            return@withContext result
        } catch (e: Exception) {
            val fallback = String.format(Locale.getDefault(), "Lat: %.4f, Lng: %.4f", lat, lng)
            addressCache.put(key, fallback)
            return@withContext fallback
        }
    }

    /**
     * Non-blocking fast address resolver. Returns cached address if available,
     * otherwise returns formatted Lat/Lng string without performing main thread network operations.
     */
    fun resolveAddress(context: Context, lat: Double, lng: Double): String {
        val key = buildCacheKey(lat, lng)
        val cached = addressCache.get(key)
        if (cached != null) return cached
        return String.format(Locale.getDefault(), "Lat: %.4f, Lng: %.4f", lat, lng)
    }
}
