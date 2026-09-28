package com.homesync.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object FirebaseStorageHelper {
    private const val TAG = "FirebaseStorageHelper"

    private fun getStorage(): FirebaseStorage? {
        return try {
            FirebaseStorage.getInstance("gs://homesync-app-4cee2.firebasestorage.app")
        } catch (e: Exception) {
            try {
                FirebaseStorage.getInstance()
            } catch (e2: Exception) {
                Log.e(TAG, "Error obtaining Firebase Storage instance", e2)
                null
            }
        }
    }

    /**
     * Encodes a Bitmap to HD JPEG byte array (90% quality, max 1600px dimension).
     */
    fun bitmapToHdBytes(bitmap: Bitmap): ByteArray {
        val maxDimension = 1600
        val scaled = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val width = if (ratio > 1) maxDimension else (maxDimension * ratio).toInt()
            val height = if (ratio > 1) (maxDimension / ratio).toInt() else maxDimension
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        } else {
            bitmap
        }

        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        return baos.toByteArray()
    }

    /**
     * Uploads a canonical profile photo to Firebase Cloud Storage at:
     * profile_photos/{userId}/profile.jpg
     *
     * Strictly does NOT fallback to Base64 on failure.
     */
    fun uploadCanonicalProfilePhoto(
        context: Context,
        userId: String,
        bitmap: Bitmap,
        familyId: String = "",
        onComplete: (photoUrl: String, errorStatus: String?) -> Unit
    ) {
        val cleanUserId = userId.trim()
        if (cleanUserId.isBlank() || cleanUserId.startsWith("HS-", ignoreCase = true)) {
            val err = "STORAGE_IDENTITY_ERROR: uploadCanonicalProfilePhoto rejected invalid userId=$cleanUserId (pairing code or blank)"
            Log.e(TAG, err)
            onComplete("", "INVALID_USER_ID")
            return
        }

        val effFamilyId = if (familyId.isNotBlank()) familyId.trim().uppercase() else FamilyManager.getStoredFamilyId(context).trim().uppercase()
        val path = "profile_photos/$cleanUserId/profile.jpg"
        val timestamp = System.currentTimeMillis()
        Log.i(TAG, "PROFILE_UPLOAD_START uid=$cleanUserId timestamp=$timestamp path=$path familyId=$effFamilyId")

        val storage = getStorage()
        if (storage == null) {
            val err = "STORAGE_UNAVAILABLE"
            Log.e(TAG, "PROFILE_UPLOAD_FAILED uid=$cleanUserId timestamp=${System.currentTimeMillis()} error=$err")
            onComplete("", err)
            return
        }

        val storageRef = storage.reference.child(path)
        val bytes = bitmapToHdBytes(bitmap)

        val metadata = StorageMetadata.Builder()
            .setContentType("image/jpeg")
            .build()

        storageRef.putBytes(bytes, metadata)
            .addOnSuccessListener {
                val storageSuccessTime = System.currentTimeMillis()
                Log.i(TAG, "PROFILE_UPLOAD_STORAGE_SUCCESS uid=$cleanUserId timestamp=$storageSuccessTime")
                storageRef.downloadUrl.addOnSuccessListener { uri ->
                    val rawUrl = uri.toString()
                    val updateTimestamp = System.currentTimeMillis()
                    val photoUrl = if (rawUrl.contains("?")) "$rawUrl&v=$updateTimestamp" else "$rawUrl?v=$updateTimestamp"

                    // Persist resulting URL to authoritative Firestore profile/member record
                    FamilyManager.updateMemberProfilePicture(effFamilyId, cleanUserId, photoUrl) { success ->
                        val firestoreSuccessTime = System.currentTimeMillis()
                        if (success) {
                            Log.i(TAG, "PROFILE_UPLOAD_FIRESTORE_SUCCESS uid=$cleanUserId timestamp=$firestoreSuccessTime")
                            ProfileImageManager.saveProfileImage(context, bitmap, key = "user_$cleanUserId", photoUrl = photoUrl)
                            ProfileImageManager.saveCachedPhotoUrl(context, cleanUserId, photoUrl)
                            onComplete(photoUrl, null)
                        } else {
                            Log.e(TAG, "PROFILE_UPLOAD_FIRESTORE_FAILED uid=$cleanUserId timestamp=$firestoreSuccessTime")
                            onComplete("", "FIRESTORE_UPDATE_FAILED")
                        }
                    }
                }.addOnFailureListener { e ->
                    val err = e.localizedMessage ?: "DOWNLOAD_URL_FAILED"
                    Log.e(TAG, "PROFILE_UPLOAD_DOWNLOAD_URL_FAILED uid=$cleanUserId timestamp=${System.currentTimeMillis()} error=$err", e)
                    onComplete("", err)
                }
            }
            .addOnFailureListener { e ->
                val err = e.localizedMessage ?: "UPLOAD_FAILED"
                Log.e(TAG, "PROFILE_UPLOAD_STORAGE_FAILED uid=$cleanUserId timestamp=${System.currentTimeMillis()} error=$err", e)
                onComplete("", err)
            }
    }

    /**
     * Backwards-compatible redirect for legacy profile photo upload callers.
     */
    @Deprecated("Use uploadCanonicalProfilePhoto with canonical userId", ReplaceWith("uploadCanonicalProfilePhoto(context, key, bitmap) { url, _ -> onComplete(url) }"))
    fun uploadProfilePhoto(
        context: Context,
        key: String,
        bitmap: Bitmap,
        onComplete: (photoUrl: String) -> Unit
    ) {
        uploadCanonicalProfilePhoto(context, key, bitmap) { photoUrl, _ ->
            onComplete(photoUrl)
        }
    }

    /**
     * Uploads an HD Task Proof Photo to Firebase Cloud Storage under the rule-authorized path:
     * task_proofs/{familyId}/{childUserId}_{questId}_hd.jpg
     * Strictly does NOT fallback to Base64 on failure.
     */
    fun uploadTaskProofPhoto(
        context: Context,
        familyId: String,
        childUserId: String,
        questId: String,
        bitmap: Bitmap,
        onComplete: (photoUrl: String, error: String?) -> Unit
    ) {
        val authUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        val authUid = authUser?.uid?.trim() ?: ""
        if (authUser == null || authUid.isBlank()) {
            val err = "AUTH_REQUIRED: User must be signed in with Firebase Auth"
            Log.e(TAG, "STORAGE_IDENTITY_ERROR: $err")
            onComplete("", err)
            return
        }

        val cleanFamilyId = familyId.trim().uppercase()
        if (cleanFamilyId.isBlank()) {
            val err = "FAMILY_REQUIRED: familyId must not be empty"
            Log.e(TAG, "STORAGE_IDENTITY_ERROR: $err")
            onComplete("", err)
            return
        }

        val cleanTaskId = questId.trim()
        if (cleanTaskId.isBlank()) {
            val err = "TASK_REQUIRED: questId must not be empty"
            Log.e(TAG, "STORAGE_IDENTITY_ERROR: $err")
            onComplete("", err)
            return
        }

        // Canonical childUserId is strictly Firebase Auth UID (never pairing code)
        val canonicalChildUserId = if (authUid.isNotBlank()) authUid else childUserId.trim()
        if (canonicalChildUserId.isBlank() || canonicalChildUserId.startsWith("HS-", ignoreCase = true)) {
            val err = "INVALID_AUTH_UID: Pairing code or blank UID cannot be used as childUserId (canonicalChildUserId=$canonicalChildUserId)"
            Log.e(TAG, "STORAGE_IDENTITY_ERROR: $err")
            onComplete("", err)
            return
        }

        val storagePath = "task_proofs/$cleanFamilyId/${canonicalChildUserId}_${cleanTaskId}_hd.jpg"

        Log.i(TAG, "STORAGE_IDENTITY familyId=$cleanFamilyId firebaseUid=$canonicalChildUserId path=$storagePath operation=UPLOAD_TASK_PROOF")
        Log.i(TAG, "TASK_PROOF_UPLOAD_START familyId=$cleanFamilyId childUserId=$canonicalChildUserId taskId=$cleanTaskId")

        val storage = getStorage()
        if (storage == null) {
            val err = "STORAGE_UNAVAILABLE"
            Log.e(TAG, "TASK_PROOF_UPLOAD_FAILED taskId=$cleanTaskId error=$err")
            onComplete("", err)
            return
        }

        try {
            val storageRef = storage.reference.child(storagePath)
            val bytes = bitmapToHdBytes(bitmap)
            val metadata = StorageMetadata.Builder()
                .setContentType("image/jpeg")
                .build()

            Log.i(TAG, "TASK_PROOF_STORAGE_BUCKET bucket=${storage.reference.bucket}")
            Log.i(TAG, "TASK_PROOF_STORAGE_PATH storagePath=$storagePath")
            Log.i(TAG, "TASK_PROOF_BYTES bytes=${bytes.size}")
            Log.i(TAG, "TASK_PROOF_STORAGE_PUT_START taskId=$cleanTaskId")

            storageRef.putBytes(bytes, metadata).addOnSuccessListener {
                Log.i(TAG, "TASK_PROOF_STORAGE_PUT_SUCCESS taskId=$cleanTaskId")
                Log.i(TAG, "TASK_PROOF_DOWNLOAD_URL_START taskId=$cleanTaskId")

                storageRef.downloadUrl.addOnSuccessListener { uri ->
                    val url = uri.toString()
                    Log.i(TAG, "TASK_PROOF_DOWNLOAD_URL_SUCCESS taskId=$cleanTaskId downloadUrl=$url")
                    Log.i(TAG, "TASK_PROOF_UPLOAD_SUCCESS taskId=$cleanTaskId")
                    TaskProofImageManager.saveProofBitmap(context, cleanTaskId, bitmap)
                    onComplete(url, null)
                }.addOnFailureListener { e ->
                    val err = e.localizedMessage ?: "DOWNLOAD_URL_FAILED"
                    Log.e(TAG, "TASK_PROOF_DOWNLOAD_URL_FAILED taskId=$cleanTaskId error=$err", e)
                    onComplete("", err)
                }
            }.addOnFailureListener { e ->
                val err = e.localizedMessage ?: "UPLOAD_FAILED"
                Log.e(TAG, "TASK_PROOF_STORAGE_PUT_FAILED taskId=$cleanTaskId error=$err", e)
                onComplete("", err)
            }
        } catch (e: Exception) {
            val err = e.localizedMessage ?: "UPLOAD_EXCEPTION"
            Log.e(TAG, "TASK_PROOF_UPLOAD_EXCEPTION taskId=$cleanTaskId error=$err", e)
            onComplete("", err)
        }
    }

    /**
     * Backwards-compatible overload for legacy callers.
     */
    fun uploadTaskProofPhoto(
        context: Context,
        questId: String,
        bitmap: Bitmap,
        onComplete: (photoUrl: String) -> Unit
    ) {
        uploadTaskProofPhoto(
            context = context,
            familyId = FamilyManager.getStoredFamilyId(context),
            childUserId = FamilyManager.getStoredUserId(context),
            questId = questId,
            bitmap = bitmap
        ) { url, _ ->
            onComplete(url)
        }
    }

    /**
     * Asynchronously downloads an image from HTTP/HTTPS URL and caches it locally via ProfileImageManager.
     * Prevents race conditions by discarding any download that no longer matches the latest cloud URL for the user.
     */
    fun downloadAndCachePhoto(
        context: Context,
        key: String,
        urlOrBase64: String,
        onDownloaded: (Bitmap?) -> Unit
    ) {
        if (urlOrBase64.isBlank()) {
            onDownloaded(null)
            return
        }

        val userUid = key.removePrefix("user_")
        val timestamp = System.currentTimeMillis()

        if (urlOrBase64.startsWith("http://") || urlOrBase64.startsWith("https://")) {
            Log.i(TAG, "PROFILE_DOWNLOAD_START uid=$userUid timestamp=$timestamp url=$urlOrBase64")
            thread(name = "ProfileDownloader-$key") {
                var conn: HttpURLConnection? = null
                try {
                    var currentUrl = urlOrBase64
                    var responseCode = 0
                    for (redirect in 0..5) {
                        val url = URL(currentUrl)
                        conn = url.openConnection() as HttpURLConnection
                        conn.doInput = true
                        conn.useCaches = false
                        conn.connectTimeout = 15000
                        conn.readTimeout = 15000
                        conn.instanceFollowRedirects = true
                        conn.setRequestProperty("User-Agent", "HomeSync-Android")
                        conn.setRequestProperty("Cache-Control", "no-cache, no-store")
                        conn.setRequestProperty("Pragma", "no-cache")
                        conn.connect()
                        responseCode = conn.responseCode
                        if (responseCode in 300..399) {
                            val newLoc = conn.getHeaderField("Location")
                            if (!newLoc.isNullOrBlank()) {
                                currentUrl = newLoc
                                try { conn.disconnect() } catch (_: Exception) {}
                                continue
                            }
                        }
                        break
                    }
                    if (conn != null && responseCode in 200..299) {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            if (key.startsWith("task_proof_")) {
                                TaskProofImageManager.saveProofBitmap(context, key.removePrefix("task_proof_"), bitmap)
                                onDownloaded(bitmap)
                            } else {
                                // Race condition guard: verify downloaded URL is still the latest cloud URL
                                val currentLatest = ProfileImageManager.getLatestCloudUrl(userUid)
                                if (currentLatest.isNotBlank() && currentLatest != urlOrBase64) {
                                    Log.w(TAG, "PROFILE_DISCARD_STALE_DOWNLOAD uid=$userUid downloadedUrl=$urlOrBase64 currentUrl=$currentLatest")
                                    onDownloaded(null)
                                    return@thread
                                }

                                ProfileImageManager.saveProfileImage(context, bitmap, key = "user_$userUid", photoUrl = urlOrBase64)
                                Log.i(TAG, "PROFILE_DOWNLOAD_SUCCESS uid=$userUid timestamp=${System.currentTimeMillis()}")
                                onDownloaded(bitmap)
                            }
                        } else {
                            Log.e(TAG, "PROFILE_DOWNLOAD_FAILED uid=$userUid timestamp=${System.currentTimeMillis()} error=DECODE_NULL")
                            onDownloaded(null)
                        }
                    } else {
                        Log.e(TAG, "PROFILE_DOWNLOAD_FAILED uid=$userUid timestamp=${System.currentTimeMillis()} error=HTTP_$responseCode")
                        onDownloaded(null)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "PROFILE_DOWNLOAD_FAILED uid=$userUid timestamp=${System.currentTimeMillis()} error=${e.message}")
                    onDownloaded(null)
                } finally {
                    try { conn?.disconnect() } catch (_: Exception) {}
                }
            }
        } else {
            val bitmap = TaskProofImageManager.decodeBase64ToBitmap(urlOrBase64)
            if (bitmap != null) {
                if (key.startsWith("task_proof_")) {
                    TaskProofImageManager.saveProofBitmap(context, key.removePrefix("task_proof_"), bitmap)
                } else {
                    ProfileImageManager.saveProfileImage(context, bitmap, key = "user_$userUid", photoUrl = urlOrBase64)
                }
                Log.i(TAG, "PROFILE_DOWNLOAD_SUCCESS uid=$userUid timestamp=${System.currentTimeMillis()}")
            } else {
                Log.e(TAG, "PROFILE_DOWNLOAD_FAILED uid=$userUid timestamp=${System.currentTimeMillis()} error=DECODE_BASE64_NULL")
            }
            onDownloaded(bitmap)
        }
    }
}
