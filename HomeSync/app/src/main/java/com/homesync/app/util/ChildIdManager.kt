package com.homesync.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import java.security.SecureRandom

object ChildIdManager {
    private const val PREFS_NAME = "homesync_children_prefs"
    private const val KEY_PREFIX_CHILD_ID = "child_id_"
    private const val KEY_PREFIX_CHILD_NAME = "child_name_"
    private const val KEY_CURRENT_DEVICE_CHILD_ID = "current_device_child_id"
    private const val CHARACTERS = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ" // Excludes I and O to avoid confusion
    private val random = SecureRandom()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Generates a unique 6-character alphanumeric Child ID with the 'HS-' prefix (e.g. HS-849201, HS-7K3M92).
     */
    fun generateUniqueId(): String {
        val codeBuilder = StringBuilder(6)
        for (i in 0 until 6) {
            val randomIndex = random.nextInt(CHARACTERS.length)
            codeBuilder.append(CHARACTERS[randomIndex])
        }
        return "HS-${codeBuilder}"
    }

    /**
     * Cleanly formats any string (including email addresses or raw identifiers) into a proper Human Child Name.
     */
    fun formatChildName(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank() || trimmed.equals("default_child", ignoreCase = true) || trimmed.equals("Active Child", ignoreCase = true) || trimmed.equals("Child", ignoreCase = true)) {
            return "Child"
        }
        if (trimmed.contains("@")) {
            val handle = trimmed.substringBefore("@")
            val cleanLetters = handle.replace(Regex("[0-9_.]"), " ").trim()
            val formatted = cleanLetters.split(" ")
                .filter { it.isNotBlank() }
                .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
            return formatted.ifBlank { handle.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
        }
        return trimmed.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
    }

    /**
     * Gets the clean human name for a given child ID or code.
     * Returns empty string if no child is connected.
     */
    fun getChildName(context: Context, childId: String): String {
        val cleanCode = childId.trim().uppercase()
        val allSaved = getAllSavedChildren(context)
        if (allSaved.isEmpty()) return ""
        
        if (cleanCode.isBlank()) {
            return formatChildName(allSaved.first().first)
        }
        val prefs = getPrefs(context)
        val savedName = prefs.getString("$KEY_PREFIX_CHILD_NAME$cleanCode", null)
        if (!savedName.isNullOrBlank()) {
            return formatChildName(savedName)
        }
        val tuple = allSaved.find { it.second == cleanCode }
        if (tuple != null) {
            return formatChildName(tuple.first)
        }
        return formatChildName(allSaved.first().first)
    }

    /**
     * Retrieves an existing unique ID for the given child identifier,
     * or generates and persists a new unique ID if one does not exist yet.
     */
    fun getOrCreateChildId(context: Context, childIdentifier: String): String {
        val prefs = getPrefs(context)
        val cleanName = formatChildName(childIdentifier)
        val storageKey = "$KEY_PREFIX_CHILD_ID${cleanName.lowercase()}"

        val existingId = prefs.getString(storageKey, null)
        if (!existingId.isNullOrBlank()) {
            return existingId
        }

        var newId: String
        val allValues = prefs.all.values
        do {
            newId = generateUniqueId()
        } while (allValues.contains(newId))

        prefs.edit()
            .putString(storageKey, newId)
            .putString("$KEY_PREFIX_CHILD_NAME$newId", cleanName)
            .putString(KEY_CURRENT_DEVICE_CHILD_ID, newId)
            .apply()

        return newId
    }

