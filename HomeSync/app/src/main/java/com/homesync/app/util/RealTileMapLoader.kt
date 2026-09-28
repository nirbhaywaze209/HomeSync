package com.homesync.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

object RealTileMapLoader {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 4).coerceAtLeast(32 * 1024)
    private val tileCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return (bitmap.byteCount / 1024).coerceAtLeast(1)
        }
    }

    fun clearCache() {
        tileCache.evictAll()
    }

    fun getTileCoordinates(lat: Double, lng: Double, zoom: Int): Pair<Int, Int> {
        val safeZoom = zoom.coerceIn(1, 19)
        val n = 1 shl safeZoom
        val x = ((lng + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val latRad = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        val y = ((1.0 - asinh(tan(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        return Pair(x, y)
    }

    fun latLngToWorldPixels(lat: Double, lng: Double, zoom: Int): Pair<Double, Double> {
        val safeZoom = zoom.coerceIn(1, 19)
        val n = (1 shl safeZoom).toDouble()
        val worldSize = 256.0 * n
        val x = ((lng + 180.0) / 360.0) * worldSize
        val latRad = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        val y = ((1.0 - asinh(tan(latRad)) / PI) / 2.0) * worldSize
        return Pair(x, y)
    }

    fun worldPixelsToLatLng(worldX: Double, worldY: Double, zoom: Int): Pair<Double, Double> {
        val safeZoom = zoom.coerceIn(1, 19)
        val n = (1 shl safeZoom).toDouble()
        val worldSize = 256.0 * n
        val lng = (worldX / worldSize) * 360.0 - 180.0
        val yNorm = (1.0 - 2.0 * (worldY / worldSize)).coerceIn(-0.999999, 0.999999)
        val latRad = atan(sinh(yNorm * PI))
        val lat = Math.toDegrees(latRad)
        return Pair(lat, lng)
    }

    fun latLngToPixelDelta(baseLat: Double, baseLng: Double, targetLat: Double, targetLng: Double, zoom: Int): Pair<Float, Float> {
        val (baseX, baseY) = latLngToWorldPixels(baseLat, baseLng, zoom)
        val (targetX, targetY) = latLngToWorldPixels(targetLat, targetLng, zoom)
        return Pair((targetX - baseX).toFloat(), (targetY - baseY).toFloat())
    }

    fun pixelOffsetToLatLng(baseLat: Double, baseLng: Double, zoom: Int, pxX: Float, pxY: Float): Pair<Double, Double> {
        val (baseX, baseY) = latLngToWorldPixels(baseLat, baseLng, zoom)
        return worldPixelsToLatLng(baseX + pxX, baseY + pxY, zoom)
    }

    fun metersToPixels(meters: Float, lat: Double, zoom: Int): Float {
        val safeZoom = zoom.coerceIn(1, 19)
        val latRad = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        val metersPerPixel = (156543.03392 * cos(latRad) / (1 shl safeZoom)).toFloat()
        return if (metersPerPixel > 0f) (meters / metersPerPixel).coerceAtLeast(4f) else 8f
    }

    suspend fun loadTile(
        x: Int,
        y: Int,
        zoom: Int,
        isSatellite: Boolean
    ): Bitmap? = withContext(Dispatchers.IO) {
        val safeZoom = zoom.coerceIn(1, 20)
        val n = 1 shl safeZoom
        if (y < 0 || y >= n) return@withContext null // Out of geographic Mercator latitude range

        val validX = ((x % n) + n) % n
        val validY = y

        val cacheKey = "${if (isSatellite) "sat" else "street"}_${safeZoom}_${validX}_${validY}"
        tileCache.get(cacheKey)?.let { return@withContext it }

        val serverUrls = if (isSatellite) {
            listOf(
                "https://mt0.google.com/vt/lyrs=y&x=$validX&y=$validY&z=$safeZoom",
                "https://mt1.google.com/vt/lyrs=y&x=$validX&y=$validY&z=$safeZoom",
                "https://mt2.google.com/vt/lyrs=y&x=$validX&y=$validY&z=$safeZoom",
                "https://mt3.google.com/vt/lyrs=y&x=$validX&y=$validY&z=$safeZoom"
            )
        } else {
            listOf(
                "https://mt0.google.com/vt/lyrs=m&x=$validX&y=$validY&z=$safeZoom",
                "https://mt1.google.com/vt/lyrs=m&x=$validX&y=$validY&z=$safeZoom",
                "https://mt2.google.com/vt/lyrs=m&x=$validX&y=$validY&z=$safeZoom",
                "https://a.tile.openstreetmap.org/$safeZoom/$validX/$validY.png",
                "https://b.tile.openstreetmap.org/$safeZoom/$validX/$validY.png",
                "https://c.tile.openstreetmap.org/$safeZoom/$validX/$validY.png"
            )
        }

        for (urlString in serverUrls) {
            try {
                val url = URL(urlString)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                    setRequestProperty("Accept", "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                    doInput = true
                }
                connection.connect()

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val stream = connection.inputStream
                    val bitmap = BitmapFactory.decodeStream(stream)
                    stream.close()
                    connection.disconnect()
                    if (bitmap != null) {
                        tileCache.put(cacheKey, bitmap)
                        return@withContext bitmap
                    }
                } else {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                // Try next mirror
            }
        }
        return@withContext null
    }
}
