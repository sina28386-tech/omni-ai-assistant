package com.omni.aiassistant.device.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Locale

/**
 * One playable track.
 *
 * [path] is used for playback because a plain file path always works, even
 * when MediaStore has no row for the file. [contentUri] is kept for the
 * system and for sharing.
 */
data class MediaTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val path: String,
    val contentUri: String,
) {
    /** A short label for the chat timeline and the notification. */
    fun displayName(): String = when {
        artist.isNotBlank() && artist != title -> "$title — $artist"
        else -> title
    }
}

/**
 * Finds music on this phone.
 *
 * Two sources are used, in this order:
 * 1. the media library (`MediaStore`), which is the correct source when the
 *    user granted access to photos and music;
 * 2. a walk of the public media folders, which works with all-files access
 *    and also finds files the media scanner has not seen yet.
 *
 * The second source is what makes the feature usable on a freshly set up
 * phone, where the media scanner has not run.
 */
class MediaLibrary(private val context: Context) {

    fun search(
        query: String,
        limit: Int = 20,
        kind: String = "audio",
    ): List<MediaTrack> {
        val needle = query.trim().lowercase(Locale.ROOT)
        val fromStore = searchStore(needle, limit)
        if (fromStore.size >= limit) return dedupe(fromStore).take(limit)
        val seen = fromStore.map { it.path }.filter { it.isNotBlank() }.toSet()
        val fromDisk = searchDisk(needle, limit, kind).filterNot { it.path in seen }
        return dedupe(fromStore + fromDisk).take(limit)
    }

    /**
     * The same file can be reported by the media library *and* by the
     * filesystem walk. Without this the assistant would tell the user that
     * several files match when there is only one.
     */
    private fun dedupe(tracks: List<MediaTrack>): List<MediaTrack> {
        val byTitle = LinkedHashMap<String, MediaTrack>()
        for (track in tracks) {
            val key = track.title.lowercase(Locale.ROOT)
            val existing = byTitle[key]
            if (existing == null || (existing.path.isBlank() && track.path.isNotBlank())) {
                byTitle[key] = track
            }
        }
        return byTitle.values.toList()
    }

    /** The best match for a free-text request such as "that song by X". */
    fun resolve(query: String): Resolution {
        val needle = query.trim().lowercase(Locale.ROOT)
        // A precise indexed match can be played without walking every public folder.
        // Preserve the existing store-first ordering and only skip the walk for an exact match.
        val indexed = dedupe(searchStore(needle, 25)).firstOrNull {
            it.title.lowercase(Locale.ROOT) == needle ||
                it.displayName().lowercase(Locale.ROOT) == needle ||
                it.path.lowercase(Locale.ROOT) == needle
        }
        if (indexed != null) return Resolution.Found(indexed)
        val tracks = search(query, limit = 25)
        if (tracks.isEmpty()) return Resolution.NotFound
        val exact = tracks.firstOrNull {
            it.title.lowercase(Locale.ROOT) == needle ||
                it.displayName().lowercase(Locale.ROOT) == needle ||
                it.path.lowercase(Locale.ROOT) == needle
        }
        if (exact != null) return Resolution.Found(exact)
        // The request may be longer than the file name ("song.wav" for "song")
        // or shorter ("omar" for "omar-test-tone.wav").
        val candidates = tracks.filter { track ->
            val title = track.title.lowercase(Locale.ROOT)
            val artist = track.artist.lowercase(Locale.ROOT)
            title.contains(needle) || artist.contains(needle) ||
                needle.contains(title) || needle.contains(artist) ||
                title.split(' ', '-', '_').any { part -> part.isNotEmpty() && needle.contains(part) }
        }
        return when {
            candidates.size == 1 -> Resolution.Found(candidates.first())
            tracks.size == 1 -> Resolution.Found(tracks.first())
            candidates.isNotEmpty() -> Resolution.Ambiguous(candidates.take(8))
            else -> Resolution.Ambiguous(tracks.take(8))
        }
    }

    sealed interface Resolution {
        data class Found(val track: MediaTrack) : Resolution
        data class Ambiguous(val candidates: List<MediaTrack>) : Resolution
        data object NotFound : Resolution
    }

    /* ------------------------------------------------------------------ */
    /* MediaStore                                                           */
    /* ------------------------------------------------------------------ */

    private fun searchStore(needle: String, limit: Int): List<MediaTrack> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
        )
        val selection = if (needle.isEmpty()) {
            null
        } else {
            "${MediaStore.Audio.Media.TITLE} LIKE ? OR " +
                "${MediaStore.Audio.Media.ARTIST} LIKE ? OR " +
                "${MediaStore.Audio.Media.ALBUM} LIKE ?"
        }
        val args = if (needle.isEmpty()) null else arrayOf("%$needle%", "%$needle%", "%$needle%")
        val tracks = mutableListOf<MediaTrack>()
        runCatching {
            context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext() && tracks.size < limit) {
                    val id = cursor.getLong(idColumn)
                    val path = if (dataColumn >= 0) cursor.getString(dataColumn).orEmpty() else ""
                    tracks += MediaTrack(
                        id = id.toString(),
                        title = cursor.getString(titleColumn)?.takeIf { it.isNotBlank() }
                            ?: File(path).nameWithoutExtension,
                        artist = cursor.getString(artistColumn).orEmpty(),
                        album = cursor.getString(albumColumn).orEmpty(),
                        durationSeconds = (cursor.getLong(durationColumn) / 1000L).toInt(),
                        path = path,
                        contentUri = ContentUris.withAppendedId(collection, id).toString(),
                    )
                }
            }
        }
        return tracks
    }

    /* ------------------------------------------------------------------ */
    /* Filesystem                                                           */
    /* ------------------------------------------------------------------ */

    private fun searchDisk(needle: String, limit: Int, kind: String): List<MediaTrack> {
        val roots = buildList {
            add(File(Environment.getExternalStorageDirectory(), "Music"))
            add(File(Environment.getExternalStorageDirectory(), "Download"))
            add(File(Environment.getExternalStorageDirectory(), "Podcasts"))
            add(File(Environment.getExternalStorageDirectory(), "Alarms"))
            add(File(Environment.getExternalStorageDirectory(), "Ringtones"))
            add(File(Environment.getExternalStorageDirectory(), "Notifications"))
        }
        val extensions = when (kind.lowercase(Locale.ROOT)) {
            "video" -> setOf("mp4", "mkv", "webm", "3gp", "mov")
            "image" -> setOf("jpg", "jpeg", "png", "webp", "heic", "gif")
            else -> setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "mid")
        }
        val tracks = mutableListOf<MediaTrack>()
        for (root in roots) {
            if (!root.exists() || !root.isDirectory) continue
            root.walkTopDown()
                .maxDepth(4)
                .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in extensions }
                .filter { needle.isEmpty() || it.name.lowercase(Locale.ROOT).contains(needle) }
                .take(limit)
                .forEach { file ->
                    tracks += MediaTrack(
                        id = file.absolutePath,
                        title = file.nameWithoutExtension,
                        artist = file.parentFile?.name.orEmpty(),
                        album = file.parentFile?.name.orEmpty(),
                        durationSeconds = 0,
                        path = file.absolutePath,
                        contentUri = Uri.fromFile(file).toString(),
                    )
                }
            if (tracks.size >= limit) break
        }
        return tracks
    }
}
