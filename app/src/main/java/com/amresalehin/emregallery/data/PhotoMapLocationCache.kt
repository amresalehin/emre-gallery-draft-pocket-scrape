package com.amresalehin.emregallery.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import org.osmdroid.util.GeoPoint
import java.io.File

/**
 * Persistent SQLite cache for image and video GPS metadata, including media with no GPS coordinates.
 */
class PhotoMapLocationCache(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "photo_map_locations.db", null, 3) {

    data class CachedLocation(val point: GeoPoint?)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS photo_locations (uri TEXT PRIMARY KEY NOT NULL, latitude REAL, longitude REAL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS photo_locations")
        onCreate(db)
    }

    @Synchronized
    fun clearAll() {
        writableDatabase.delete("photo_locations", null, null)
    }

    @Synchronized
    fun loadAll(): Map<String, CachedLocation> {
        val result = mutableMapOf<String, CachedLocation>()
        readableDatabase.query(
            "photo_locations", arrayOf("uri", "latitude", "longitude"),
            null, null, null, null, null
        ).use { cursor ->
            val uriColumn = cursor.getColumnIndexOrThrow("uri")
            val latitudeColumn = cursor.getColumnIndexOrThrow("latitude")
            val longitudeColumn = cursor.getColumnIndexOrThrow("longitude")
            while (cursor.moveToNext()) {
                val uri = cursor.getString(uriColumn)
                val hasCoordinates = !cursor.isNull(latitudeColumn) && !cursor.isNull(longitudeColumn)
                val point = if (!hasCoordinates) {
                    null
                } else {
                    val lat = cursor.getDouble(latitudeColumn)
                    val lon = cursor.getDouble(longitudeColumn)
                    if (
                        lat.isFinite() && lon.isFinite() &&
                        lat in -90.0..90.0 && lon in -180.0..180.0 &&
                        !(lat == 0.0 && lon == 0.0)
                    ) {
                        GeoPoint(lat, lon)
                    } else {
                        null
                    }
                }

                // Old builds could cache (0, 0) as a real GPS coordinate. Do not turn
                // an invalid coordinate into a permanent "no GPS" cache entry: omit it
                // so the next scan re-reads the source metadata.
                if (hasCoordinates && point == null) continue
                result[uri] = CachedLocation(point)
            }
        }
        return result
    }

    @Synchronized
    fun save(uri: String, point: GeoPoint?) {
        val values = ContentValues().apply {
            put("uri", uri)
            if (point == null) {
                putNull("latitude")
                putNull("longitude")
            } else {
                put("latitude", point.latitude)
                put("longitude", point.longitude)
            }
        }
        writableDatabase.insertWithOnConflict(
            "photo_locations", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /**
     * Scans a batch of media items, caching GPS coordinates to the database.
     * Can be safely called from background services or foreground screens.
     */
    fun scanMediaBatch(
        context: Context,
        media: List<MediaItem>,
        forceRescan: Boolean = false,
        onProgress: ((scanned: Int, total: Int, located: Int) -> Unit)? = null
    ): Int {
        // Enforce background priority so this scanner never contends with the UI thread or RenderThread
        runCatching {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        }
        val items = media
        if (forceRescan) {
            clearAll()
        }
        val cached = loadAll()
        val pending = if (forceRescan) items else items.filterNot { cached.containsKey(it.uri) }
        if (pending.isEmpty()) return 0

        var newlyLocated = 0
        var totalLocated = cached.values.count { it.point != null }

        // Process in throttled batches with batched transactions to minimize disk sync and CPU usage
        val batchSize = 25
        for (batch in pending.chunked(batchSize)) {
            // Parse media before starting the DB transaction. ContentResolver/EXIF I/O can be
            // slow; holding a write lock during it blocks other location-cache readers/writers.
            val extracted = batch.map { item ->
                item to extractLocation(context, item.uri, isVideo = item.isVideo)
            }

            val db = writableDatabase
            db.beginTransaction()
            try {
                for ((item, point) in extracted) {
                    val values = ContentValues().apply {
                        put("uri", item.uri)
                        if (point == null) {
                            putNull("latitude")
                            putNull("longitude")
                        } else {
                            put("latitude", point.latitude)
                            put("longitude", point.longitude)
                        }
                    }
                    db.insertWithOnConflict("photo_locations", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                    if (point != null) {
                        newlyLocated++
                        totalLocated++
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            onProgress?.invoke(newlyLocated, pending.size, totalLocated)
        }
        return newlyLocated
    }

    companion object {
        /**
         * Safely extracts latitude and longitude from an image URI's EXIF data or video metadata.
         * Handles unredacted MediaStore URIs (via ACCESS_MEDIA_LOCATION and MediaStore.setRequireOriginal),
         * seekable FileDescriptors, stream fallbacks, and ISO 6709 coordinates.
         */
        fun extractLocation(
            context: Context,
            uriString: String,
            isVideo: Boolean = false
        ): GeoPoint? {
            val uri = Uri.parse(uriString)

            // Direct local file support
            if (uri.scheme == null || uri.scheme == ContentResolver.SCHEME_FILE) {
                val path = uri.path ?: uriString
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val directPoint = runCatching {
                        val exif = ExifInterface(file)
                        getGeoPointFromExif(exif)
                    }.getOrNull()
                    if (directPoint != null) return directPoint
                }
            }

            // On Android 10+ (API 29+), content URIs require MediaStore.setRequireOriginal(uri)
            // with ACCESS_MEDIA_LOCATION to prevent stripping of EXIF GPS coordinates.
            val targetUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == ContentResolver.SCHEME_CONTENT) {
                runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
            } else {
                uri
            }

            // 1. Try ExifInterface with FileDescriptor (handles seekable streams correctly for TIFF/EXIF headers)
            val fromFd = runCatching {
                context.contentResolver.openFileDescriptor(targetUri, "r")?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)
                    getGeoPointFromExif(exif)
                }
            }.getOrNull()
            if (fromFd != null) return fromFd

            // 2. Try ExifInterface with InputStream on targetUri
            val fromStream = runCatching {
                context.contentResolver.openInputStream(targetUri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    getGeoPointFromExif(exif)
                }
            }.getOrNull()
            if (fromStream != null) return fromStream

            // 3. Fallback to original uri (in case setRequireOriginal failed due to provider quirks)
            if (targetUri != uri) {
                val fromOrigFd = runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        val exif = ExifInterface(pfd.fileDescriptor)
                        getGeoPointFromExif(exif)
                    }
                }.getOrNull()
                if (fromOrigFd != null) return fromOrigFd

                val fromOrigStream = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val exif = ExifInterface(stream)
                        getGeoPointFromExif(exif)
                    }
                }.getOrNull()
                if (fromOrigStream != null) return fromOrigStream
            }

