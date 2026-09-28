package com.homesync.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

import android.util.LruCache

object TaskProofImageManager {

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(4 * 1024)
    private val proofMemCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return (bitmap.byteCount / 1024).coerceAtLeast(1)
        }
    }

    fun saveProofBitmap(context: Context, questId: String, bitmap: Bitmap): String {
        if (questId.isNotBlank()) {
            proofMemCache.put(questId, bitmap)
        }
        return try {
            val file = File(context.filesDir, "task_proof_${questId}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    /**
     * Decodes a bitmap from a content URI with inSampleSize calculation to prevent OOM
     * while preserving high practical resolution for inspection (up to 1920px).
     */
    fun decodeSampledBitmapFromUri(context: Context, uri: Uri, maxDim: Int = 1920): Bitmap? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, boundsOptions)
            }
            var sampleSize = 1
            var w = boundsOptions.outWidth
            var h = boundsOptions.outHeight
            while (w > maxDim || h > maxDim) {
                w /= 2
                h /= 2
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, decodeOptions)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun saveProofFromUri(context: Context, questId: String, uri: Uri): String {
        return try {
            val bitmap = decodeSampledBitmapFromUri(context, uri) ?: return ""
            saveProofBitmap(context, questId, bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    fun getProofBitmap(context: Context, imagePath: String, questId: String = ""): Bitmap? {
        val cacheKey = if (questId.isNotBlank()) questId else imagePath.trim()
        if (cacheKey.isNotBlank()) {
            proofMemCache.get(cacheKey)?.let { return it }
        }

        // 1. Check fallback file if path is empty
        val effectivePath = imagePath.trim().ifBlank {
            if (questId.isNotBlank()) File(context.filesDir, "task_proof_${questId}.png").absolutePath else ""
        }
        if (effectivePath.isBlank()) return null

        return try {
            val cleanPath = effectivePath.removePrefix("file://")

            // 2. Direct absolute file path check
            val directFile = File(cleanPath)
            if (directFile.exists() && directFile.length() > 0) {
                val bitmap = decodeSampledBitmapFromFile(directFile.absolutePath)
                if (bitmap != null) {
                    if (cacheKey.isNotBlank()) proofMemCache.put(cacheKey, bitmap)
                    return bitmap
                }
            }

            // 3. Check inside context.filesDir
            val internalFile = File(context.filesDir, cleanPath.substringAfterLast("/"))
            if (internalFile.exists() && internalFile.length() > 0) {
                val bitmap = decodeSampledBitmapFromFile(internalFile.absolutePath)
                if (bitmap != null) {
                    if (cacheKey.isNotBlank()) proofMemCache.put(cacheKey, bitmap)
                    return bitmap
                }
            }

            // 4. Check fallback quest file name in context.filesDir
            if (questId.isNotBlank()) {
                val questFile = File(context.filesDir, "task_proof_${questId}.png")
                if (questFile.exists() && questFile.length() > 0) {
                    val bitmap = decodeSampledBitmapFromFile(questFile.absolutePath)
                    if (bitmap != null) {
                        proofMemCache.put(questId, bitmap)
                        return bitmap
                    }
                }
            }

            // 5. Check if it's an HTTP/HTTPS URL
            if (effectivePath.startsWith("http://") || effectivePath.startsWith("https://")) {
                if (questId.isNotBlank()) {
                    val httpCachedFile = File(context.filesDir, "task_proof_${questId}.png")
                    if (httpCachedFile.exists() && httpCachedFile.length() > 0) {
                        val bitmap = decodeSampledBitmapFromFile(httpCachedFile.absolutePath)
                        if (bitmap != null) {
                            proofMemCache.put(questId, bitmap)
                            return bitmap
                        }
                    }
                    FirebaseStorageHelper.downloadAndCachePhoto(context, "task_proof_${questId}", effectivePath) { downloadedBmp ->
                        if (downloadedBmp != null) {
                            saveProofBitmap(context, questId, downloadedBmp)
                        }
                    }
                }
            }

            // 6. Check if it's a content:// URI
            if (effectivePath.startsWith("content://")) {
                val uri = Uri.parse(effectivePath)
                val bitmap = decodeSampledBitmapFromUri(context, uri)
                if (bitmap != null) {
                    if (cacheKey.isNotBlank()) proofMemCache.put(cacheKey, bitmap)
                    return bitmap
                }
            }

            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Decodes a bitmap from file with inSampleSize calculation to prevent OOM
     * while preserving high practical resolution for inspection (up to 1920px).
     */
    fun decodeSampledBitmapFromFile(filePath: String, maxDim: Int = 1920): Bitmap? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, boundsOptions)
            var sampleSize = 1
            var w = boundsOptions.outWidth
            var h = boundsOptions.outHeight
            while (w > maxDim || h > maxDim) {
                w /= 2
                h /= 2
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(filePath, decodeOptions)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Reactively loads the high-resolution proof bitmap. If cached, invokes callback
     * or returns immediately. If downloading from Firebase Storage, invokes callback on completion.
     */
    fun loadProofBitmap(
        context: Context,
        imagePath: String,
        questId: String = "",
        onLoaded: (Bitmap) -> Unit
    ): Bitmap? {
        val cached = getProofBitmap(context, imagePath, questId)
        if (cached != null) {
            return cached
        }
        val effectivePath = imagePath.trim().ifBlank {
            if (questId.isNotBlank()) File(context.filesDir, "task_proof_${questId}.png").absolutePath else ""
        }
        if (effectivePath.startsWith("http://") || effectivePath.startsWith("https://")) {
            FirebaseStorageHelper.downloadAndCachePhoto(context, "task_proof_${questId}", effectivePath) { downloaded ->
                if (downloaded != null) {
                    saveProofBitmap(context, questId, downloaded)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onLoaded(downloaded)
                    }
                }
            }
        }
        return null
    }

    fun encodeBitmapToBase64(bitmap: Bitmap): String {
        return try {
            val maxDimension = 800
            val scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val width = if (ratio > 1) maxDimension else (maxDimension * ratio).toInt()
                val height = if (ratio > 1) (maxDimension / ratio).toInt() else maxDimension
                Bitmap.createScaledBitmap(bitmap, width, height, true)
            } else {
                bitmap
            }

            val byteArrayOutputStream = java.io.ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 65, byteArrayOutputStream)
            val byteArray = byteArrayOutputStream.toByteArray()
            android.util.Base64.encodeToString(byteArray, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    fun decodeBase64ToBitmap(base64Str: String): Bitmap? {
        return try {
            if (base64Str.isBlank()) return null
            val decodedBytes = android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun saveBase64ImageLocally(context: Context, questId: String, base64Str: String): String {
        val bitmap = decodeBase64ToBitmap(base64Str) ?: return ""
        return saveProofBitmap(context, questId, bitmap)
    }
}
