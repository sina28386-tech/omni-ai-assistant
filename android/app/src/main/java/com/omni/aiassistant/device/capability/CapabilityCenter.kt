package com.omni.aiassistant.device.capability

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Every capability Omni can use on the phone, in the order the setup screen
 * should present them: low-friction first, invasive last.
 */
enum class Capability {
    NOTIFICATIONS,
    LOCATION,
    CAMERA,
    CONTACTS,
    PHONE,
    SMS,
    CALENDAR,
    CALL_LOG,
    MEDIA,
    STORAGE,
    WRITE_SETTINGS,
    DND,
    ACCESSIBILITY,
    NOTIFICATION_ACCESS,
}

data class CapabilityStatus(
    val capability: Capability,
    val granted: Boolean,
    /** True when the only way to grant it is a system settings screen. */
    val requiresSettings: Boolean,
)

/**
 * Single source of truth for "can the assistant do X right now". Tools call the
 * same checks before executing, so the UI never promises a capability the
 * dispatcher will reject.
 */
class CapabilityCenter(private val context: Context) {

    fun status(capability: Capability): CapabilityStatus {
        val requiresSettings = capability in setOf(
            Capability.STORAGE,
            Capability.WRITE_SETTINGS,
            Capability.DND,
            Capability.ACCESSIBILITY,
            Capability.NOTIFICATION_ACCESS,
        )
        return CapabilityStatus(capability, isGranted(capability), requiresSettings)
    }

    fun statuses(): List<CapabilityStatus> = Capability.entries.map(::status)

    fun isGranted(capability: Capability): Boolean = when (capability) {
        Capability.NOTIFICATIONS ->
            Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)

        Capability.LOCATION ->
            granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

        Capability.CAMERA -> granted(Manifest.permission.CAMERA)

        Capability.CONTACTS -> granted(Manifest.permission.READ_CONTACTS)

        Capability.PHONE -> granted(Manifest.permission.CALL_PHONE) && granted(Manifest.permission.READ_PHONE_STATE)

        Capability.SMS -> granted(Manifest.permission.SEND_SMS) && granted(Manifest.permission.READ_SMS)

        Capability.CALENDAR -> granted(Manifest.permission.READ_CALENDAR) && granted(Manifest.permission.WRITE_CALENDAR)

        Capability.CALL_LOG -> granted(Manifest.permission.READ_CALL_LOG)

        Capability.MEDIA -> if (Build.VERSION.SDK_INT >= 33) {
            granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(Manifest.permission.READ_MEDIA_VIDEO) ||
                granted(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        Capability.STORAGE -> if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            granted(Manifest.permission.READ_EXTERNAL_STORAGE) &&
                granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        Capability.WRITE_SETTINGS -> Settings.System.canWrite(context)

        Capability.DND -> {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.isNotificationPolicyAccessGranted
        }

        Capability.ACCESSIBILITY -> enabledAccessibilityServices().any { it.contains(ACCESSIBILITY_SUFFIX) }

        Capability.NOTIFICATION_ACCESS ->
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    /** Android 6+ runtime permissions for the capability, if any. */
    fun runtimePermissions(capability: Capability): List<String> = when (capability) {
        Capability.NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

        Capability.LOCATION -> listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        Capability.CAMERA -> listOf(Manifest.permission.CAMERA)
        Capability.CONTACTS -> listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        Capability.PHONE -> listOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
        )
        Capability.CALL_LOG -> listOf(Manifest.permission.READ_CALL_LOG)
        Capability.SMS -> listOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
        )
        Capability.CALENDAR -> listOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        )
        Capability.MEDIA -> if (Build.VERSION.SDK_INT >= 33) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        Capability.STORAGE -> if (Build.VERSION.SDK_INT >= 30) {
            emptyList()
        } else {
            listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
        }
        Capability.WRITE_SETTINGS,
        Capability.DND,
        Capability.ACCESSIBILITY,
        Capability.NOTIFICATION_ACCESS,
        -> emptyList()
    }

    /**
     * Settings intent for capabilities that cannot be granted by a runtime
     * dialog. Returns null for runtime-only capabilities; those must go through
     * `ActivityResultContracts.RequestMultiplePermissions`.
     */
    fun settingsIntent(capability: Capability): Intent? = when (capability) {
        Capability.STORAGE -> if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        } else {
            null
        }

        Capability.WRITE_SETTINGS ->
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))

        Capability.DND -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

        Capability.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

        Capability.NOTIFICATION_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

        else -> null
    }

    fun settingsGesture(): Boolean = isGranted(Capability.STORAGE) || Settings.System.canWrite(context)

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun enabledAccessibilityServices(): List<String> {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return raw.split(':').filter { it.isNotBlank() }
    }

    companion object {
        /** Class names are strings so capability checks compile before the services exist. */
        const val ACCESSIBILITY_SERVICE =
            "com.omni.aiassistant.device.accessibility.OmniAccessibilityService"
        const val NOTIFICATION_SERVICE =
            "com.omni.aiassistant.device.notifications.OmniNotificationListener"

        private const val ACCESSIBILITY_SUFFIX = "$ACCESSIBILITY_SERVICE"
    }
}
