package com.omni.aiassistant.device.tools.files

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.omni.aiassistant.core.agent.ToolArgs
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSchema
import com.omni.aiassistant.core.agent.ToolSpec
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class AppsListTool(context: Context) : AppsTool(context) {

    override val spec = ToolSpec(
        name = "apps_list",
        description = "List installed apps that have a launcher icon. Filter by name or package.",
        category = ToolCategory.APPS,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "query" to ToolSchema.string("Optional part of an app name or package"),
                "limit" to ToolSchema.integer("Maximum number of apps", 1, 50, 40),
            ),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val query = arguments.argString("query")?.trim()
        val limit = ToolArgs.clamp(arguments.argInt("limit") ?: 40, 1, 50)
        val packageManager = context.packageManager

        val entries = launchableApps(context)
            .filter { entry ->
                query.isNullOrEmpty() ||
                    entry.label.contains(query, ignoreCase = true) ||
                    entry.packageName.contains(query, ignoreCase = true)
            }
            .take(limit)

        val apps = entries.map { entry -> appSummary(packageManager, entry) }
        return success(
            "Found ${apps.size} launchable app(s)",
            buildJsonObject {
                put("count", JsonPrimitive(apps.size))
                put("apps", buildJsonArray { apps.forEach { add(it) } })
            },
        )
    }
}

internal class AppOpenTool(context: Context) : AppsTool(context) {

    override val spec = ToolSpec(
        name = "app_open",
        description = "Open an installed app by name or package. Asks the caller to be more " +
            "specific when several app names match.",
        category = ToolCategory.APPS,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "app" to ToolSchema.string("App label or exact package name"),
            ),
            required = listOf("app"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val query = arguments.argString("app")
            ?: return ToolOutcome.Failure("An app name or package is required")
        val entry = when (val found = lookupApp(context, query)) {
            is AppLookup.Error -> return ToolOutcome.Failure(found.message.take(400))
            is AppLookup.Ok -> found.entry
        }

        val launch = (try {
            context.packageManager.getLaunchIntentForPackage(entry.packageName)
        } catch (error: Exception) {
            null
        }) ?: return ToolOutcome.Failure("${entry.label} has no launchable screen")

        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launch)
            success(
                "Opened ${entry.label}",
                buildJsonObject {
                    put("label", JsonPrimitive(entry.label))
                    put("package", JsonPrimitive(entry.packageName))
                    put("opened", JsonPrimitive(true))
                },
            )
        } catch (error: Exception) {
            ToolOutcome.Failure("Could not open ${entry.label}")
        }
    }
}

internal class AppInfoTool(context: Context) : AppsTool(context) {

    override val spec = ToolSpec(
        name = "app_info",
        description = "Show package details for one installed app: version, install date, " +
            "system flag and target SDK.",
        category = ToolCategory.APPS,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "app" to ToolSchema.string("App label or exact package name"),
            ),
            required = listOf("app"),
        ),
    )

    @Suppress("DEPRECATION")
    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val query = arguments.argString("app")
            ?: return ToolOutcome.Failure("An app name or package is required")
        val entry = when (val found = lookupApp(context, query)) {
            is AppLookup.Error -> return ToolOutcome.Failure(found.message.take(400))
            is AppLookup.Ok -> found.entry
        }

        val packageManager = context.packageManager
        val info = try {
            packageManager.getPackageInfo(entry.packageName, 0)
        } catch (error: Exception) {
            null
        } ?: return ToolOutcome.Failure("Could not read details for ${entry.label}")
        val application = info.applicationInfo
            ?: return ToolOutcome.Failure("Could not read details for ${entry.label}")
        val label = try {
            application.loadLabel(packageManager).toString()
        } catch (error: Exception) {
            entry.label
        }.ifBlank { entry.label }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        val versionName = info.versionName
        val versionJson = versionName?.takeIf { it.isNotBlank() }
            ?.let { JsonPrimitive(it) }
            ?: JsonNull
        val summary = if (versionName.isNullOrBlank()) {
            "$label (${entry.packageName})"
        } else {
            "$label $versionName (${entry.packageName})"
        }

        return success(
            summary,
            buildJsonObject {
                put("label", JsonPrimitive(label))
                put("package", JsonPrimitive(entry.packageName))
                put("versionName", versionJson)
                put("versionCode", JsonPrimitive(versionCode))
                put("installedAt", JsonPrimitive(info.firstInstallTime))
                put(
                    "system",
                    JsonPrimitive((application.flags and ApplicationInfo.FLAG_SYSTEM) != 0),
                )
                put("targetSdk", JsonPrimitive(application.targetSdkVersion))
            },
        )
    }
}

internal class AppUninstallTool(context: Context) : AppsTool(context) {

    override val spec = ToolSpec(
        name = "app_uninstall",
        description = "Ask the system to uninstall one app. Android shows its own confirmation " +
            "screen; Omni cannot uninstall itself.",
        category = ToolCategory.APPS,
        risk = ToolRisk.HIGH,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "app" to ToolSchema.string("App label or exact package name"),
            ),
            required = listOf("app"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val query = arguments.argString("app")
            ?: return ToolOutcome.Failure("An app name or package is required")
        val entry = when (val found = lookupApp(context, query)) {
            is AppLookup.Error -> return ToolOutcome.Failure(found.message.take(400))
            is AppLookup.Ok -> found.entry
        }
        if (entry.packageName == context.packageName) {
            return ToolOutcome.Failure("Omni cannot uninstall itself")
        }
        if (!isInstalled(context, entry.packageName)) {
            return ToolOutcome.Failure("${entry.label} is not installed")
        }

        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${entry.packageName}"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            success(
                "Uninstall requested for ${entry.label}",
                buildJsonObject {
                    put("label", JsonPrimitive(entry.label))
                    put("package", JsonPrimitive(entry.packageName))
                    put("requested", JsonPrimitive(true))
                },
            )
        } catch (error: Exception) {
            ToolOutcome.Failure("Could not open the uninstall screen for ${entry.label}")
        }
    }
}

