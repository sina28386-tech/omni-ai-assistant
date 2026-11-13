package com.omni.aiassistant.device.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.omni.aiassistant.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Restarts the wake-word listener after a reboot or an app update, but only
 * when the user had it switched on.
 */
class WakeBootReceiver : BroadcastReceiver(), KoinComponent {

    private val settings: SettingsRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val enabled = runCatching { settings.snapshot().wakeWordEnabled }.getOrDefault(false)
                if (enabled) ArmanWakeService.start(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
