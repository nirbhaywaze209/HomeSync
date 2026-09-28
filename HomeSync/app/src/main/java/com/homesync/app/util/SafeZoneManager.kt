package com.homesync.app.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import org.json.JSONArray
import org.json.JSONObject

data class CustomSafeZone(
    val id: String,
    val name: String,
    val icon: String = "🏡",
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 300f,
    val colorHex: String = "#0284C7"
)

object SafeZoneManager {
    private const val TAG = "SafeZoneManager"
    private const val PREFS_NAME = "homesync_user_safezones_prefs"
    private const val KEY_SAFE_ZONES = "custom_safe_zones_list"
    private const val COLLECTION_FAMILIES = "hs_families"
    private const val COLLECTION_SAFE_ZONES = "safe_zones"
    private const val RTDB_SAFE_ZONES = "hs_safe_zones"

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Retrieves all user-created safe zones from local cache.
     */
    fun getSafeZones(context: Context): List<CustomSafeZone> {
        val prefs = getPrefs(context)
        val jsonString = prefs.getString(KEY_SAFE_ZONES, null) ?: return emptyList()

        val list = mutableListOf<CustomSafeZone>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    CustomSafeZone(
                        id = obj.optString("id", System.currentTimeMillis().toString()),
                        name = obj.optString("name", "Safe Zone"),
                        icon = obj.optString("icon", "📍"),
                        latitude = obj.optDouble("latitude", 0.0),
                        longitude = obj.optDouble("longitude", 0.0),
                        radiusMeters = obj.optDouble("radiusMeters", 300.0).toFloat(),
                        colorHex = obj.optString("colorHex", "#0284C7")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    /**
     * Adds a new custom safe zone created by the user with cloud sync across RTDB & Firestore.
     */
    fun addSafeZone(context: Context, zone: CustomSafeZone, familyId: String = "") {
        val existing = getSafeZones(context).toMutableList()
        existing.removeAll { it.id == zone.id }
        existing.add(zone)
        saveList(context, existing)

        val cleanFamilyId = familyId.trim().uppercase().ifBlank { FamilyManager.getStoredFamilyId(context) }
        if (cleanFamilyId.isNotBlank()) {
            val zoneData = hashMapOf<String, Any>(
                "id" to zone.id,
                "name" to zone.name,
                "icon" to zone.icon,
                "latitude" to zone.latitude,
                "longitude" to zone.longitude,
                "radiusMeters" to zone.radiusMeters.toDouble(),
                "colorHex" to zone.colorHex,
                "updatedAt" to System.currentTimeMillis()
            )

            // RTDB
            FirebaseRealtimeSyncManager.getRtdb()?.getReference(RTDB_SAFE_ZONES)
                ?.child(cleanFamilyId)?.child(zone.id)?.setValue(zoneData)

            // Firestore
            FirebaseSyncManager.getDb()?.collection(COLLECTION_FAMILIES)
                ?.document(cleanFamilyId)?.collection(COLLECTION_SAFE_ZONES)
                ?.document(zone.id)?.set(zoneData, SetOptions.merge())
        }
    }

    /**
     * Removes a safe zone by ID with cloud sync across RTDB & Firestore.
     */
    fun deleteSafeZone(context: Context, zoneId: String, familyId: String = "") {
        val existing = getSafeZones(context).filter { it.id != zoneId }
        saveList(context, existing)

        val cleanFamilyId = familyId.trim().uppercase().ifBlank { FamilyManager.getStoredFamilyId(context) }
        if (cleanFamilyId.isNotBlank()) {
            FirebaseRealtimeSyncManager.getRtdb()?.getReference(RTDB_SAFE_ZONES)
                ?.child(cleanFamilyId)?.child(zoneId)?.removeValue()

            FirebaseSyncManager.getDb()?.collection(COLLECTION_FAMILIES)
                ?.document(cleanFamilyId)?.collection(COLLECTION_SAFE_ZONES)
                ?.document(zoneId)?.delete()
        }
    }

    /**
     * Updates an existing safe zone (e.g. radius or name).
     */
    fun updateSafeZone(context: Context, updatedZone: CustomSafeZone, familyId: String = "") {
        addSafeZone(context, updatedZone, familyId)
    }

    /**
     * Listens in real time to family safe zones across RTDB and Firestore.
     */
    fun listenSafeZones(
        context: Context,
        familyId: String,
        onZonesUpdated: (List<CustomSafeZone>) -> Unit
    ): ListenerRegistration? {
        val cleanFamilyId = familyId.trim().uppercase().ifBlank { FamilyManager.getStoredFamilyId(context) }
        if (cleanFamilyId.isBlank()) return null

        val currentZonesMap = mutableMapOf<String, CustomSafeZone>()

        fun notifyMerged() {
            val list = currentZonesMap.values.toList()
            saveList(context, list)
            mainHandler.post { onZonesUpdated(list) }
        }

        // 1. RTDB listener
        var rtdbListener: ValueEventListener? = null
        val rtdbRef = FirebaseRealtimeSyncManager.getRtdb()?.getReference(RTDB_SAFE_ZONES)?.child(cleanFamilyId)
        if (rtdbRef != null) {
            rtdbListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val rtdbZones = mutableListOf<CustomSafeZone>()
                    for (child in snapshot.children) {
                        val id = child.child("id").getValue(String::class.java) ?: child.key ?: continue
                        val name = child.child("name").getValue(String::class.java) ?: "Safe Zone"
                        val icon = child.child("icon").getValue(String::class.java) ?: "🏡"
                        val lat = child.child("latitude").getValue(Double::class.java) ?: 0.0
                        val lng = child.child("longitude").getValue(Double::class.java) ?: 0.0
                        val rad = child.child("radiusMeters").getValue(Double::class.java)?.toFloat() ?: 300f
                        val color = child.child("colorHex").getValue(String::class.java) ?: "#0284C7"

                        rtdbZones.add(CustomSafeZone(id, name, icon, lat, lng, rad, color))
                    }
                    if (snapshot.exists()) {
                        val liveIds = rtdbZones.map { it.id }.toSet()
                        currentZonesMap.keys.retainAll(liveIds)
                        rtdbZones.forEach { currentZonesMap[it.id] = it }
                    }
                    notifyMerged()
                }

                override fun onCancelled(error: DatabaseError) {}
            }
            rtdbRef.addValueEventListener(rtdbListener)
        }

        // 2. Firestore listener
        val firestoreReg = FirebaseSyncManager.getDb()?.collection(COLLECTION_FAMILIES)
            ?.document(cleanFamilyId)?.collection(COLLECTION_SAFE_ZONES)
            ?.addSnapshotListener { snapshot, error ->
                if (snapshot != null && error == null) {
                    val firestoreIds = snapshot.documents.map { it.id }.toSet()
                    currentZonesMap.keys.retainAll(firestoreIds)
                    for (doc in snapshot.documents) {
                        val id = doc.getString("id") ?: doc.id
                        val name = doc.getString("name") ?: "Safe Zone"
                        val icon = doc.getString("icon") ?: "🏡"
                        val lat = doc.getDouble("latitude") ?: 0.0
                        val lng = doc.getDouble("longitude") ?: 0.0
                        val rad = doc.getDouble("radiusMeters")?.toFloat() ?: 300f
                        val color = doc.getString("colorHex") ?: "#0284C7"
                        currentZonesMap[id] = CustomSafeZone(id, name, icon, lat, lng, rad, color)
                    }
                    notifyMerged()
                }
            }

        return object : ListenerRegistration {
            override fun remove() {
                try { firestoreReg?.remove() } catch (_: Exception) {}
                try {
                    if (rtdbRef != null && rtdbListener != null) {
                        rtdbRef.removeEventListener(rtdbListener)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Formats distance in meters if under 1km (e.g. '450m'), or in km if >= 1km (e.g. '1.2 km').
     */
    fun formatDistance(meters: Float): String {
        val rounded = meters.toInt().coerceAtLeast(0)
        return if (rounded < 1000) {
            "${rounded}m"
        } else {
            val km = rounded / 1000f
            if (km % 1.0f == 0.0f) {
                "${km.toInt()} km"
            } else {
                String.format(java.util.Locale.US, "%.1f km", km)
            }
        }
    }

    /**
     * Formats distance with full word for meters if under 1km (e.g. '450 meters'), or in km (e.g. '1.5 km').
     */
    fun formatDistanceLong(meters: Float): String {
        val rounded = meters.toInt().coerceAtLeast(0)
        return if (rounded < 1000) {
            "$rounded meters"
        } else {
            val km = rounded / 1000f
            if (km % 1.0f == 0.0f) {
                "${km.toInt()} km"
            } else {
                String.format(java.util.Locale.US, "%.1f km", km)
            }
        }
    }

    fun calculateDistanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val results = FloatArray(1)
        try {
            android.location.Location.distanceBetween(lat1, lng1, lat2, lng2, results)
            return results[0]
        } catch (_: Exception) {
            return 0f
        }
    }

    /**
     * Checks if a coordinate is inside any user-defined safe zone.
     */
    fun checkChildSafetyStatus(childLat: Double, childLng: Double, zones: List<CustomSafeZone>): Pair<Boolean, String> {
        if (zones.isEmpty()) {
            return Pair(false, "No Safe Zone configured (Guardian must add a Safe Zone)")
        }

        var nearestZone: CustomSafeZone? = null
        var minDistance = Float.MAX_VALUE

        for (zone in zones) {
            val dist = calculateDistanceMeters(childLat, childLng, zone.latitude, zone.longitude)
            if (dist <= zone.radiusMeters) {
                val formattedDist = formatDistance(dist)
                return Pair(true, "🟢 Inside Safe Zone: ${zone.icon} ${zone.name} ($formattedDist from center)")
            }
            if (dist < minDistance) {
                minDistance = dist
                nearestZone = zone
            }
        }

        val nearest = nearestZone
        return if (nearest != null) {
            val outsideDist = (minDistance - nearest.radiusMeters).coerceAtLeast(0f)
            val formattedOutside = formatDistance(outsideDist)
            Pair(false, "⚠️ Outside Safe Zones ($formattedOutside from ${nearest.icon} ${nearest.name})")
        } else {
            Pair(false, "⚠️ Outside Safe Zones")
        }
    }

    private fun saveList(context: Context, list: List<CustomSafeZone>) {
        val jsonArray = JSONArray()
        for (zone in list) {
            val obj = JSONObject()
            obj.put("id", zone.id)
            obj.put("name", zone.name)
            obj.put("icon", zone.icon)
            obj.put("latitude", zone.latitude)
            obj.put("longitude", zone.longitude)
            obj.put("radiusMeters", zone.radiusMeters.toDouble())
            obj.put("colorHex", zone.colorHex)
            jsonArray.put(obj)
        }
        getPrefs(context).edit().putString(KEY_SAFE_ZONES, jsonArray.toString()).apply()
    }
}
