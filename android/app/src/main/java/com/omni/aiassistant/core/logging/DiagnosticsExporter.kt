package com.omni.aiassistant.core.logging

import android.content.Context
import android.net.Uri
import android.os.Build
import com.omni.aiassistant.BuildConfig
import com.omni.aiassistant.data.settings.SettingsRepository

class DiagnosticsExporter(
    private val context: Context,
    private val logger: SafeLogger,
    private val settings: SettingsRepository,
) {
    suspend fun export(uri: Uri) {
        val prefs = settings.snapshot()
        val body = buildString {
            appendLine("Omni AI Assistant diagnostics")
            appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("debug=${BuildConfig.DEBUG}")
            appendLine("android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("theme=${prefs.theme} language=${prefs.language}")
            appendLine("streaming=${prefs.streaming} creditSaver=${prefs.creditSaver}")
            appendLine("localDevelopment=${prefs.localDevelopmentEndpoint}")
            appendLine("agentMode=${prefs.agentMode} toolAudit=${prefs.toolAuditEnabled}")
            appendLine("fullPhoneControl=${prefs.fullPhoneControl}")
            appendLine("trustedToolCount=${prefs.trustedTools.size}")
            appendLine("--- safe log ---")
            logger.snapshot().forEach(::appendLine)
        }
        context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { it.write(body) }
            ?: error("Unable to open diagnostics destination")
    }
}
