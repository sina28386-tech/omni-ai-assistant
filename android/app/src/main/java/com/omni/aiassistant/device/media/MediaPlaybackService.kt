package com.omni.aiassistant.device.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.omni.aiassistant.MainActivity
import com.omni.aiassistant.R
import com.omni.aiassistant.core.wake.WakeCommandBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plays music on this phone.
 *
 * The service is a foreground service of type `mediaPlayback`, so the music
 * keeps playing when the app is closed - the same guarantee every music
 * player gives. It owns a `MediaPlayer` and a `MediaSession`, which means the
 * hardware media buttons, the notification and the assistant's own tools all
 * control the very same playback.
 */
class MediaPlaybackService : Service() {

    private var player: MediaPlayer? = null
    private var session: MediaSession? = null
    private var queue: List<MediaTrack> = emptyList()
    private var index: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSession(this, "OmniMedia").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onStop() = stopPlayback()
                override fun onSkipToNext() = playAt(index + 1)
                override fun onSkipToPrevious() = playAt(index - 1)
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val tracks = intent.getStringArrayListExtra(EXTRA_TRACKS)
                    ?.mapNotNull { track -> decodeTrack(track) }
                    .orEmpty()
                val position = intent.getIntExtra(EXTRA_INDEX, 0)
                if (tracks.isNotEmpty()) {
                    queue = tracks
                    playAt(position)
                }
            }

            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stopPlayback()
            ACTION_NEXT -> playAt(index + 1)
            ACTION_PREVIOUS -> playAt(index - 1)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releasePlayer()
        session?.release()
        session = null
        super.onDestroy()
    }

    /* ------------------------------------------------------------------ */
    /* Playback                                                            */
    /* ------------------------------------------------------------------ */

    private fun playAt(target: Int) {
        if (queue.isEmpty()) return
        val safeIndex = target.coerceIn(0, queue.lastIndex)
        val track = queue[safeIndex]
        index = safeIndex
        releasePlayer()
        runCatching {
            val created = MediaPlayer()
            val localFile = java.io.File(track.path)
            if (localFile.isFile) {
                // A file:// URI cannot be opened through setDataSource(Context,
                // Uri); the plain path can.
                created.setDataSource(localFile.absolutePath)
            } else {
                created.setDataSource(this, track.contentUri.toUri())
            }
            created.setOnCompletionListener { playAt(safeIndex + 1) }
            created.setOnErrorListener { _, what, extra ->
                publish(
                    MediaPlaybackState(
                        playing = false,
                        title = track.title,
                        artist = track.artist,
                        error = "Playback failed ($what/$extra)",
                    ),
                )
                true
            }
            created.prepare()
            created.start()
            player = created
            publish(
                MediaPlaybackState(
                    playing = true,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                ),
            )
            startForegroundSafely()
        }.onFailure { error ->
            publish(
                MediaPlaybackState(
                    playing = false,
                    title = track.title,
                    artist = track.artist,
                    error = "Could not play that file: ${error.message?.take(120) ?: "unknown error"}",
                ),
            )
        }
    }

    private fun pause() {
        runCatching { player?.pause() }
        publish(currentState().copy(playing = false))
    }

    private fun resume() {
        runCatching { player?.start() }
        publish(currentState().copy(playing = true))
        startForegroundSafely()
    }

    private fun stopPlayback() {
        releasePlayer()
        publish(MediaPlaybackState())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun releasePlayer() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    private fun currentState(): MediaPlaybackState {
        val track = queue.getOrNull(index)
        return MediaPlaybackState(
            playing = runCatching { player?.isPlaying == true }.getOrDefault(false),
            title = track?.title.orEmpty(),
            artist = track?.artist.orEmpty(),
            album = track?.album.orEmpty(),
        )
    }

    private fun publish(state: MediaPlaybackState) {
        stateFlow.value = state
        // Keep the system media controls (the notification shade, the lock
        // screen, headphones) in step with the real player.
        runCatching {
            session?.setMetadata(
                android.media.MediaMetadata.Builder()
                    .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, state.title)
                    .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, state.artist)
                    .build(),
            )
            val actions = PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS
            session?.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(actions)
                    .setState(
                        if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        1f,
                    )
                    .build(),
            )
        }
    }

    /* ------------------------------------------------------------------ */
    /* Notification                                                        */
    /* ------------------------------------------------------------------ */

    private fun startForegroundSafely() {
        val notification = buildNotification(currentState())
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildNotification(state: MediaPlaybackState): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(icon: Int, title: String, intent: Intent): NotificationCompat.Action =
            NotificationCompat.Action(
                icon,
                title,
                PendingIntent.getService(
                    this,
                    intent.action.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(state.title.ifBlank { getString(R.string.media_notification_title) })
            .setContentText(state.artist)
            .setSmallIcon(R.drawable.ic_notification_media)
            .setOngoing(state.playing)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)

        if (state.playing) {
            builder.addAction(
                action(
                    R.drawable.ic_notification_media,
                    getString(R.string.media_pause),
                    Intent(this, MediaPlaybackService::class.java).setAction(ACTION_PAUSE),
                ),
            )
        } else {
            builder.addAction(
                action(
                    R.drawable.ic_notification_media,
                    getString(R.string.media_play),
                    Intent(this, MediaPlaybackService::class.java).setAction(ACTION_RESUME),
                ),
            )
        }
        builder.addAction(
            action(
                R.drawable.ic_notification_media,
                getString(R.string.media_stop),
                Intent(this, MediaPlaybackService::class.java).setAction(ACTION_STOP),
            ),
        )
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.media_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.media_channel_description)
                setShowBadge(false)
            },
        )
    }

    private fun decodeTrack(raw: String): MediaTrack? = runCatching {
        val parts = raw.split(FIELD_SEPARATOR)
        MediaTrack(
            id = parts.getOrElse(0) { "" },
            title = parts.getOrElse(1) { "" },
            artist = parts.getOrElse(2) { "" },
            album = parts.getOrElse(3) { "" },
            durationSeconds = parts.getOrElse(4) { "0" }.toIntOrNull() ?: 0,
            path = parts.getOrElse(5) { "" },
            contentUri = parts.getOrElse(6) { "" },
        )
    }.getOrNull()

    private fun encodeTrack(track: MediaTrack): String = listOf(
        track.id,
        track.title,
        track.artist,
        track.album,
        track.durationSeconds.toString(),
        track.path,
        track.contentUri,
    ).joinToString(FIELD_SEPARATOR)

    companion object {
        const val ACTION_PLAY = "com.omni.aiassistant.action.MEDIA_PLAY"
        const val ACTION_PAUSE = "com.omni.aiassistant.action.MEDIA_PAUSE"
        const val ACTION_RESUME = "com.omni.aiassistant.action.MEDIA_RESUME"
        const val ACTION_STOP = "com.omni.aiassistant.action.MEDIA_STOP"
        const val ACTION_NEXT = "com.omni.aiassistant.action.MEDIA_NEXT"
        const val ACTION_PREVIOUS = "com.omni.aiassistant.action.MEDIA_PREVIOUS"

        const val EXTRA_TRACKS = "tracks"
        const val EXTRA_INDEX = "index"

        private const val CHANNEL_ID = "omni_media"
        private const val NOTIFICATION_ID = 4202
        /** ASCII unit separator: it cannot appear in a file name or a title. */
        private const val FIELD_SEPARATOR = "\u001F"

        /** What is playing right now. The chat timeline reads it. */
        private val stateFlow = MutableStateFlow(MediaPlaybackState())
        val state: StateFlow<MediaPlaybackState> = stateFlow.asStateFlow()

        fun play(context: Context, tracks: List<MediaTrack>, index: Int = 0) {
            val intent = Intent(context, MediaPlaybackService::class.java).setAction(ACTION_PLAY)
                .putStringArrayListExtra(
                    EXTRA_TRACKS,
                    ArrayList(tracks.map { track ->
                        listOf(
                            track.id,
                            track.title,
                            track.artist,
                            track.album,
                            track.durationSeconds.toString(),
                            track.path,
                            track.contentUri,
                        ).joinToString(FIELD_SEPARATOR)
                    }),
                )
                .putExtra(EXTRA_INDEX, index)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun command(context: Context, action: String) {
            val intent = Intent(context, MediaPlaybackService::class.java).setAction(action)
            runCatching { context.startService(intent) }
        }
    }
}

/** The playback state the UI and the tools read. */
data class MediaPlaybackState(
    val playing: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val error: String? = null,
)
