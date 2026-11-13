package com.omni.aiassistant.device

import com.omni.aiassistant.R

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs

class DeviceInfoProvider(private val context: Context) {
    fun report(): String {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0) level * 100 / scale.coerceAtLeast(1) else -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val stat = StatFs(context.filesDir.absolutePath)
        val freeMb = stat.availableBytes / (1024 * 1024)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork
        val caps = network?.let(connectivity::getNetworkCapabilities)
        val networkName = context.getString(when {
            caps == null -> R.string.network_offline
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> R.string.network_wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> R.string.network_cellular
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> R.string.network_ethernet
            else -> R.string.network_connected
        })
        return listOf(
            context.getString(R.string.device_manufacturer_value, Build.MANUFACTURER),
            context.getString(R.string.device_model_value, Build.MODEL),
            context.getString(R.string.device_android_value, Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
            context.getString(R.string.device_battery_value, if (percent >= 0) "$percent%" else context.getString(R.string.unknown)),
            context.getString(R.string.device_charging_value, context.getString(if (charging) R.string.yes else R.string.no)),
            context.getString(R.string.device_storage_value, freeMb),
            context.getString(R.string.device_network_value, networkName),
        ).joinToString("\n")
    }
}
