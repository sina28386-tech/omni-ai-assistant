package com.omni.aiassistant.device

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class DeviceActionExecutor(private val context: Context) {
    fun execute(action: DeviceAction): Result<Unit> = runCatching {
        val intent = when (action) {
            is DeviceAction.WebSearch -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${encode(action.query)}"))
            is DeviceAction.Dial -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(action.phoneNumber)}"))
            is DeviceAction.Sms -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(action.phoneNumber)}")).apply { putExtra("sms_body", action.body) }
            is DeviceAction.Email -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(action.address)}")).apply { putExtra(Intent.EXTRA_SUBJECT, action.subject) }
            DeviceAction.Camera -> Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            DeviceAction.Gallery -> Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
            DeviceAction.Contacts -> Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
            is DeviceAction.Maps -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(action.query)}"))
            DeviceAction.AppSettings -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            DeviceAction.DeviceInfo -> error("DeviceInfo is handled inside the app")
            is DeviceAction.FindFile, is DeviceAction.PlayFile,
            is DeviceAction.DownloadFile, is DeviceAction.DeleteFile,
            is DeviceAction.SetWallpaper ->
                error("File actions run through FileAgentRunner, never as plain intents")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw IllegalStateException("No installed app can handle this action", e)
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