internal class AppSettingsTool(context: Context) : AppsTool(context) {

    override val spec = ToolSpec(
        name = "app_settings",
        description = "Open the system app-details settings page for one installed app.",
        category = ToolCategory.APPS,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "app" to ToolSchema.string("App label or exact package name"),
            ),
            required = listOf("app"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val query = arguments.argString("app")
            ?: return ToolOutcome.Failure("An app name or package is required")
        val entry = when (val found = lookupApp(context, query)) {
            is AppLookup.Error -> return ToolOutcome.Failure(found.message.take(400))
            is AppLookup.Ok -> found.entry
        }
        if (!isInstalled(context, entry.packageName)) {
            return ToolOutcome.Failure("${entry.label} is not installed")
        }

        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${entry.packageName}"),
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            success(
                "Opened settings for ${entry.label}",
                buildJsonObject {
                    put("label", JsonPrimitive(entry.label))
                    put("package", JsonPrimitive(entry.packageName))
                    put("opened", JsonPrimitive(true))
                },
            )
        } catch (error: Exception) {
            ToolOutcome.Failure("Could not open settings for ${entry.label}")
        }
    }
}

// --- package lookup ---------------------------------------------------------

private data class AppEntry(val label: String, val packageName: String)

private sealed interface AppLookup {
    data class Ok(val entry: AppEntry) : AppLookup
    data class Error(val message: String) : AppLookup
}

private sealed interface AppMatch {
    data class Found(val entry: AppEntry) : AppMatch
    data class Missing(val message: String) : AppMatch
    data class Ambiguous(val candidates: List<AppEntry>) : AppMatch
}

@Suppress("DEPRECATION")
private fun launchableApps(context: Context): List<AppEntry> {
    val packageManager = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolved = try {
        packageManager.queryIntentActivities(intent, 0)
    } catch (error: Exception) {
        emptyList()
    }
    val seen = LinkedHashMap<String, AppEntry>()
    for (info in resolved) {
        val activity = info.activityInfo ?: continue
        val packageName = activity.packageName
        if (packageName.isBlank() || seen.containsKey(packageName)) continue
        val label = try {
            info.loadLabel(packageManager).toString()
        } catch (error: Exception) {
            packageName
        }
        seen[packageName] = AppEntry(label.ifBlank { packageName }, packageName)
    }
    return seen.values.sortedBy { it.label.lowercase() }
}

@Suppress("DEPRECATION")
private fun findApp(context: Context, raw: String): AppMatch {
    val query = raw.trim()
    if (query.isEmpty()) return AppMatch.Missing("An app name or package is required")
    val packageManager = context.packageManager

    val exact = try {
        packageManager.getApplicationInfo(query, 0)
    } catch (error: Exception) {
        null
    }
    if (exact != null) {
        val label = try {
            exact.loadLabel(packageManager).toString()
        } catch (error: Exception) {
            query
        }
        return AppMatch.Found(AppEntry(label.ifBlank { query }, query))
    }

    val matches = launchableApps(context).filter { entry ->
        entry.label.contains(query, ignoreCase = true) ||
            entry.packageName.contains(query, ignoreCase = true)
    }
    if (matches.isEmpty()) {
        return AppMatch.Missing("No installed app matches \"${query.take(60)}\"")
    }
    val exactLabel = matches.filter { it.label.equals(query, ignoreCase = true) }
    return when {
        exactLabel.size == 1 -> AppMatch.Found(exactLabel.single())
        matches.size == 1 -> AppMatch.Found(matches.single())
        else -> AppMatch.Ambiguous(matches.take(5))
    }
}

private fun lookupApp(context: Context, raw: String): AppLookup = when (val match = findApp(context, raw)) {
    is AppMatch.Found -> AppLookup.Ok(match.entry)
    is AppMatch.Missing -> AppLookup.Error(match.message)
    is AppMatch.Ambiguous -> AppLookup.Error(
        "Several apps match: " +
            match.candidates.joinToString("; ") { "${it.label} (${it.packageName})" } +
            ". Use the exact package name.",
    )
}

@Suppress("DEPRECATION")
private fun isInstalled(context: Context, packageName: String): Boolean = try {
    context.packageManager.getPackageInfo(packageName, 0)
    true
} catch (error: Exception) {
    false
}

@Suppress("DEPRECATION")
private fun appSummary(packageManager: PackageManager, entry: AppEntry): JsonObject {
    val application = try {
        packageManager.getApplicationInfo(entry.packageName, 0)
    } catch (error: Exception) {
        null
    }
    val versionName = try {
        packageManager.getPackageInfo(entry.packageName, 0).versionName
    } catch (error: Exception) {
        null
    }
    val versionJson = versionName?.takeIf { it.isNotBlank() }
        ?.let { JsonPrimitive(it) }
        ?: JsonNull
    return buildJsonObject {
        put("label", JsonPrimitive(entry.label))
        put("package", JsonPrimitive(entry.packageName))
        put(
            "system",
            JsonPrimitive(application != null && (application.flags and ApplicationInfo.FLAG_SYSTEM) != 0),
        )
        put("versionName", versionJson)
    }
}
