package com.omni.aiassistant.device.tools.media

import android.content.Context
import com.omni.aiassistant.core.agent.Tool
import com.omni.aiassistant.core.agent.ToolArgs
import com.omni.aiassistant.core.agent.ToolArgs.int
import com.omni.aiassistant.core.agent.ToolArgs.string
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolGroup
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSchema
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.device.capability.Capability
import com.omni.aiassistant.device.capability.CapabilityCenter
import com.omni.aiassistant.device.media.MediaLibrary
import com.omni.aiassistant.device.media.MediaPlaybackService
import com.omni.aiassistant.device.media.MediaTrack
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Music and media.
 *
 * `media_play` starts the playback service, so the music keeps playing when
 * the app is closed - exactly like any music player. The service owns a
 * media session, which means the hardware buttons and the assistant's own
 * controls drive the same playback.
 */
class MediaToolGroup(
    private val context: Context,
    private val capabilities: CapabilityCenter,
) : ToolGroup {

    private val library = MediaLibrary(context.applicationContext)

    override fun tools(): List<Tool> = listOf(
        MediaSearchTool(library, capabilities),
        MediaPlayTool(context.applicationContext, library, capabilities),
        MediaStopTool(context.applicationContext),
    )
}

private class MediaSearchTool(
    private val library: MediaLibrary,
    private val capabilities: CapabilityCenter,
) : Tool {

    override val spec = ToolSpec(
        name = "media_search",
        description = "Search the music and media files on this phone by name, artist or " +
            "album. Use for browsing and ambiguous requests; media_play can directly find " +
            "and play a specific song. Returns candidates with their title and artist.",
        category = ToolCategory.MEDIA,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "query" to ToolSchema.string("Part of the title, artist or album"),
                "kind" to ToolSchema.string(
                    "What to look for",
                    choices = listOf("audio", "video", "image"),
                    default = "audio",
                ),
                "limit" to ToolSchema.integer(
                    "Maximum number of results",
                    minimum = 1,
                    maximum = 30,
                    default = 10,
                ),
            ),
            required = listOf("query"),
        ),
    )

    override suspend fun execute(arguments: JsonObject): ToolOutcome = try {
        val query = arguments.string("query")?.trim()
        if (query.isNullOrEmpty()) {
            ToolOutcome.Failure("Missing required argument 'query'")
        } else if (query.length > 200) {
            ToolOutcome.Failure("The search text is too long")
        } else {
            val kind = arguments.string("kind")?.lowercase() ?: "audio"
            val limit = ToolArgs.clamp(arguments.int("limit") ?: 10, 1, 30)
            if (!hasMediaAccess()) {
                // Saying "nothing matched" would be a lie: we simply were not
                // allowed to look at the music on this phone.
                return ToolOutcome.NeedsPermission(
                    Capability.MEDIA.name,
                    "Access to music and media on this phone",
                )
            }
            val tracks = library.search(query, limit, kind)
            if (tracks.isEmpty()) {
                ToolOutcome.Failure(
                    "No media file matched \"${query.take(60)}\" on this phone.",
                )
            } else {
                ToolOutcome.Success(
                    summary = "Found ${tracks.size} media file(s) matching \"${query.take(60)}\"",
                    data = buildJsonObject {
                        put("query", query)
                        put("count", tracks.size)
                        put("results", buildJsonArray {
                            tracks.forEach { track -> add(track.toJson()) }
                        })
                    },
                )
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ToolOutcome.Failure("media_search failed (${error::class.simpleName})")
    }

    private fun hasMediaAccess(): Boolean = runCatching {
        capabilities.isGranted(Capability.MEDIA) || capabilities.isGranted(Capability.STORAGE)
    }.getOrDefault(false)
}

private class MediaPlayTool(
    private val appContext: Context,
    private val library: MediaLibrary,
    private val capabilities: CapabilityCenter,
) : Tool {

    override val spec = ToolSpec(
        name = "media_play",
        description = "Play a song or audio file that is on this phone. Accepts the title, the " +
            "artist, or the exact file path. The music keeps playing when the app is closed. " +
            "Call this directly when a title or artist was provided: it finds the file and " +
            "returns choices if several match. Use media_search for browsing or vague requests.",
        category = ToolCategory.MEDIA,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "query" to ToolSchema.string("Title, artist or full path of the file to play"),
                "path" to ToolSchema.string("Exact absolute path of the file to play"),
            ),
            required = listOf("query"),
        ),
    )

    override suspend fun execute(arguments: JsonObject): ToolOutcome = try {
        val path = arguments.string("path")?.trim()?.takeIf { it.isNotEmpty() }
        val query = arguments.string("query")?.trim()?.takeIf { it.isNotEmpty() }
        if (path == null && query == null) {
            return ToolOutcome.Failure("Provide the 'query' or the 'path' of the file to play")
        }
        if (path == null && !canAccessMedia()) {
            return ToolOutcome.NeedsPermission(
                Capability.MEDIA.name,
                "Access to music and media on this phone",
            )
        }
        val track: MediaTrack = if (path != null) {
            val file = java.io.File(path)
            if (!file.exists()) {
                return ToolOutcome.Failure("There is no file at that path.")
            }
            MediaTrack(
                id = file.absolutePath,
                title = file.nameWithoutExtension,
                artist = file.parentFile?.name.orEmpty(),
                album = file.parentFile?.name.orEmpty(),
                durationSeconds = 0,
                path = file.absolutePath,
                contentUri = android.net.Uri.fromFile(file).toString(),
            )
        } else {
            when (val resolution = library.resolve(query!!)) {
                is MediaLibrary.Resolution.Found -> resolution.track
                is MediaLibrary.Resolution.Ambiguous -> {
                    return ToolOutcome.Failure(
                        "Several files match \"${query!!.take(60)}\". Choose one of: " +
                            resolution.candidates.joinToString(", ") { it.displayName() },
                    )
                }

                MediaLibrary.Resolution.NotFound -> {
                    return ToolOutcome.Failure(
                        "No media file on this phone matches \"${query!!.take(60)}\".",
                    )
                }
            }
        }
        MediaPlaybackService.play(context = appContext, tracks = listOf(track), index = 0)
        ToolOutcome.Success(
            summary = "Playing ${track.displayName()}",
            data = buildJsonObject {
                put("title", track.title)
                put("artist", track.artist)
                put("path", track.path)
                put("playing", true)
            },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ToolOutcome.Failure("media_play failed (${error::class.simpleName})")
    }

    private fun canAccessMedia(): Boolean = runCatching {
        capabilities.isGranted(Capability.MEDIA) || capabilities.isGranted(Capability.STORAGE)
    }.getOrDefault(false)
}

private class MediaStopTool(
    private val appContext: Context,
) : Tool {

    override val spec = ToolSpec(
        name = "media_stop",
        description = "Stop the music that Arman started playing on this phone.",
        category = ToolCategory.MEDIA,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(properties = emptyMap()),
    )

    override suspend fun execute(arguments: JsonObject): ToolOutcome {
        val state = MediaPlaybackService.state.value
        MediaPlaybackService.command(appContext, MediaPlaybackService.ACTION_STOP)
        return ToolOutcome.Success(
            summary = "Stopped the playback" + if (state.title.isNotBlank()) " (${state.title})" else "",
            data = buildJsonObject { put("stopped", true) },
        )
    }
}

private fun MediaTrack.toJson(): JsonObject = buildJsonObject {
    put("id", id)
    put("title", title)
    put("artist", artist)
    put("album", album)
    put("durationSeconds", durationSeconds)
    put("path", path)
}
