package com.omni.aiassistant.device.tools.comm

import android.content.Context
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import com.omni.aiassistant.core.agent.Tool
import com.omni.aiassistant.core.agent.ToolArgs
import com.omni.aiassistant.core.agent.ToolArgs.int
import com.omni.aiassistant.core.agent.ToolArgs.string
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSchema
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.device.capability.Capability
import com.omni.aiassistant.device.capability.CapabilityCenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class SmsSendTool(
    private val context: Context,
    private val capabilities: CapabilityCenter,
) : Tool {

    override val spec = ToolSpec(
        name = "sms_send",
        description = "Send an SMS message with the device's default SIM. Always " +
            "confirm the recipient and the exact message text with the user before " +
            "calling this tool.",
        category = ToolCategory.COMMUNICATION,
        risk = ToolRisk.HIGH,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "number" to ToolSchema.string(
                    "Recipient phone number in international or local format",
                ),
                "body" to ToolSchema.string("Message body"),
            ),
            required = listOf("number", "body"),
        ),
    )

    override suspend fun execute(arguments: JsonObject): ToolOutcome {
        val rawNumber = arguments.string("number")?.trim()
            ?: return ToolOutcome.Failure("Missing required argument 'number'")
        val body = arguments.string("body")
            ?: return ToolOutcome.Failure("SMS body cannot be empty")
        val number = normalizeSmsNumber(rawNumber)
            ?: return ToolOutcome.Failure(
                "Invalid phone number. Use 3 to 20 digits with an optional leading +.",
            )
        if (body.length > MAX_BODY_CHARS) {
            return ToolOutcome.Failure("SMS body is too long (maximum $MAX_BODY_CHARS characters)")
        }
        if (!capabilities.isGranted(Capability.SMS)) {
            return ToolOutcome.NeedsPermission(Capability.SMS.name, SMS_LABEL)
        }

        return withContext<ToolOutcome>(Dispatchers.IO) {
            try {
                val manager = smsManager(context)
                    ?: return@withContext ToolOutcome.Failure("SMS service is unavailable")
                val parts = manager.divideMessage(body)
                    ?: return@withContext ToolOutcome.Failure(
                        "SMS body could not be split for sending",
                    )
                if (parts.isEmpty()) {
                    return@withContext ToolOutcome.Failure("SMS body could not be split for sending")
                }
                // The platform throws when it rejects the request; returning after
                // this call means the send was accepted, not that it was delivered.
                manager.sendMultipartTextMessage(number, null, parts, null, null)
                val data = buildJsonObject {
                    put("parts", parts.size)
                }
                ToolOutcome.Success("SMS send request accepted; delivery is not confirmed", data)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (security: SecurityException) {
                ToolOutcome.NeedsPermission(Capability.SMS.name, SMS_LABEL)
            } catch (error: Exception) {
                safeFailure("Could not send SMS", error)
            }
        }
    }

    private companion object {
        const val MAX_BODY_CHARS = 8_000
    }
}

internal class SmsReadTool(
    private val context: Context,
    private val capabilities: CapabilityCenter,
) : Tool {

    override val spec = ToolSpec(
        name = "sms_read",
        description = "Read recent inbox SMS messages, newest first. Optionally " +
            "filter by sender. Message bodies are truncated to 500 characters in the " +
            "result.",
        category = ToolCategory.COMMUNICATION,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "limit" to ToolSchema.integer(
                    description = "Maximum number of messages to return",
                    minimum = 1,
                    maximum = 50,
                    default = 20,
                ),
                "from" to ToolSchema.string("Optional sender number or partial number"),
            ),
        ),
    )

    override suspend fun execute(arguments: JsonObject): ToolOutcome {
        val limit = ToolArgs.clamp(
            arguments.int("limit") ?: 20,
            1,
            50,
        )
        val from = arguments.string("from")?.trim()
        if (from != null && from.length > 64) {
            return ToolOutcome.Failure("Sender filter is too long")
        }
        if (!capabilities.isGranted(Capability.SMS)) {
            return ToolOutcome.NeedsPermission(Capability.SMS.name, SMS_LABEL)
        }

        val selection = from?.let { "${Telephony.Sms.ADDRESS} LIKE ?" }
        val selectionArgs = from?.let { arrayOf("%$it%") }
        return withContext<ToolOutcome>(Dispatchers.IO) {
            try {
                val rows = queryInbox(context, selection, selectionArgs, limit + 1)
                val hasMore = rows.size > limit
                val messages = rows.take(limit)
                val data = buildJsonObject {
                    put("count", messages.size)
                    put("hasMore", hasMore)
                    put("messages", JsonArray(messages.map { it.toJson() }))
                }
                val summary = when (messages.size) {
                    0 -> "No SMS messages found"
                    1 -> "Read 1 SMS message"
                    else -> "Read ${messages.size} SMS messages"
                }
                ToolOutcome.Success(summary, data)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (security: SecurityException) {
                ToolOutcome.NeedsPermission(Capability.SMS.name, SMS_LABEL)
            } catch (error: Exception) {
                safeFailure("Could not read SMS messages", error)
            }
        }
    }
}

