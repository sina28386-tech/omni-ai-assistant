package com.omni.aiassistant.device

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.omni.aiassistant.core.device.FileRecord
import com.omni.aiassistant.core.device.FileSafetyPolicy
import com.omni.aiassistant.core.device.FileSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed interface FileOutcome {
    data class Searched(val query: String, val matches: List<FileRecord>) : FileOutcome
    data class Launched(val label: String) : FileOutcome
    data class NeedsChoice(val query: String, val options: List<FileRecord>) : FileOutcome
    data class DeleteConfirm(val candidate: FileRecord) : FileOutcome
    data class Deleted(val name: String) : FileOutcome
    data class Downloaded(val name: String) : FileOutcome
    data class WallpaperSet(val name: String) : FileOutcome
    data class Blocked(val reason: String) : FileOutcome
    data class Failed(val message: String) : FileOutcome
}

/**
 * Lists files under app-scoped roots (no extra permissions needed).
 * Shared-storage (MediaStore + READ_MEDIA_*) is a planned phase-2 source.
 */
class FileIndexProvider(private val roots: List<File>) {
    fun snapshot(maxFiles: Int = 50_000): List<FileRecord> {
        val out = ArrayList<FileRecord>(4096)
        val stack = ArrayDeque<File>()
        roots.filter { it.exists() }.forEach { stack.add(it) }
        while (stack.isNotEmpty() && out.size < maxFiles) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    stack.add(child)
                } else if (out.size < maxFiles) {
                    out.add(
                        FileRecord(
                            name = child.name,
                            path = child.absolutePath,
                            sizeBytes = child.length(),
                            modifiedEpochMillis = child.lastModified(),
                        ),
                    )
                }
            }
        }
        return out
    }
}

