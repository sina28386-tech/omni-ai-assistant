package com.omni.aiassistant.device.tools.files

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** One row shared by MediaStore search and recents. */
internal data class MediaHit(
    val name: String,
    val path: String?,
    val uri: String,
    val size: Long,
    val modified: Long,
    val mime: String?,
)

/**
 * Read-only MediaStore access. Queries every relevant collection and merges
 * the rows, so a partial media grant (for example photos only) still returns
 * what the user allowed instead of failing the whole search.
 */
internal object MediaSearch {

    private val PROJECTION = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.DATA,
        MediaStore.MediaColumns.MIME_TYPE,
    )

    fun search(context: Context, query: String, kind: String, limit: Int): List<MediaHit> {
        val escaped = query
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'"
        val args = arrayOf("%$escaped%")
        return querySources(
            context = context,
            kind = kind,
            selection = selection,
            selectionArgs = args,
            sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
            limit = limit,
        )
    }

    fun recent(context: Context, kind: String, limit: Int): List<MediaHit> = querySources(
        context = context,
        kind = kind,
        selection = null,
        selectionArgs = null,
        sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        limit = limit,
    )

    @Suppress("DEPRECATION")
    private fun querySources(
        context: Context,
        kind: String,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String,
        limit: Int,
    ): List<MediaHit> {
        val hits = ArrayList<MediaHit>(limit)
        val seen = HashSet<String>(limit * 2)
        var denied = false
        var succeeded = 0

        for (source in sources(kind)) {
            val cursor = try {
                context.contentResolver.query(source, PROJECTION, selection, selectionArgs, sortOrder)
            } catch (deniedError: SecurityException) {
                denied = true
                null
            } catch (error: Exception) {
                null
            } ?: continue
            succeeded++

            try {
                val idIndex = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val mimeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                if (nameIndex < 0) continue

                while (cursor.moveToNext() && hits.size < limit) {
                    val name = if (cursor.isNull(nameIndex)) null else cursor.getString(nameIndex)
                    if (name.isNullOrBlank()) continue
                    if (!FileKinds.matchesKind(name, kind)) continue

                    val id = if (idIndex >= 0) cursor.getLong(idIndex) else -1L
                    val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                    val modified = if (dateIndex >= 0) cursor.getLong(dateIndex) * 1000L else 0L
                    val path = if (dataIndex >= 0 && !cursor.isNull(dataIndex)) {
                        cursor.getString(dataIndex)
                    } else {
                        null
                    }
                    val mime = if (mimeIndex >= 0 && !cursor.isNull(mimeIndex)) {
                        cursor.getString(mimeIndex)
                    } else {
                        null
                    }
                    val key = "$name|$size|$modified"
                    if (!seen.add(key)) continue
                    val uri = if (id >= 0) {
                        ContentUris.withAppendedId(source, id).toString()
                    } else {
                        source.toString()
                    }
                    hits.add(MediaHit(name, path, uri, size, modified, mime))
                }
            } catch (deniedError: SecurityException) {
                denied = true
            } catch (error: Exception) {
                // A missing column on an older OEM build: skip this collection.
            } finally {
                try {
                    cursor.close()
                } catch (ignored: Exception) {
                    // Nothing useful to do while closing a cursor.
                }
            }
        }

        if (hits.isEmpty() && denied && succeeded == 0) {
            throw SecurityException("Media access denied")
        }
        return hits
    }

    @Suppress("DEPRECATION")
    private fun sources(kind: String): List<Uri> {
        val files = MediaStore.Files.getContentUri("external")
        return when (kind) {
            "image" -> listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            "video" -> listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            "audio" -> listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            "document" -> listOf(files)
            else -> listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                files,
            )
        }
    }
}