internal class SmsSearchTool(
    private val context: Context,
    private val capabilities: CapabilityCenter,
) : Tool {

    override val spec = ToolSpec(
        name = "sms_search",
        description = "Search inbox SMS messages by body text or sender. Message " +
            "bodies are truncated to 500 characters in the result.",
        category = ToolCategory.COMMUNICATION,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "query" to ToolSchema.string("Text to find in the message body or sender"),
                "limit" to ToolSchema.integer(
                    description = "Maximum number of messages to return",
                    minimum = 1,
                    maximum = 50,
                    default = 20,
                ),
            ),
            required = listOf("query"),
        ),
    )

    override suspend fun execute(arguments: JsonObject): ToolOutcome {
        val query = arguments.string("query")?.trim()
            ?: return ToolOutcome.Failure("Missing required argument 'query'")
        if (query.length > 200) {
            return ToolOutcome.Failure("Search query is too long")
        }
        val limit = ToolArgs.clamp(
            arguments.int("limit") ?: 20,
            1,
            50,
        )
        if (!capabilities.isGranted(Capability.SMS)) {
            return ToolOutcome.NeedsPermission(Capability.SMS.name, SMS_LABEL)
        }

        val selection =
            "(${Telephony.Sms.BODY} LIKE ? OR ${Telephony.Sms.ADDRESS} LIKE ?)"
        return withContext<ToolOutcome>(Dispatchers.IO) {
            try {
                val rows = queryInbox(
                    context,
                    selection,
                    arrayOf("%$query%", "%$query%"),
                    limit + 1,
                )
                val hasMore = rows.size > limit
                val messages = rows.take(limit)
                val data = buildJsonObject {
                    put("count", messages.size)
                    put("hasMore", hasMore)
                    put("messages", JsonArray(messages.map { it.toJson() }))
                }
                val summary = when (messages.size) {
                    0 -> "No SMS messages found"
                    1 -> "Found 1 SMS message"
                    else -> "Found ${messages.size} SMS messages"
                }
                ToolOutcome.Success(summary, data)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (security: SecurityException) {
                ToolOutcome.NeedsPermission(Capability.SMS.name, SMS_LABEL)
            } catch (error: Exception) {
                safeFailure("Could not search SMS messages", error)
            }
        }
    }
}

private data class SmsRow(
    val id: Long,
    val address: String?,
    val body: String,
    val timestamp: Long,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("from", address)
        put("body", body.truncateTo(MAX_BODY_CHARS))
        put("truncated", body.length > MAX_BODY_CHARS)
        put("timestamp", timestamp)
    }

    private companion object {
        const val MAX_BODY_CHARS = 500
    }
}

private fun queryInbox(
    context: Context,
    selection: String?,
    selectionArgs: Array<String>?,
    limit: Int,
): List<SmsRow> {
    val rows = mutableListOf<SmsRow>()
    context.contentResolver.query(
        Telephony.Sms.Inbox.CONTENT_URI,
        arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
        ),
        selection,
        selectionArgs,
        "${Telephony.Sms.DATE} DESC LIMIT ${limit.coerceIn(1, 51)}",
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
        val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
        val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
        val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
        while (cursor.moveToNext()) {
            if (rows.size >= limit) break
            rows += SmsRow(
                id = if (cursor.isNull(idIndex)) -1L else cursor.getLong(idIndex),
                address = if (cursor.isNull(addressIndex)) null else {
                    cursor.getString(addressIndex)?.takeIf { it.isNotBlank() }?.truncateTo(64)
                },
                body = if (cursor.isNull(bodyIndex)) "" else {
                    cursor.getString(bodyIndex).take(501)
                },
                timestamp = if (cursor.isNull(dateIndex)) 0L else cursor.getLong(dateIndex),
            )
        }
    }
    return rows
}

/**
 * Keep only characters that are meaningful in a phone number, then require a
 * plausible digit count. This rejects letters and URI injection while allowing
 * common formatting.
 */
private fun normalizeSmsNumber(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.length !in 3..40) return null
    val allowed = trimmed.filter { character ->
        character.isDigit() || character == '+' || character.isWhitespace() ||
            character == '-' || character == '(' || character == ')' || character == '.'
    }
    if (allowed.length != trimmed.length) return null
    val address = allowed.filter { it.isDigit() || it == '+' }
    if (!address.matches(Regex("^\\+?[0-9]{3,20}$"))) return null
    return address
}

@Suppress("DEPRECATION")
private fun smsManager(context: Context): SmsManager? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(SmsManager::class.java)
    } else {
        SmsManager.getDefault()
    }