    /**
     * Retrieves the latest or active child ID on this device, or generates and stores one if none exists.
     */
    fun getDeviceChildId(context: Context): String {
        val prefs = getPrefs(context)
        val existing = prefs.getString(KEY_CURRENT_DEVICE_CHILD_ID, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val allSaved = getAllSavedChildren(context)
        if (allSaved.isNotEmpty()) {
            val firstCode = allSaved.first().second
            prefs.edit().putString(KEY_CURRENT_DEVICE_CHILD_ID, firstCode).apply()
            return firstCode
        }
        // Auto-generate fresh unique ID so child ID is never blank on fresh install
        val freshId = generateUniqueId()
        prefs.edit().putString(KEY_CURRENT_DEVICE_CHILD_ID, freshId).apply()
        return freshId
    }

    /**
     * Copies the Child ID to the Android clipboard and shows a Toast.
     */
    fun copyIdToClipboard(context: Context, childId: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("HomeSync Child ID", childId)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Copied Child ID: $childId to clipboard", Toast.LENGTH_SHORT).show()
    }

    /**
     * Saves the currently linked / active child ID on this device and ensures a profile exists.
     */
    fun saveLinkedChildId(context: Context, childId: String) {
        val clean = childId.trim().uppercase()
        if (clean.isNotBlank()) {
            val prefs = getPrefs(context)
            prefs.edit().putString(KEY_CURRENT_DEVICE_CHILD_ID, clean).apply()
            val existingName = prefs.getString("$KEY_PREFIX_CHILD_NAME$clean", null)
            if (existingName.isNullOrBlank()) {
                val name = formatChildName("Child " + clean.takeLast(4))
                addChildProfile(context, name, clean)
            }
        }
    }

    /**
     * Checks whether a child code was explicitly unpaired/deleted by the user.
     */
    fun isChildUnpaired(context: Context, code: String): Boolean {
        val cleanCode = code.trim().uppercase()
        if (cleanCode.isBlank()) return false
        val prefs = getPrefs(context)
        val set = (prefs.getStringSet("unpaired_child_codes", emptySet()) ?: emptySet()).map { it.uppercase() }
        return set.contains(cleanCode)
    }

    /**
     * Checks if a name corresponds to a guardian or blacklisted dummy name that should NEVER be stored as a child profile.
     */
    fun isGuardianOrIgnoredName(context: Context, name: String): Boolean {
        val cleanName = formatChildName(name).trim()
        if (cleanName.isBlank() || cleanName.equals("Child", ignoreCase = true)) return false
        if (cleanName.equals("Sarah", ignoreCase = true) || cleanName.contains("sarah@", ignoreCase = true)) return true
        val gName = AuthManager.getGuardianName(context).trim()
        if (gName.isNotBlank() && cleanName.equals(gName, ignoreCase = true)) return true
        val cached = FamilyManager.getCachedMembers(context)
        if (cached.any { it.role == FamilyRole.GUARDIAN && it.name.trim().equals(cleanName, ignoreCase = true) }) return true
        return false
    }

    /**
     * Purges mistaken guardian names and duplicate/stale child codes from local storage,
     * ensuring that the active child's profile is accurately synchronized to currentChildCode.
     */
    fun purgeGuardianAndDuplicateProfiles(
        context: Context,
        guardianName: String = "",
        currentChildName: String = "",
        currentChildCode: String = ""
    ) {
        val prefs = getPrefs(context)
        val editor = prefs.edit()
        val cleanGuardian = formatChildName(guardianName).trim()
        val cleanChild = formatChildName(currentChildName).trim()
        val cleanCode = currentChildCode.trim().uppercase()

        for ((key, value) in prefs.all) {
            val strVal = value as? String ?: ""
            // Remove any child profile associated with the guardian
            val isGuardianKey = (cleanGuardian.isNotBlank() && (
                key.equals("$KEY_PREFIX_CHILD_ID${cleanGuardian.lowercase()}", ignoreCase = true) ||
                strVal.equals(cleanGuardian, ignoreCase = true) ||
                isGuardianOrIgnoredName(context, strVal)
            )) || isGuardianOrIgnoredName(context, key.removePrefix(KEY_PREFIX_CHILD_ID))

            if (isGuardianKey) {
                editor.remove(key)
                continue
            }

            // If a child profile has the same name as currentChildName but a DIFFERENT code, purge that stale duplicate code
            if (cleanChild.isNotBlank() && cleanCode.isNotBlank()) {
                val isStaleNameKey = key.startsWith(KEY_PREFIX_CHILD_NAME) && strVal.equals(cleanChild, ignoreCase = true) && !key.endsWith(cleanCode, ignoreCase = true)
                if (isStaleNameKey) {
                    val staleCode = key.removePrefix(KEY_PREFIX_CHILD_NAME)
                    editor.remove(key)
                    val unpaired = (prefs.getStringSet("unpaired_child_codes", emptySet()) ?: emptySet()).toMutableSet()
                    unpaired.add(staleCode)
                    editor.putStringSet("unpaired_child_codes", unpaired)
                }
            }
        }

        // Accurately map currentChildName to currentChildCode
        if (cleanChild.isNotBlank() && cleanCode.isNotBlank() && !isGuardianOrIgnoredName(context, cleanChild)) {
            editor.putString("$KEY_PREFIX_CHILD_ID${cleanChild.lowercase()}", cleanCode)
            editor.putString("$KEY_PREFIX_CHILD_NAME$cleanCode", cleanChild)
            editor.putString(KEY_CURRENT_DEVICE_CHILD_ID, cleanCode)
        }

        editor.putBoolean("has_initialized_children", true)
        editor.apply()
    }

    /**
     * Adds or updates a child profile with a mandatory clean human name and unique pairing code.
     */
    fun addChildProfile(context: Context, name: String, code: String) {
        val cleanName = formatChildName(name)
        val cleanCode = code.trim().uppercase().ifBlank { generateUniqueId() }
        if (isGuardianOrIgnoredName(context, cleanName)) return
        val prefs = getPrefs(context)

        // If child was previously unpaired, re-enable it on explicit add
        val unpaired = (prefs.getStringSet("unpaired_child_codes", emptySet()) ?: emptySet()).toMutableSet()
        if (unpaired.contains(cleanCode)) {
            unpaired.remove(cleanCode)
            prefs.edit().putStringSet("unpaired_child_codes", unpaired).apply()
        }

        prefs.edit()
            .putString("$KEY_PREFIX_CHILD_ID${cleanName.lowercase()}", cleanCode)
            .putString("$KEY_PREFIX_CHILD_NAME$cleanCode", cleanName)
            .putString(KEY_CURRENT_DEVICE_CHILD_ID, cleanCode)
            .putBoolean("has_initialized_children", true)
            .apply()
    }

    fun saveChildProfile(context: Context, name: String, code: String) {
        addChildProfile(context, name, code)
    }

    /**
     * Saves a sibling / cloud-discovered child profile WITHOUT touching KEY_CURRENT_DEVICE_CHILD_ID.
     * Use this from cloud discovery listeners so we never overwrite the active device's child ID.
     */
    fun addSiblingProfile(context: Context, name: String, code: String) {
        val cleanName = formatChildName(name)
        val cleanCode = code.trim().uppercase().ifBlank { return }
        if (isGuardianOrIgnoredName(context, cleanName)) return
        val prefs = getPrefs(context)

        // Remove from unpaired set if the guardian explicitly re-adds via cloud
        val unpaired = (prefs.getStringSet("unpaired_child_codes", emptySet()) ?: emptySet()).toMutableSet()
        if (unpaired.contains(cleanCode)) {
            unpaired.remove(cleanCode)
            prefs.edit().putStringSet("unpaired_child_codes", unpaired).apply()
        }

        // Save name/code mapping but DO NOT overwrite KEY_CURRENT_DEVICE_CHILD_ID
        prefs.edit()
            .putString("$KEY_PREFIX_CHILD_ID${cleanName.lowercase()}", cleanCode)
            .putString("$KEY_PREFIX_CHILD_NAME$cleanCode", cleanName)
            .putBoolean("has_initialized_children", true)
            .apply()
    }

    /**
     * Updates an existing child profile's display name from cloud data if different.
     */
    fun updateProfileFromCloud(context: Context, childCode: String, name: String) {
        val cleanCode = childCode.trim().uppercase()
        if (cleanCode.isBlank() || isChildUnpaired(context, cleanCode)) return
        val cleanName = formatChildName(name)
        if (cleanName.isBlank() || cleanName.equals("Child", ignoreCase = true)) return

        val prefs = getPrefs(context)
        val currentSavedName = prefs.getString("$KEY_PREFIX_CHILD_NAME$cleanCode", "")
        if (currentSavedName != cleanName) {
            prefs.edit()
                .putString("$KEY_PREFIX_CHILD_ID${cleanName.lowercase()}", cleanCode)
                .putString("$KEY_PREFIX_CHILD_NAME$cleanCode", cleanName)
                .putBoolean("has_initialized_children", true)
                .apply()
        }
    }

    /**
     * Unpairs and completely deletes a child profile from local storage.
     */
    fun unpairChild(context: Context, code: String, childName: String = "") {
        val prefs = getPrefs(context)
        val cleanCode = code.trim().uppercase()
        val cleanName = formatChildName(childName).trim()
        val editor = prefs.edit()

        // Track in unpaired set so cloud listeners don't re-add an explicitly deleted child
        val set = (prefs.getStringSet("unpaired_child_codes", emptySet()) ?: emptySet()).toMutableSet()
        if (cleanCode.isNotBlank()) set.add(cleanCode)
        if (cleanName.isNotBlank()) set.add(cleanName.uppercase())
        editor.putStringSet("unpaired_child_codes", set)

        for ((key, value) in prefs.all) {
            val matchesCode = cleanCode.isNotBlank() && (
                value == cleanCode ||
                (value is String && value.equals(cleanCode, ignoreCase = true)) ||
                (key.startsWith(KEY_PREFIX_CHILD_NAME) && key.endsWith(cleanCode, ignoreCase = true)) ||
                key.endsWith(cleanCode, ignoreCase = true)
            )
            val matchesName = cleanName.isNotBlank() && (
                key.equals("$KEY_PREFIX_CHILD_ID${cleanName.lowercase()}", ignoreCase = true) ||
                (value is String && value.equals(cleanName, ignoreCase = true))
            )
            if (matchesCode || matchesName) {
                editor.remove(key)
            }
        }
        editor.putBoolean("has_initialized_children", true)
        editor.apply()

        val remaining = getAllSavedChildren(context).filter {
            (cleanCode.isBlank() || !it.second.equals(cleanCode, ignoreCase = true)) &&
            (cleanName.isBlank() || !it.first.equals(cleanName, ignoreCase = true))
        }
        val postEditor = prefs.edit()
        if (remaining.isNotEmpty()) {
            postEditor.putString(KEY_CURRENT_DEVICE_CHILD_ID, remaining.first().second)
        } else {
            postEditor.remove(KEY_CURRENT_DEVICE_CHILD_ID)
        }
        postEditor.apply()
    }

    /**
     * Completely removes all child profiles from local storage.
     */
    fun clearAllChildren(context: Context) {
        val prefs = getPrefs(context)
        val editor = prefs.edit()
        for ((key, _) in prefs.all) {
            if (key.startsWith(KEY_PREFIX_CHILD_ID) || key.startsWith(KEY_PREFIX_CHILD_NAME) || key == KEY_CURRENT_DEVICE_CHILD_ID) {
                editor.remove(key)
            }
        }
        editor.remove("unpaired_child_codes")
        editor.putBoolean("has_initialized_children", true)
        editor.apply()

        try {
            val files = context.filesDir.listFiles()
            if (files != null) {
                for (f in files) {
                    if (f.name.startsWith("child_") || f.name.contains("profile_pic.png")) {
                        if (!f.name.startsWith("guardian_")) {
                            f.delete()
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Returns a list of all saved child profiles (Clean Name to Unique ID) on this device.
     */
    fun getAllSavedChildren(context: Context): List<Pair<String, String>> {
        val prefs = getPrefs(context)
        val hasInitialized = prefs.getBoolean("has_initialized_children", false)
        val result = mutableListOf<Pair<String, String>>()
        val seenCodes = mutableSetOf<String>()
        val unpairedCodes = (prefs.getStringSet("unpaired_child_codes", emptySet()) ?: emptySet()).map { it.uppercase() }.toSet()

        val editor = prefs.edit()
        var needsClean = false
        for ((key, value) in prefs.all) {
            if (key.startsWith(KEY_PREFIX_CHILD_ID) && value is String) {
                val rawName = key.removePrefix(KEY_PREFIX_CHILD_ID)
                // Purge any legacy dummy users, accidental Sarah entries, or guardian names
                if (rawName.equals("aarav", ignoreCase = true) || rawName.equals("maya", ignoreCase = true) || rawName.equals("sarah", ignoreCase = true) || rawName.contains("sarah@") || isGuardianOrIgnoredName(context, rawName)) {
                    editor.remove(key)
                    editor.remove("$KEY_PREFIX_CHILD_NAME$value")
                    needsClean = true
                    continue
                }
                val cleanName = formatChildName(rawName)
                val code = value.trim().uppercase()
                if (code.isNotBlank() && !seenCodes.contains(code) && !unpairedCodes.contains(code) && !unpairedCodes.contains(cleanName.uppercase())) {
                    result.add(Pair(cleanName, code))
                    seenCodes.add(code)
                }
            }
        }
        if (needsClean) {
            editor.apply()
        }

        return result
    }

    /**
     * Saves the latest GPS location for a child device.
     */
    fun saveChildLocation(context: Context, childId: String, lat: Double, lng: Double, address: String) {
        val clean = childId.trim().uppercase()
        getPrefs(context).edit()
            .putString("loc_lat_$clean", lat.toString())
            .putString("loc_lng_$clean", lng.toString())
            .putString("loc_addr_$clean", address)
            .putLong("loc_time_$clean", System.currentTimeMillis())
            .apply()
    }

    /**
     * Gets the latest saved GPS location for a child device.
     */
    fun getChildLocation(context: Context, childId: String): Triple<Double, Double, String>? {
        val clean = childId.trim().uppercase()
        val prefs = getPrefs(context)
        val latStr = prefs.getString("loc_lat_$clean", null) ?: return null
        val lngStr = prefs.getString("loc_lng_$clean", null) ?: return null
        val addr = prefs.getString("loc_addr_$clean", "Live Location") ?: "Live Location"
        val lat = latStr.toDoubleOrNull() ?: return null
        val lng = lngStr.toDoubleOrNull() ?: return null
        return Triple(lat, lng, addr)
    }

    /**
     * Resolves the HS pairing code (e.g. HS-849201) for a child given their UID and Name.
     */
    fun resolveChildCode(
        context: Context,
        childUid: String,
        childName: String,
        members: List<FamilyMember> = emptyList()
    ): String {
        // 1. Check FamilyMember childCode
        val cleanUid = childUid.trim()
        val mem = members.firstOrNull { it.userId.equals(cleanUid, ignoreCase = true) }
        if (mem != null && mem.childCode.isNotBlank() && mem.childCode.startsWith("HS-", ignoreCase = true)) {
            return mem.childCode.trim().uppercase()
        }

        // 2. Check local saved children by clean name
        val cleanName = formatChildName(childName)
        val saved = getAllSavedChildren(context)
        val byName = saved.find { it.first.equals(cleanName, ignoreCase = true) }
        if (byName != null && byName.second.startsWith("HS-", ignoreCase = true)) {
            return byName.second.trim().uppercase()
        }

        // 3. If childUid itself is an HS code
        if (cleanUid.startsWith("HS-", ignoreCase = true)) {
            return cleanUid.uppercase()
        }

        // 4. Any saved child with HS- prefix
        val anyHs = saved.firstOrNull { it.second.startsWith("HS-", ignoreCase = true) }
        if (anyHs != null) {
            return anyHs.second.trim().uppercase()
        }

        return getDeviceChildId(context)
    }
}
