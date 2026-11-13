package com.omni.aiassistant.device.context

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import com.omni.aiassistant.core.agent.EnvironmentSnapshot
import com.omni.aiassistant.data.settings.SettingsRepository
import com.omni.aiassistant.device.capability.Capability
import com.omni.aiassistant.device.capability.CapabilityCenter
import com.omni.aiassistant.device.capability.CapabilityStatus
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Collects what Arman can see of this phone: the software that is installed
 * and the capabilities that are actually usable right now.
 *
 * The result is cached for a minute because the agent loop rebuilds the prompt
 * on every round, while installing an app or flipping a permission is rare.
 * The snapshot is data only; it never triggers a permission prompt.
 */
class EnvironmentContextProvider(
    private val context: Context,
    private val capabilities: CapabilityCenter,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()

    @Volatile
    private var cachedAt: Long = 0L

    @Volatile
    private var cached: EnvironmentSnapshot? = null

    suspend fun snapshot(force: Boolean = false): EnvironmentSnapshot {
        val now = System.currentTimeMillis()
        cached?.let { if (!force && now - cachedAt < TTL_MS) return it }
        return mutex.withLock {
            val current = cached
            if (!force && current != null && System.currentTimeMillis() - cachedAt < TTL_MS) {
                return@withLock current
            }
            val fresh = collect()
            cached = fresh
            cachedAt = System.currentTimeMillis()
            fresh
        }
    }

    private suspend fun collect(): EnvironmentSnapshot {
        val (apps, appCount) = withContext(Dispatchers.IO) { launchableApps() }
        val statuses = capabilities.statuses()
        val appSettings = settings.snapshot()
        return EnvironmentSnapshot(
            deviceLabel = deviceLabel(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            languageTag = Locale.getDefault().toLanguageTag(),
            apps = apps,
            appCount = appCount,
            granted = statuses.filter { it.granted }.map { it.capability.name },
            blocked = statuses.filterNot { it.granted }
                .associate { it.capability.name to blockedReason(it) },
            pcBridgeReady = appSettings.hermesUrl.isNotBlank() && appSettings.hermesToken.isNotBlank(),
        )
    }

    /**
     * User-installed launchable apps first, then the system ones, each group
     * alphabetically. The cap keeps the prompt small; the total count is
     * reported separately so the model can say "+N more".
     */
    private fun launchableApps(): Pair<List<String>, Int> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching { context.packageManager.queryIntentActivities(intent, 0) }
            .getOrDefault(emptyList())
        val labels = resolved.asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .mapNotNull { info ->
                val label = runCatching {
                    context.packageManager.getApplicationLabel(info).toString()
                }.getOrNull().orEmpty().trim()
                if (label.isEmpty()) null else label to isSystem(info)
            }
            .sortedWith(compareBy({ it.second }, { it.first.lowercase(Locale.ROOT) }))
            .map { it.first }
            .distinct()
            .toList()
        return labels.take(MAX_APPS) to labels.size
    }

    private fun isSystem(info: ApplicationInfo): Boolean =
        (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0

    private fun deviceLabel(): String {
        val maker = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        return when {
            maker.isBlank() -> model
            model.isBlank() -> maker
            model.startsWith(maker, ignoreCase = true) -> model
            else -> "$maker $model"
        }
    }

    /**
     * A short, concrete reason plus the switch that unlocks the capability.
     * The model repeats this instead of claiming a blocked action worked.
     */
    private fun blockedReason(status: CapabilityStatus): String = when (status.capability) {
        Capability.ACCESSIBILITY -> "screen control is off; enable Omni under Accessibility"
        Capability.NOTIFICATION_ACCESS -> "notification reading is off; enable Omni under Notification access"
        Capability.STORAGE -> "all-files access is off; enable it in system settings"
        Capability.DND -> "Do Not Disturb access is off; enable it in system settings"
        Capability.WRITE_SETTINGS -> "modify-system-settings access is off; enable it in system settings"
        else -> if (status.requiresSettings) {
            "not granted; enable it in system settings"
        } else {
            "permission not granted; the user can allow it when prompted"
        }
    }

    private companion object {
        const val TTL_MS = 60_000L
        const val MAX_APPS = 60
    }
}
