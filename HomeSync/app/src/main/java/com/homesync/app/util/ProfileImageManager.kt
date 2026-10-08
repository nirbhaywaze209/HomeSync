package com.homesync.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object ProfileImageManager {

    data class CachedPhoto(
        val uid: String,
        val photoUrl: String,
        val bitmap: Bitmap
    )

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(4 * 1024)
    private val memCache = object : LruCache<String, CachedPhoto>(cacheSize) {
        override fun sizeOf(key: String, value: CachedPhoto): Int {
            return (value.bitmap.byteCount / 1024).coerceAtLeast(1)
        }
    }

    private val negativeCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val latestCloudUrls = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun setLatestCloudUrl(userId: String, url: String) {
        val cleanUid = userId.trim().removePrefix("user_")
        if (cleanUid.isNotBlank()) {
            latestCloudUrls[cleanUid] = url.trim()
        }
    }

    fun getLatestCloudUrl(userId: String): String {
        val cleanUid = userId.trim().removePrefix("user_")
        return latestCloudUrls[cleanUid] ?: ""
    }

    private fun getFile(context: Context, key: String = "guardian"): File {
        val safeKey = key.trim().removePrefix("user_").replace(Regex("[^a-zA-Z0-9_]"), "_")
        val jpgFile = File(context.filesDir, "${safeKey}_profile_pic.jpg")
        if (jpgFile.exists()) return jpgFile
        val pngFile = File(context.filesDir, "${safeKey}_profile_pic.png")
        if (pngFile.exists()) return pngFile
        return jpgFile
    }

    /**
     * Detects and removes any black letterboxing or pillarboxing bars added by camera previews or sensor padding.
     */
    fun trimBlackBorders(src: Bitmap): Bitmap {
        val width = src.width
        val height = src.height
        if (width <= 16 || height <= 16) return src

        var top = 0
        var bottom = height - 1
        var left = 0
        var right = width - 1

        fun isPixelBlack(pixel: Int): Boolean {
            val alpha = (pixel ushr 24) and 0xFF
            if (alpha < 30) return true
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            return r < 25 && g < 25 && b < 25
        }

        fun isRowBlack(y: Int): Boolean {
            val step = (width / 20).coerceAtLeast(1)
            for (x in 0 until width step step) {
                if (!isPixelBlack(src.getPixel(x, y))) return false
            }
            return true
        }

        fun isColBlack(x: Int): Boolean {
            val step = (height / 20).coerceAtLeast(1)
            for (y in 0 until height step step) {
                if (!isPixelBlack(src.getPixel(x, y))) return false
            }
            return true
        }

        while (top < bottom && isRowBlack(top)) top++
        while (bottom > top && isRowBlack(bottom)) bottom--
        while (left < right && isColBlack(left)) left++
        while (right > left && isColBlack(right)) right--

        val trimmedWidth = right - left + 1
        val trimmedHeight = bottom - top + 1
        if ((trimmedWidth < width || trimmedHeight < height) && trimmedWidth > 32 && trimmedHeight > 32) {
            return try {
                Bitmap.createBitmap(src, left, top, trimmedWidth, trimmedHeight)
            } catch (_: Exception) {
                src
            }
        }
        return src
    }

    /**
     * Normalizes an avatar bitmap:
     * 1. Applies correct EXIF orientation so portrait photos are not rotated sideways.
     * 2. Detects and trims any camera sensor black letterboxing or padding.
     * 3. Crops to a 1:1 square centered with balanced portrait framing so human faces and chins are fully visible.
     * 4. Resizes down to a crisp, high-resolution square (max 512x512) for smooth memory performance.
     */
    fun normalizeAvatarBitmap(rawBitmap: Bitmap, orientationDegrees: Int = 0): Bitmap {
        // Fast short-circuit if already an avatar square of 512x512 or smaller
        if (orientationDegrees == 0 && rawBitmap.width == rawBitmap.height && rawBitmap.width <= 512) {
            return rawBitmap
        }

        var bmp = rawBitmap
        // 1. Rotate if needed
        if (orientationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(orientationDegrees.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        }

        // 2. Trim black letterbox / sensor padding bars
        bmp = trimBlackBorders(bmp)

        // 3. Crop to 1:1 square
        val width = bmp.width
        val height = bmp.height
        val squareBmp = if (width == height) {
            bmp
        } else if (width > height) {
            // Horizontal photo: crop sides equally to center
            val xOffset = (width - height) / 2
            Bitmap.createBitmap(bmp, xOffset, 0, height, height)
        } else {
            // Vertical / Portrait photo: center with gentle upper bias so forehead, eyes, and chin are captured
            val size = width
            val diff = height - width
            val yOffset = ((diff * 0.15f).toInt()).coerceIn(0, diff)
            Bitmap.createBitmap(bmp, 0, yOffset, size, size)
        }

        // 4. Scale to standard crisp 512x512 avatar
        return if (squareBmp.width > 512) {
            Bitmap.createScaledBitmap(squareBmp, 512, 512, true)
        } else {
            squareBmp
        }
    }

    private fun getExifOrientationDegrees(context: Context, uri: Uri): Int {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            inputStream?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Saves a Bitmap image to memory LRU cache instantly, and asynchronously writes fast JPEG to disk.
     * Tracks the canonical UID and the specific photoUrl / version that generated the bitmap.
     */
    fun saveProfileImage(context: Context, bitmap: Bitmap, key: String = "guardian", photoUrl: String = ""): Boolean {
        val cleanKey = key.trim()
        val cleanUid = cleanKey.removePrefix("user_").removePrefix("child_")
        val canonicalKey = "user_$cleanUid"
        val normalized = normalizeAvatarBitmap(bitmap)

        val effectiveUrl = photoUrl.trim().ifBlank {
            getLatestCloudUrl(cleanUid).ifBlank { getCachedPhotoUrl(context, cleanUid) }
        }

        if (effectiveUrl.isNotBlank() && cleanUid.isNotBlank()) {
            setLatestCloudUrl(cleanUid, effectiveUrl)
            saveCachedPhotoUrl(context, cleanUid, effectiveUrl)
        }

        val cachedPhoto = CachedPhoto(cleanUid, effectiveUrl, normalized)
        negativeCache.remove(canonicalKey)
        negativeCache.remove(cleanKey)
        memCache.put(canonicalKey, cachedPhoto)
        if (cleanKey != canonicalKey) {
            memCache.put(cleanKey, cachedPhoto)
        }

        // Persist to internal storage
        val safeKey = canonicalKey.replace(Regex("[^a-zA-Z0-9_]"), "_")
        kotlin.concurrent.thread(name = "ProfileSaver-$safeKey", priority = Thread.NORM_PRIORITY) {
            try {
                val hasAlpha = normalized.hasAlpha()
                val format = if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                val quality = if (hasAlpha) 100 else 85
                val ext = if (hasAlpha) "png" else "jpg"
                val file = File(context.filesDir, "${safeKey}_profile_pic.$ext")
                FileOutputStream(file).use { out ->
                    normalized.compress(format, quality, out)
                }
                // Clean up alternate extension file
                val altExt = if (hasAlpha) "jpg" else "png"
                val altFile = File(context.filesDir, "${safeKey}_profile_pic.$altExt")
                if (altFile.exists()) altFile.delete()
            } catch (_: Exception) {}
        }
        return true
    }

    /**
     * Copies an image from Uri to internal storage and memory LRU cache with EXIF rotation and face centering.
     */
    fun saveProfileImageFromUri(context: Context, uri: Uri, key: String = "guardian", photoUrl: String = ""): Boolean {
        return try {
            val rotation = getExifOrientationDegrees(context, uri)
            val inputStream = context.contentResolver.openInputStream(uri) ?: return false
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bitmap != null) {
                val normalized = normalizeAvatarBitmap(bitmap, rotation)
                saveProfileImage(context, normalized, key, photoUrl)
            } else false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Loads profile picture Bitmap fast from LRU memory cache, falling back to disk decode.
     * Strictly verifies that the cached photo matches the expected/latest cloud URL.
     * If cloud URL has changed, OLD cached bitmap is NEVER returned!
     */
    fun getProfileImage(context: Context, key: String = "guardian", expectedUrl: String? = null): Bitmap? {
        val cleanKey = key.trim()
        val cleanUid = cleanKey.removePrefix("user_").removePrefix("child_")
        if (cleanUid.isBlank() || cleanUid.startsWith("hs-", ignoreCase = true)) return null

        val canonicalKey = "user_$cleanUid"
        val targetExpectedUrl = (expectedUrl ?: getLatestCloudUrl(cleanUid)).trim()

        // 1. Check Memory Cache
        if (negativeCache.containsKey(canonicalKey) || negativeCache.containsKey(cleanKey)) {
            return null
        }

        val cached = memCache.get(canonicalKey) ?: memCache.get(cleanKey)
        if (cached != null) {
            if (targetExpectedUrl.isNotBlank()) {
                if (cached.photoUrl == targetExpectedUrl) {
                    return cached.bitmap
                } else {
                    // Stale cache item! Invalidate from memCache
                    memCache.remove(canonicalKey)
                    memCache.remove(cleanKey)
                }
            } else {
                return cached.bitmap
            }
        }

        // 2. Check Disk Storage
        val safeKey = canonicalKey.replace(Regex("[^a-zA-Z0-9_]"), "_")
        val file = File(context.filesDir, "${safeKey}_profile_pic.jpg").takeIf { it.exists() && it.length() > 0 }
            ?: File(context.filesDir, "${safeKey}_profile_pic.png").takeIf { it.exists() && it.length() > 0 }

        if (file != null) {
            val storedUrl = getCachedPhotoUrl(context, cleanUid)
            if (targetExpectedUrl.isNotBlank() && storedUrl != targetExpectedUrl) {
                // Disk file belongs to older URL - do not return stale image!
                return null
            }

            try {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    val entry = CachedPhoto(cleanUid, storedUrl, bitmap)
                    memCache.put(canonicalKey, entry)
                    return bitmap
                }
            } catch (_: Exception) {}
        }

        negativeCache[canonicalKey] = System.currentTimeMillis()
        return null
    }

    fun getProfileImageForUser(context: Context, userId: String, expectedUrl: String? = null): Bitmap? {
        val cleanUid = userId.trim()
        if (cleanUid.isBlank() || cleanUid.startsWith("HS-", ignoreCase = true)) return null
        return getProfileImage(context, "user_$cleanUid", expectedUrl)
    }

    fun clearProfileImage(context: Context, key: String = "guardian"): Boolean {
        val cleanKey = key.trim()
        val cleanUid = cleanKey.removePrefix("user_").removePrefix("child_")
        val canonicalKey = "user_$cleanUid"
        val safeKey = canonicalKey.replace(Regex("[^a-zA-Z0-9_]"), "_")

        negativeCache.remove(canonicalKey)
        negativeCache.remove(cleanKey)
        memCache.remove(canonicalKey)
        memCache.remove(cleanKey)
        if (cleanUid.isNotBlank()) {
            latestCloudUrls.remove(cleanUid)
            saveCachedPhotoUrl(context, cleanUid, "")
        }

        val jpg = File(context.filesDir, "${safeKey}_profile_pic.jpg")
        if (jpg.exists()) jpg.delete()
        val png = File(context.filesDir, "${safeKey}_profile_pic.png")
        if (png.exists()) png.delete()

        // Also clean legacy key filenames
        val legacySafe = cleanKey.replace(Regex("[^a-zA-Z0-9_]"), "_")
        if (legacySafe != safeKey) {
            val legacyJpg = File(context.filesDir, "${legacySafe}_profile_pic.jpg")
            if (legacyJpg.exists()) legacyJpg.delete()
            val legacyPng = File(context.filesDir, "${legacySafe}_profile_pic.png")
            if (legacyPng.exists()) legacyPng.delete()
        }

        return true
    }

    private const val PREFS_PHOTO_URLS = "homesync_profile_urls"

    fun getCachedPhotoUrl(context: Context, key: String): String {
        val cleanKey = key.trim().removePrefix("user_").removePrefix("child_")
        val safeKey = cleanKey.replace(Regex("[^a-zA-Z0-9_]"), "_")
        return context.getSharedPreferences(PREFS_PHOTO_URLS, Context.MODE_PRIVATE)
            .getString("url_$safeKey", "") ?: ""
    }

    fun saveCachedPhotoUrl(context: Context, key: String, url: String) {
        val cleanKey = key.trim().removePrefix("user_").removePrefix("child_")
        val safeKey = cleanKey.replace(Regex("[^a-zA-Z0-9_]"), "_")
        context.getSharedPreferences(PREFS_PHOTO_URLS, Context.MODE_PRIVATE)
            .edit()
            .putString("url_$safeKey", url.trim())
            .apply()
    }
}