            // Avoid spinning up native media decoders for photos. This fallback is for video
            // container metadata (ISO 6709) only; invoking it for every photo without EXIF GPS
            // is expensive across large libraries and can exhaust native media resources.
            if (isVideo) {
                val fromMetadata = runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        val locStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                        parseIso6709(locStr)
                    } finally {
                        retriever.release()
                    }
                }.getOrNull()
                if (fromMetadata != null) return fromMetadata
            }

            return null
        }

        private fun getGeoPointFromExif(exif: ExifInterface): GeoPoint? {
            // First check the standard latLong property
            val latLong = exif.latLong
            if (latLong != null && latLong.size >= 2) {
                val lat = latLong[0]
                val lon = latLong[1]
                if (lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)) {
                    return GeoPoint(lat, lon)
                }
            }

            // Fallback: parse raw GPS EXIF tags manually if latLong parser failed
            val latStr = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
            val latRef = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)
            val lonStr = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)
            val lonRef = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)
            if (!latStr.isNullOrBlank() && !lonStr.isNullOrBlank()) {
                val lat = parseDms(latStr, latRef)
                val lon = parseDms(lonStr, lonRef)
                if (lat != null && lon != null && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)) {
                    return GeoPoint(lat, lon)
                }
            }

            return null
        }

        private fun parseDms(dms: String, ref: String?): Double? {
            return runCatching {
                val parts = dms.split(',', ' ').filter { it.isNotBlank() }
                val degrees: Double
                val minutes: Double
                val seconds: Double
                if (parts.size >= 3) {
                    degrees = parseRational(parts[0])
                    minutes = parseRational(parts[1])
                    seconds = parseRational(parts[2])
                } else if (parts.size == 1) {
                    degrees = parseRational(parts[0])
                    minutes = 0.0
                    seconds = 0.0
                } else {
                    return null
                }
                var decimal = degrees + (minutes / 60.0) + (seconds / 3600.0)
                val r = ref?.trim()?.uppercase()
                if (r == "S" || r == "W") {
                    decimal = -decimal
                }
                decimal
            }.getOrNull()
        }

        private fun parseRational(part: String): Double {
            val sub = part.split('/')
            return if (sub.size == 2) {
                val denom = sub[1].toDoubleOrNull() ?: 1.0
                if (denom != 0.0) (sub[0].toDoubleOrNull() ?: 0.0) / denom else 0.0
            } else {
                part.toDoubleOrNull() ?: 0.0
            }
        }

        private fun parseIso6709(raw: String?): GeoPoint? {
            if (raw.isNullOrBlank()) return null
            val match = Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(raw.trim()) ?: return null
            val lat = match.groupValues[1].toDoubleOrNull() ?: return null
            val lon = match.groupValues[2].toDoubleOrNull() ?: return null
            return if (lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)) {
                GeoPoint(lat, lon)
            } else null
        }
    }
}
