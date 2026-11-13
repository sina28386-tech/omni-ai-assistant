package com.omni.aiassistant.device.tools.files

import android.os.SystemClock
import java.io.File

/**
 * The result of a filesystem walk.
 *
 * [permissionDenied] tells the caller that the walk was *not* able to look
 * everywhere. Without it the assistant would answer "no files found" when the
 * truth is "I was not allowed to look", which is a lie the user acts on.
 */
internal data class WalkOutcome(
    val files: List<File>,
    val permissionDenied: Boolean,
) {
    val isEmpty: Boolean get() = files.isEmpty()
}

/**
 * Breadth-first scan of shared storage with a hard budget so a search can
 * never stall the assistant: 6 000 entries, depth 6 and 5 seconds.
 *
 * Hidden folders, `Android`, symlinks and the app-private trees are never
 * followed. The `Android` folder is skipped as a whole: it only holds other
 * apps' private data, and walking it would burn the whole budget before the
 * user's own folders (Download, Documents, Music) are ever reached.
 */
internal object ExternalWalk {

    private const val MAX_ENTRIES = 6_000
    private const val MAX_DEPTH = 6
    private const val MAX_MILLIS = 5_000L

    /** Upper bound of collected matches per walk. */
    private const val MAX_MATCHES = 400

    /** Folders that hold the user's own files. They are walked first. */
    private val PRIORITY_FOLDERS = listOf(
        "Download",
        "Documents",
        "Music",
        "Movies",
        "Pictures",
        "DCIM",
        "Podcasts",
        "Alarms",
        "Ringtones",
        "Notifications",
    )

    fun search(root: File, query: String, kind: String, limit: Int): WalkOutcome {
        val results = ArrayList<File>(limit)
        val denied = walk(root, kind) { file ->
            if (FileKinds.matchesName(file.name, query)) {
                results.add(file)
                1
            } else {
                0
            }
        }
        return WalkOutcome(results.sortedByDescending { it.lastModified() }, denied)
    }

    fun recent(root: File, kind: String, limit: Int): WalkOutcome {
        val collected = ArrayList<File>(256)
        val denied = walk(root, kind) { file ->
            if (collected.size < MAX_ENTRIES) collected.add(file)
            1
        }
        return WalkOutcome(collected.sortedByDescending { it.lastModified() }.take(limit), denied)
    }

    /**
     * The callback returns how many *matches* it collected for that file, so
     * the budget is spent on results and not on unrelated files.
     */
    private fun walk(
        root: File,
        kind: String,
        onFile: (File) -> Int,
    ): Boolean {
        val started = SystemClock.elapsedRealtime()
        var visited = 0
        var found = 0
        var denied = false
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty()) {
            if (visited >= MAX_ENTRIES) break
            if (SystemClock.elapsedRealtime() - started > MAX_MILLIS) break

            val (directory, depth) = queue.removeFirst()
            val children = try {
                directory.listFiles()
            } catch (security: SecurityException) {
                denied = true
                null
            } catch (error: Exception) {
                null
            } ?: continue

            // The folders that hold the user's own files go first, so a slow
            // device never spends its whole budget on the wrong tree.
            val ordered = children.sortedBy { child ->
                val name = child.name
                val priority = PRIORITY_FOLDERS.indexOf(name).takeIf { it >= 0 }
                if (priority != null) priority else PRIORITY_FOLDERS.size + 1
            }

            for (child in ordered) {
                if (visited >= MAX_ENTRIES) break
                if (SystemClock.elapsedRealtime() - started > MAX_MILLIS) break

                visited++
                val name = child.name
                if (name.isEmpty() || name.startsWith(".")) continue

                val isDirectory = try {
                    child.isDirectory
                } catch (security: SecurityException) {
                    denied = true
                    false
                }
                if (isDirectory) {
                    // Files below this directory would exceed the depth budget.
                    if (depth >= MAX_DEPTH - 1) continue
                    if (isSymbolicLink(child)) continue
                    val relative = child.relativeToOrNull(root)?.invariantSeparatorsPath ?: continue
                    // `Android` only holds other apps' private data. The user
                    // cannot open it either, and walking it would starve the
                    // folders that actually contain their files.
                    if (relative == "Android" || relative.startsWith("Android/")) continue
                    queue.add(child to (depth + 1))
                } else {
                    if (!FileKinds.matchesKind(name, kind)) continue
                    found += onFile(child)
                    if (found >= MAX_MATCHES) break
                }
            }
        }
        return denied
    }
}