class FileAgentRunner(
    private val context: Context,
    private val provider: FileIndexProvider,
) {
    /**
     * Runs file actions in order. Reads never ask anything. The batch stops
     * at the first step that needs the user's attention (choice, delete
     * confirmation, block, failure) — fail-closed, never guessing ahead.
     */
    suspend fun run(
        actions: List<DeviceAction>,
        confirmedDeleteName: String? = null,
    ): List<FileOutcome> = withContext(Dispatchers.IO) {
        val index = FileSearch.buildIndex(provider.snapshot())
        val outcomes = ArrayList<FileOutcome>(actions.size)
        for (action in actions) {
            val outcome = when (action) {
                is DeviceAction.FindFile -> {
                    val hits = FileSearch.search(index, action.query, action.kind)
                    FileOutcome.Searched(action.query, hits.map { it.record })
                }
                is DeviceAction.PlayFile -> {
                    val hits = FileSearch.search(index, action.query, action.kind).map { it.record }
                    when (val r = FileSafetyPolicy.resolvePlay(hits)) {
                        FileSafetyPolicy.PlayResolution.NotFound ->
                            FileOutcome.Failed("No file matches \"${action.query}\"")
                        is FileSafetyPolicy.PlayResolution.Launch -> launch(r.record)
                        is FileSafetyPolicy.PlayResolution.Disambiguate ->
                            FileOutcome.NeedsChoice(action.query, r.options)
                    }
                }
                is DeviceAction.DownloadFile -> download(action)
                is DeviceAction.DeleteFile -> delete(action, confirmedDeleteName)
                is DeviceAction.SetWallpaper -> setWallpaper(action)
                else -> FileOutcome.Failed("Not a file action")
            }
            outcomes.add(outcome)
            if (outcome is FileOutcome.Blocked || outcome is FileOutcome.Failed ||
                outcome is FileOutcome.NeedsChoice || outcome is FileOutcome.DeleteConfirm
            ) break
        }
        outcomes
    }

    /** Open one exact, user-picked record — no re-search, no guessing. */
    suspend fun playRecord(record: FileRecord): FileOutcome = withContext(Dispatchers.IO) {
        launch(record)
    }

    private fun launch(record: FileRecord): FileOutcome {
        val file = File(record.path)
        if (!file.exists()) return FileOutcome.Failed("File no longer exists: ${record.name}")
        val uri: Uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        } catch (e: Exception) {
            return FileOutcome.Failed("Cannot open ${record.name}: ${e.message}")
        }
        val mime = when (record.extension) {
            in com.omni.aiassistant.core.device.FileNameNormalize.VIDEO_EXTS -> "video/*"
            in com.omni.aiassistant.core.device.FileNameNormalize.IMAGE_EXTS -> "image/*"
            in com.omni.aiassistant.core.device.FileNameNormalize.AUDIO_EXTS -> "audio/*"
            else -> "*/*"
        }
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
            FileOutcome.Launched(record.name)
        } catch (e: Exception) {
            FileOutcome.Failed("No app can open ${record.name}")
        }
    }

    private fun download(action: DeviceAction.DownloadFile): FileOutcome {
        val url = action.url
        if (url.isNullOrBlank()) {
            return FileOutcome.Failed(
                "To download \"${action.query}\", send a direct link: /device download https://…/file.mp4",
            )
        }
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val host = uri?.host
        if (uri?.scheme != "https" && uri?.scheme != "http") return FileOutcome.Failed("Only http(s) links are accepted")
        if (host.isNullOrBlank() || uri.userInfo != null) return FileOutcome.Failed("Invalid download link")
        if (uri.scheme == "http" && !host.startsWith("10.") && host != "127.0.0.1" && host != "localhost") {
            return FileOutcome.Failed("Plain http is allowed only for local/lab hosts")
        }
        val fileName = uri.lastPathSegment?.takeIf { it.isNotBlank() }?.take(128) ?: "download.bin"
        return try {
            val dm = context.getSystemService(DownloadManager::class.java)
                ?: return FileOutcome.Failed("Download service unavailable")
            val request = DownloadManager.Request(uri)
                .setTitle(fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, android.os.Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
            dm.enqueue(request)
            FileOutcome.Downloaded(fileName)
        } catch (e: Exception) {
            FileOutcome.Failed("Download failed: ${e.message}")
        }
    }

    private fun setWallpaper(action: DeviceAction.SetWallpaper): FileOutcome {
        val path = action.localPath
        if (path.isNullOrBlank()) {
            return FileOutcome.Failed("Attach an image first, then say: بک‌گراند کن")
        }
        val file = File(path)
        if (!file.exists()) return FileOutcome.Failed("Image file not found")
        return try {
            val bitmap = android.graphics.BitmapFactory.decodeFile(path)
                ?: return FileOutcome.Failed("Could not decode image")
            val wm = context.getSystemService(android.app.WallpaperManager::class.java)
                ?: return FileOutcome.Failed("Wallpaper service unavailable")
            wm.setBitmap(bitmap)
            if (!bitmap.isRecycled) bitmap.recycle()
            FileOutcome.WallpaperSet(file.name)
        } catch (e: Exception) {
            FileOutcome.Failed("Wallpaper failed: ${e.message}")
        }
    }

    private fun delete(action: DeviceAction.DeleteFile, confirmedDeleteName: String?): FileOutcome {
        val index = FileSearch.buildIndex(provider.snapshot())
        val matches = FileSearch.search(index, action.exactName, com.omni.aiassistant.core.device.MediaKind.ANY, limit = 5)
            .map { it.record }
        return when (val decision = FileSafetyPolicy.decideDelete(action.exactName, matches)) {
            is FileSafetyPolicy.DeleteDecision.Blocked -> FileOutcome.Blocked(decision.reason)
            is FileSafetyPolicy.DeleteDecision.NeedsConfirmation -> {
                // Defense in depth: even a confirmed dialog result is re-checked here.
                if (confirmedDeleteName != null &&
                    FileSafetyPolicy.confirmDelete(confirmedDeleteName, decision.candidate)
                ) {
                    val target = File(decision.candidate.path)
                    if (!target.exists()) return FileOutcome.Failed("File no longer exists: ${decision.candidate.name}")
                    return if (target.delete() && !target.exists()) {
                        FileOutcome.Deleted(decision.candidate.name)
                    } else {
                        FileOutcome.Failed("Could not delete ${decision.candidate.name}")
                    }
                }
                FileOutcome.DeleteConfirm(decision.candidate)
            }
        }
    }
}
