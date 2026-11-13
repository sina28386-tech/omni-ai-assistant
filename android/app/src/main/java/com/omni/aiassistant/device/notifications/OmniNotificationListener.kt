package com.omni.aiassistant.device.notifications

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bounded, readable copy of one active notification. Bodies are truncated. */
data class NotificationItem(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val subText: String?,
    val time: Long,
    val isOngoing: Boolean,
    val isClearable: Boolean,
    val canReply: Boolean,
)

/**
 * Notification access implementation for Omni.
 *
 * Nothing is cached: every snapshot reads `activeNotifications` and copies only
 * the fields the model needs. This is deliberate — notification bodies are
 * sensitive and the user can revoke access at any time. Keys and bodies are
 * never logged.
 */
class OmniNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = WeakReference(this)
        _active.value = true
    }

    override fun onListenerDisconnected() {
        if (instance?.get() === this) {
            _active.value = false
            instance = null
        }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (instance?.get() === this) {
            instance = null
            _active.value = false
        }
        super.onDestroy()
    }

    /**
     * Active notifications, newest first, optionally filtered by package name
     * or visible app label. The limit is clamped to the platform contract's
     * 50-item ceiling.
     */
    fun snapshot(limit: Int = DEFAULT_LIMIT, appQuery: String? = null): List<NotificationItem> {
        val safeLimit = limit.coerceIn(0, MAX_ITEMS)
        if (safeLimit == 0) return emptyList()

        val query = appQuery?.trim()?.takeIf { it.isNotEmpty() }
        val notifications = activeNotifications ?: return emptyList()
        val labels = HashMap<String, String>(8)

        return notifications
            .asSequence()
            .sortedByDescending { it.postTime }
            .mapNotNull { status ->
                runCatching {
                    val packageName = status.packageName.orEmpty()
                    val label = labels.getOrPut(packageName) { resolveAppLabel(packageName) }
                    toItem(status, label)
                }.getOrNull()
            }
            .filter { item ->
                query == null ||
                    item.packageName.contains(query, ignoreCase = true) ||
                    item.appLabel.contains(query, ignoreCase = true)
            }
            .take(safeLimit)
            .toList()
    }

    /** Dismiss one notification by the key returned from [snapshot]. */
    fun dismiss(key: String): Boolean {
        val targetKey = key.trim()
        if (targetKey.isEmpty()) return false
        val exists = try {
            activeNotifications.any { it.key == targetKey }
        } catch (security: SecurityException) {
            throw security
        } catch (error: Exception) {
            false
        }
        if (!exists) return false
        return try {
            cancelNotification(targetKey)
            true
        } catch (security: SecurityException) {
            throw security
        } catch (error: Exception) {
            false
        }
    }

    /**
     * Send an inline reply through the notification's first RemoteInput action.
     * Returns false when the notification disappeared or has no reply action.
     */
    fun reply(key: String, text: String): Boolean {
        val targetKey = key.trim()
        if (targetKey.isEmpty() || text.isBlank()) return false

        val status = try {
            activeNotifications.firstOrNull { it.key == targetKey }
        } catch (security: SecurityException) {
            throw security
        } catch (error: Exception) {
            null
        } ?: return false
        val action = firstReplyAction(status.notification) ?: return false
        val remoteInput = action.remoteInputs
            ?.firstOrNull { it.resultKey.isNotBlank() }
            ?: return false
        val resultKey = remoteInput.resultKey

        return try {
            val results = Bundle().apply {
                putCharSequence(resultKey, text.take(MAX_REPLY_CHARS))
            }
            val input = RemoteInput.Builder(resultKey)
                .setAllowFreeFormInput(true)
                .addExtras(results)
                .build()
            val fillInIntent = Intent()
            RemoteInput.addResultsToIntent(arrayOf(input), fillInIntent, results)
            action.actionIntent.send(this, 0, fillInIntent)
            true
        } catch (security: SecurityException) {
            throw security
        } catch (error: Exception) {
            false
        }
    }

    private fun firstReplyAction(notification: Notification): Notification.Action? =
        notification.actions?.firstOrNull { action ->
            action.actionIntent != null &&
                action.remoteInputs?.any { it.resultKey.isNotBlank() } == true
        }

    private fun toItem(status: StatusBarNotification, appLabel: String): NotificationItem {
        val notification = status.notification
        val extras = notification.extras ?: Bundle()
        val title = firstText(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG),
            max = MAX_TITLE_CHARS,
        )
        val text = firstText(
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            max = MAX_BODY_CHARS,
        )
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
            ?.toString()
            ?.trim()
            ?.take(MAX_SUBTEXT_CHARS)
            ?.takeIf { it.isNotEmpty() }

        return NotificationItem(
            key = status.key.orEmpty(),
            packageName = status.packageName.orEmpty(),
            appLabel = appLabel,
            title = title,
            text = text,
            subText = subText,
            time = status.postTime,
            isOngoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
            isClearable = status.isClearable,
            canReply = firstReplyAction(notification) != null,
        )
    }

    private fun firstText(first: CharSequence?, second: CharSequence?, max: Int): String {
        val value = first?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
            ?: second?.toString()?.trim().orEmpty()
        if (value.length <= max) return value
        return value.take(max - 1).trimEnd() + "…"
    }

    private fun resolveAppLabel(packageName: String): String {
        if (packageName.isEmpty()) return ""
        return runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString().trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: packageName
    }

    companion object {
        private const val DEFAULT_LIMIT = 15
        private const val MAX_ITEMS = 50
        private const val MAX_TITLE_CHARS = 160
        private const val MAX_BODY_CHARS = 300
        private const val MAX_SUBTEXT_CHARS = 120
        private const val MAX_REPLY_CHARS = 1_000

        private var instance: WeakReference<OmniNotificationListener>? = null
        private val _active = MutableStateFlow(false)
        val active: StateFlow<Boolean> = _active.asStateFlow()

        fun current(): OmniNotificationListener? = instance?.get()
    }
}
