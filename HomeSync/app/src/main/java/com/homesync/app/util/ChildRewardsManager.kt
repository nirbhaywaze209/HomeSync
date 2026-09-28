package com.homesync.app.util

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

data class AvatarItem(
    val id: String,
    val name: String,
    val icon: String,
    val costCoins: Int,
    val description: String
)

data class ChildStats(
    val childId: String,
    val points: Int,
    val coins: Int,
    val level: Int,
    val levelTitle: String,
    val currentLevelPoints: Int,
    val pointsForNextLevel: Int,
    val progressToNextLevel: Float,
    val equippedAvatar: AvatarItem,
    val unlockedAvatarIds: Set<String>,
    val arcadeHighScore: Int,
    val memoryBestTime: Int
)

object ChildRewardsManager {
    private const val TAG = "ChildRewardsManager"
    private const val PREFS_NAME = "homesync_child_rewards_prefs"
    private const val RTDB_REWARDS = "hs_family_rewards"
    private const val COLLECTION_FAMILIES = "hs_families"
    private const val COLLECTION_MEMBERS = "members"
    private const val COLLECTION_REWARDS = "rewards"
    private const val DOC_WALLET = "wallet"

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    val AVAILABLE_AVATARS = listOf(
        AvatarItem("hero_star", "Habit Star", "⭐", 0, "Default Habit Champion"),
        AvatarItem("robopup", "RoboPup", "🐶", 100, "Loyal Eco Companion"),
        AvatarItem("astro_kid", "Astro Kid", "🚀", 250, "Cosmic Space Explorer"),
        AvatarItem("super_hero", "Super Saver", "🦸", 500, "Guardian of Healthy Habits"),
        AvatarItem("magic_unicorn", "Star Unicorn", "🦄", 750, "Magical Dream Achiever"),
        AvatarItem("diamond_king", "Crown Master", "👑", 1000, "Legendary Grand Champion")
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getPointsKey(childId: String) = "points_$childId"
    private fun getCoinsKey(childId: String) = "coins_$childId"
    private fun getEquippedAvatarKey(childId: String) = "equipped_avatar_$childId"
    private fun getUnlockedAvatarsKey(childId: String) = "unlocked_avatars_$childId"
    private fun getHighScoreKey(childId: String) = "high_score_$childId"
    private fun getMemoryBestKey(childId: String) = "memory_best_$childId"
    private fun getMigratedKey(childId: String) = "cloud_wallet_migrated_$childId"

    fun calculateLevelTitle(level: Int): String {
        return when (level) {
            1 -> "Habit Novice"
            2 -> "Habit Explorer"
            3 -> "Eco Champion"
            4 -> "Habit Hero"
            5 -> "Star Master"
            else -> "Legendary Champion"
        }
    }

    fun buildStats(
        childId: String,
        points: Int,
        coins: Int,
        equippedId: String,
        unlockedSet: Set<String>,
        highScore: Int = 0,
        memoryBest: Int = 0
    ): ChildStats {
        val level = (points / 500) + 1
        val levelTitle = calculateLevelTitle(level)
        val currentLevelBasePoints = (level - 1) * 500
        val pointsIntoCurrentLevel = (points - currentLevelBasePoints).coerceAtLeast(0)
        val progress = (pointsIntoCurrentLevel.toFloat() / 500f).coerceIn(0f, 1f)
        val equippedAvatar = AVAILABLE_AVATARS.find { it.id == equippedId } ?: AVAILABLE_AVATARS.first()

        return ChildStats(
            childId = childId,
            points = points,
            coins = coins,
            level = level,
            levelTitle = levelTitle,
            currentLevelPoints = pointsIntoCurrentLevel,
            pointsForNextLevel = 500,
            progressToNextLevel = progress,
            equippedAvatar = equippedAvatar,
            unlockedAvatarIds = unlockedSet,
            arcadeHighScore = highScore,
            memoryBestTime = memoryBest
        )
    }

    fun getStats(context: Context, childId: String): ChildStats {
        val prefs = getPrefs(context)
        val cleanId = childId.ifBlank { "default_child" }

        val points = prefs.getInt(getPointsKey(cleanId), 0)
        val coins = prefs.getInt(getCoinsKey(cleanId), 0)
        val highScore = prefs.getInt(getHighScoreKey(cleanId), 0)
        val memoryBest = prefs.getInt(getMemoryBestKey(cleanId), 0)

        val unlockedSet = prefs.getStringSet(getUnlockedAvatarsKey(cleanId), setOf("hero_star")) ?: setOf("hero_star")
        val equippedId = prefs.getString(getEquippedAvatarKey(cleanId), "hero_star") ?: "hero_star"

        return buildStats(cleanId, points, coins, equippedId, unlockedSet, highScore, memoryBest)
    }

    fun saveStatsToLocalCache(
        context: Context,
        childId: String,
        points: Int,
        coins: Int,
        equippedAvatarId: String,
        unlockedAvatarIds: Set<String>,
        highScore: Int? = null,
        memoryBest: Int? = null
    ) {
        val cleanId = childId.ifBlank { "default_child" }
        val editor = getPrefs(context).edit()
            .putInt(getPointsKey(cleanId), points)
            .putInt(getCoinsKey(cleanId), coins)
            .putString(getEquippedAvatarKey(cleanId), equippedAvatarId)
            .putStringSet(getUnlockedAvatarsKey(cleanId), unlockedAvatarIds)

        if (highScore != null) editor.putInt(getHighScoreKey(cleanId), highScore)
        if (memoryBest != null) editor.putInt(getMemoryBestKey(cleanId), memoryBest)
        editor.apply()
    }

    /**
     * Listens to the authoritative cloud wallet across Firestore and RTDB.
     * Reconciles balance, points, and avatars in real time and keeps local cache synchronized.
     */
    fun listenWallet(
        context: Context,
        familyId: String,
        childUid: String,
        onWalletUpdated: (ChildStats) -> Unit
    ): ListenerRegistration? {
        val cleanFamilyId = familyId.trim().uppercase()
        val cleanChildUid = childUid.trim()

        if (cleanFamilyId.isBlank() || cleanChildUid.isBlank()) {
            return null
        }

        if (cleanChildUid.startsWith("HS-", ignoreCase = true)) {
            Log.e(TAG, "WALLET_IDENTITY_ERROR: Pairing code $cleanChildUid passed to listenWallet (Firebase Auth UID required)")
            return null
        }

        Log.i(TAG, "WALLET_LISTEN_START familyId=$cleanFamilyId childUid=$cleanChildUid")

        val db = FirebaseSyncManager.getDb()
        val rtdb = FirebaseRealtimeSyncManager.getRtdb()

        var rtdbListener: ValueEventListener? = null
        val rtdbRef = rtdb?.getReference(RTDB_REWARDS)?.child(cleanFamilyId)?.child(cleanChildUid)

        if (rtdbRef != null) {
            rtdbListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) return
                    val coins = snapshot.child("coins").getValue(Long::class.java)?.toInt() ?: 0
                    val points = snapshot.child("points").getValue(Long::class.java)?.toInt() ?: 0
                    val equipped = snapshot.child("equippedAvatarId").getValue(String::class.java) ?: "hero_star"
                    val unlockedList = mutableSetOf("hero_star")
                    snapshot.child("unlockedAvatarIds").children.forEach { child ->
                        child.getValue(String::class.java)?.let { unlockedList.add(it) }
                    }

                    saveStatsToLocalCache(context, cleanChildUid, points, coins, equipped, unlockedList)
                    val stats = buildStats(cleanChildUid, points, coins, equipped, unlockedList)
                    mainHandler.post { onWalletUpdated(stats) }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.w(TAG, "RTDB wallet listener cancelled: ${error.message}")
                }
            }
            rtdbRef.addValueEventListener(rtdbListener)
        }

        // Firestore listener
        val docRef = db?.collection(COLLECTION_FAMILIES)
            ?.document(cleanFamilyId)
            ?.collection(COLLECTION_MEMBERS)
            ?.document(cleanChildUid)
            ?.collection(COLLECTION_REWARDS)
            ?.document(DOC_WALLET)

        val firestoreReg = docRef?.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "Firestore wallet listener error: ${error.message}")
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                val coins = snapshot.getLong("coins")?.toInt() ?: 0
                val points = snapshot.getLong("points")?.toInt() ?: 0
                val equipped = snapshot.getString("equippedAvatarId") ?: "hero_star"
                @Suppress("UNCHECKED_CAST")
                val unlockedFromDoc = (snapshot.get("unlockedAvatarIds") as? List<String>)?.toSet() ?: setOf("hero_star")
                val unlockedList = unlockedFromDoc + "hero_star"

                saveStatsToLocalCache(context, cleanChildUid, points, coins, equipped, unlockedList)
                val stats = buildStats(cleanChildUid, points, coins, equipped, unlockedList)
                mainHandler.post { onWalletUpdated(stats) }
            } else if (snapshot != null && !snapshot.exists()) {
                // One-time initialization if document doesn't exist yet
                val prefs = getPrefs(context)
                val isMigrated = prefs.getBoolean(getMigratedKey(cleanChildUid), false)
                if (!isMigrated) {
                    val localCoins = prefs.getInt(getCoinsKey(cleanChildUid), 0)
                    val localPoints = prefs.getInt(getPointsKey(cleanChildUid), 0)
                    val localEquipped = prefs.getString(getEquippedAvatarKey(cleanChildUid), "hero_star") ?: "hero_star"
                    val localUnlocked = prefs.getStringSet(getUnlockedAvatarsKey(cleanChildUid), setOf("hero_star"))?.toList() ?: listOf("hero_star")

                    val initData = hashMapOf<String, Any>(
                        "coins" to localCoins,
                        "points" to localPoints,
                        "level" to ((localPoints / 500) + 1),
                        "levelTitle" to calculateLevelTitle((localPoints / 500) + 1),
                        "equippedAvatarId" to localEquipped,
                        "unlockedAvatarIds" to localUnlocked,
                        "updatedAt" to System.currentTimeMillis()
                    )

                    docRef.set(initData, SetOptions.merge()).addOnSuccessListener {
                        prefs.edit().putBoolean(getMigratedKey(cleanChildUid), true).apply()
                        rtdbRef?.setValue(initData)
                    }
                }
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
     * Atomically adds points and coins to the cloud wallet across Firestore and RTDB.
     * Idempotent: checks transactionId to ensure identical events are credited exactly once.
     */
    fun addRewardsToCloudWallet(
        context: Context,
        familyId: String,
        childUid: String,
        earnedPoints: Int,
        earnedCoins: Int,
        transactionId: String = "",
        onComplete: (Boolean) -> Unit = {}
    ) {
        val cleanFamilyId = familyId.trim().uppercase()
        val cleanChildUid = childUid.trim()

        if (cleanFamilyId.isBlank() || cleanChildUid.isBlank() || (earnedPoints <= 0 && earnedCoins <= 0)) {
            onComplete(false)
            return
        }

        if (cleanChildUid.startsWith("HS-", ignoreCase = true)) {
            Log.e(TAG, "WALLET_IDENTITY_ERROR: Pairing code $cleanChildUid passed to addRewardsToCloudWallet")
            onComplete(false)
            return
        }

        val prefs = getPrefs(context)
        if (transactionId.isNotBlank()) {
            val appliedKey = "tx_applied_$transactionId"
            if (prefs.getBoolean(appliedKey, false)) {
                Log.w(TAG, "WALLET_TX_DUPLICATE_BLOCKED txId=$transactionId")
                onComplete(true)
                return
            }
            prefs.edit().putBoolean(appliedKey, true).apply()
        }

        val now = System.currentTimeMillis()
        Log.i(TAG, "WALLET_INCREMENT familyId=$cleanFamilyId childUid=$cleanChildUid earnedCoins=$earnedCoins earnedPoints=$earnedPoints txId=$transactionId")

        // 1. RTDB atomic transaction
        val rtdb = FirebaseRealtimeSyncManager.getRtdb()
        val rtdbRef = rtdb?.getReference(RTDB_REWARDS)?.child(cleanFamilyId)?.child(cleanChildUid)

        rtdbRef?.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                if (transactionId.isNotBlank() && currentData.child("rewardLedger").child(transactionId).value != null) {
                    return Transaction.abort()
                }

                val currentCoins = currentData.child("coins").getValue(Long::class.java)?.toInt() ?: 0
                val currentPoints = currentData.child("points").getValue(Long::class.java)?.toInt() ?: 0

                val newCoins = currentCoins + earnedCoins
                val newPoints = currentPoints + earnedPoints
                val newLevel = (newPoints / 500) + 1

                currentData.child("coins").value = newCoins
                currentData.child("points").value = newPoints
                currentData.child("level").value = newLevel
                currentData.child("levelTitle").value = calculateLevelTitle(newLevel)
                currentData.child("updatedAt").value = now
                if (transactionId.isNotBlank()) {
                    currentData.child("lastTransactionId").value = transactionId
                    currentData.child("rewardLedger").child(transactionId).value = true
                }
                return Transaction.success(currentData)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?) {
                if (error != null) {
                    Log.w(TAG, "RTDB wallet transaction error: ${error.message}")
                }
            }
        })

        // 2. Firestore atomic update
        val db = FirebaseSyncManager.getDb()
        val docRef = db?.collection(COLLECTION_FAMILIES)
            ?.document(cleanFamilyId)
            ?.collection(COLLECTION_MEMBERS)
            ?.document(cleanChildUid)
            ?.collection(COLLECTION_REWARDS)
            ?.document(DOC_WALLET)

        val updates = hashMapOf<String, Any>(
            "coins" to FieldValue.increment(earnedCoins.toLong()),
            "points" to FieldValue.increment(earnedPoints.toLong()),
            "updatedAt" to now
        )
        if (transactionId.isNotBlank()) {
            updates["lastTransactionId"] = transactionId
            updates["rewardLedger.$transactionId"] = true
        }

        docRef?.set(updates, SetOptions.merge())
            ?.addOnSuccessListener {
                Log.i(TAG, "WALLET_FIRESTORE_INCREMENT_SUCCESS childUid=$cleanChildUid +$earnedCoins coins")
                mainHandler.post { onComplete(true) }
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "WALLET_FIRESTORE_INCREMENT_FAILED childUid=$cleanChildUid: ${e.message}", e)
                mainHandler.post { onComplete(rtdb != null) }
            } ?: mainHandler.post { onComplete(true) }

        // Immediately update local cache
        val currentStats = getStats(context, cleanChildUid)
        val newPoints = currentStats.points + earnedPoints
        val newCoins = currentStats.coins + earnedCoins
        saveStatsToLocalCache(
            context,
            cleanChildUid,
            newPoints,
            newCoins,
            currentStats.equippedAvatar.id,
            currentStats.unlockedAvatarIds
        )
    }

    /**
     * Atomically deducts coins/stars from the cloud wallet for store purchases or reward redemption.
     */
    fun deductCoinsFromCloudWallet(
        context: Context,
        familyId: String,
        childUid: String,
        costCoins: Int,
        itemTitle: String = "",
        onComplete: (Boolean) -> Unit = {}
    ) {
        val cleanFamilyId = familyId.trim().uppercase()
        val cleanChildUid = childUid.trim()

        if (costCoins <= 0) {
            onComplete(true)
            return
        }

        val currentStats = getStats(context, cleanChildUid)
        if (currentStats.coins < costCoins) {
            Toast.makeText(context, "Need ${costCoins - currentStats.coins} more stars to unlock!", Toast.LENGTH_SHORT).show()
            onComplete(false)
            return
        }

        val rtdb = FirebaseRealtimeSyncManager.getRtdb()
        val rtdbRef = rtdb?.getReference(RTDB_REWARDS)?.child(cleanFamilyId)?.child(cleanChildUid)
        val now = System.currentTimeMillis()

        rtdbRef?.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val coins = currentData.child("coins").getValue(Long::class.java)?.toInt() ?: 0
                if (coins < costCoins) {
                    return Transaction.abort()
                }
                currentData.child("coins").value = coins - costCoins
                currentData.child("updatedAt").value = now
                return Transaction.success(currentData)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?) {
                if (committed) {
                    val db = FirebaseSyncManager.getDb()
                    db?.collection(COLLECTION_FAMILIES)
                        ?.document(cleanFamilyId)?.collection(COLLECTION_MEMBERS)?.document(cleanChildUid)
                        ?.collection(COLLECTION_REWARDS)?.document(DOC_WALLET)
                        ?.update("coins", FieldValue.increment(-costCoins.toLong()), "updatedAt", now)

                    val newCoins = (currentStats.coins - costCoins).coerceAtLeast(0)
                    getPrefs(context).edit().putInt(getCoinsKey(cleanChildUid), newCoins).apply()
                    mainHandler.post { onComplete(true) }
                } else {
                    mainHandler.post { onComplete(false) }
                }
            }
        }) ?: run {
            val newCoins = (currentStats.coins - costCoins).coerceAtLeast(0)
            getPrefs(context).edit().putInt(getCoinsKey(cleanChildUid), newCoins).apply()
            mainHandler.post { onComplete(true) }
        }
    }

    /**
     * Atomically deducts coins from the cloud wallet to unlock and equip an avatar.
     */
    fun unlockAndEquipAvatarOnCloud(
        context: Context,
        familyId: String,
        childUid: String,
        avatar: AvatarItem,
        onComplete: (Boolean, String) -> Unit
    ) {
        val cleanFamilyId = familyId.trim().uppercase()
        val cleanChildUid = childUid.trim()

        val currentStats = getStats(context, cleanChildUid)
        if (currentStats.unlockedAvatarIds.contains(avatar.id)) {
            // Already unlocked, equip locally and sync
            getPrefs(context).edit().putString(getEquippedAvatarKey(cleanChildUid), avatar.id).apply()
            val rtdb = FirebaseRealtimeSyncManager.getRtdb()
            rtdb?.getReference(RTDB_REWARDS)?.child(cleanFamilyId)?.child(cleanChildUid)
                ?.child("equippedAvatarId")?.setValue(avatar.id)

            FirebaseSyncManager.getDb()?.collection(COLLECTION_FAMILIES)
                ?.document(cleanFamilyId)?.collection(COLLECTION_MEMBERS)?.document(cleanChildUid)
                ?.collection(COLLECTION_REWARDS)?.document(DOC_WALLET)
                ?.update("equippedAvatarId", avatar.id)

            Toast.makeText(context, "Equipped ${avatar.name}! ${avatar.icon}", Toast.LENGTH_SHORT).show()
            onComplete(true, "EQUIPPED")
            return
        }

        if (currentStats.coins < avatar.costCoins) {
            Toast.makeText(context, "Not enough coins! Need ${avatar.costCoins - currentStats.coins} more 🪙", Toast.LENGTH_SHORT).show()
            onComplete(false, "INSUFFICIENT_FUNDS")
            return
        }

        val newUnlocked = currentStats.unlockedAvatarIds + avatar.id
        val newCoins = currentStats.coins - avatar.costCoins
        saveStatsToLocalCache(context, cleanChildUid, currentStats.points, newCoins, avatar.id, newUnlocked)
        Toast.makeText(context, "🎉 Unlocked & Equipped ${avatar.name}!", Toast.LENGTH_SHORT).show()
        onComplete(true, "SUCCESS")

        val rtdb = FirebaseRealtimeSyncManager.getRtdb()
        val rtdbRef = rtdb?.getReference(RTDB_REWARDS)?.child(cleanFamilyId)?.child(cleanChildUid)
        val now = System.currentTimeMillis()

        rtdbRef?.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                val coins = currentData.child("coins").getValue(Long::class.java)?.toInt() ?: 0
                if (coins < avatar.costCoins) {
                    return Transaction.abort()
                }
                val updatedCoins = coins - avatar.costCoins
                currentData.child("coins").value = updatedCoins
                currentData.child("equippedAvatarId").value = avatar.id

                val currentUnlocked = mutableListOf<String>()
                currentData.child("unlockedAvatarIds").children.forEach {
                    it.getValue(String::class.java)?.let { id -> currentUnlocked.add(id) }
                }
                if (!currentUnlocked.contains(avatar.id)) {
                    currentUnlocked.add(avatar.id)
                }
                currentData.child("unlockedAvatarIds").value = currentUnlocked
                currentData.child("updatedAt").value = now
                return Transaction.success(currentData)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (committed) {
                    val db = FirebaseSyncManager.getDb()
                    val docRef = db?.collection(COLLECTION_FAMILIES)
                        ?.document(cleanFamilyId)?.collection(COLLECTION_MEMBERS)?.document(cleanChildUid)
                        ?.collection(COLLECTION_REWARDS)?.document(DOC_WALLET)

                    docRef?.update(
                        mapOf(
                            "coins" to FieldValue.increment(-avatar.costCoins.toLong()),
                            "equippedAvatarId" to avatar.id,
                            "unlockedAvatarIds" to FieldValue.arrayUnion(avatar.id),
                            "updatedAt" to now
                        )
                    )
                }
            }
        }) ?: run {
            unlockAndEquipAvatar(context, cleanChildUid, avatar)
        }
    }

    /**
     * Local backward compatibility method for game rewards.
     */
    fun addRewards(
        context: Context,
        childId: String,
        earnedPoints: Int,
        earnedCoins: Int,
        gameScore: Int = 0
    ): ChildStats {
        val cleanId = childId.ifBlank { "default_child" }
        val familyId = FamilyManager.getStoredFamilyId(context)

        if (familyId.isNotBlank() && !cleanId.startsWith("HS-", ignoreCase = true)) {
            val txId = "game_${System.currentTimeMillis()}"
            addRewardsToCloudWallet(context, familyId, cleanId, earnedPoints, earnedCoins, txId)
        }

        val prefs = getPrefs(context)
        val currentPoints = prefs.getInt(getPointsKey(cleanId), 0)
        val currentCoins = prefs.getInt(getCoinsKey(cleanId), 0)
        val currentHighScore = prefs.getInt(getHighScoreKey(cleanId), 0)

        val newPoints = currentPoints + earnedPoints
        val newCoins = currentCoins + earnedCoins
        val newHighScore = if (gameScore > currentHighScore) gameScore else currentHighScore

        prefs.edit()
            .putInt(getPointsKey(cleanId), newPoints)
            .putInt(getCoinsKey(cleanId), newCoins)
            .putInt(getHighScoreKey(cleanId), newHighScore)
            .apply()

        return getStats(context, cleanId)
    }

    fun unlockAndEquipAvatar(context: Context, childId: String, avatar: AvatarItem): Boolean {
        val prefs = getPrefs(context)
        val cleanId = childId.ifBlank { "default_child" }
        val currentCoins = prefs.getInt(getCoinsKey(cleanId), 150)
        val unlockedSet = (prefs.getStringSet(getUnlockedAvatarsKey(cleanId), setOf("hero_star")) ?: setOf("hero_star")).toMutableSet()

        if (unlockedSet.contains(avatar.id)) {
            prefs.edit().putString(getEquippedAvatarKey(cleanId), avatar.id).apply()
            Toast.makeText(context, "Equipped ${avatar.name}! ${avatar.icon}", Toast.LENGTH_SHORT).show()
            return true
        }

        if (currentCoins >= avatar.costCoins) {
            val newCoins = currentCoins - avatar.costCoins
            unlockedSet.add(avatar.id)
            prefs.edit()
                .putInt(getCoinsKey(cleanId), newCoins)
                .putStringSet(getUnlockedAvatarsKey(cleanId), unlockedSet)
                .putString(getEquippedAvatarKey(cleanId), avatar.id)
                .apply()

            Toast.makeText(context, "🎉 Unlocked & Equipped ${avatar.name}!", Toast.LENGTH_LONG).show()
            return true
        } else {
            Toast.makeText(context, "Not enough coins! Need ${avatar.costCoins - currentCoins} more 🪙", Toast.LENGTH_SHORT).show()
            return false
        }
    }
}
