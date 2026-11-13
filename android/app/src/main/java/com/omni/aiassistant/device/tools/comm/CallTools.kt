package com.omni.aiassistant.device.tools.comm

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
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

internal class CallPlaceTool(
    private val context: Context,
    private val capabilities: CapabilityCenter,
) : Tool {

    override val spec = ToolSpec(
        name = "call_place",
        description = "Start a real phone call now. Provide a phone number, or a " +
            "contact name to resolve first. If the name matches more than one contact " +
            "or number the tool fails and asks for a specific number. This does not " +
            "open the dialer for the user to press call.",
        category = ToolCategory.COMMUNICATION,
        risk = ToolRisk.HIGH,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "number" to ToolSchema.string("Phone number to call"),
                "contact_name" to ToolSchema.string(
                    "Contact name to look up when no number is given",
                ),
            ),
        ),
    )

    override suspend fun execute(arguments: JsonObject): ToolOutcome {
        val numberArgument = arguments.string("number")?.trim()
        val contactName = arguments.string("contact_name")?.trim()
        if (numberArgument == null && contactName != null && contactName.length > 100) {
            return ToolOutcome.Failure("Contact name is too long")
        }
        if (numberArgument == null && contactName == null) {
            return ToolOutcome.Failure("Provide a phone number or a contact name")
        }
        if (!capabilities.isGranted(Capability.PHONE)) {
            return ToolOutcome.NeedsPermission(Capability.PHONE.name, PHONE_LABEL)
        }

        return withContext<ToolOutcome>(Dispatchers.IO) {
            try {
                var resolvedName: String? = null
                val rawNumber = if (numberArgument != null) {
                    numberArgument
                } else {
                    if (!capabilities.isGranted(Capability.CONTACTS)) {
                        return@withContext ToolOutcome.NeedsPermission(
                            Capability.CONTACTS.name,
                            CONTACTS_LABEL,
                        )
                    }
                    when (val resolved = resolveContactNumber(context, contactName!!)) {
                        is ContactResolution.Found -> {
                            resolvedName = resolved.name
                            resolved.number
                        }
                        ContactResolution.NotFound ->
                            return@withContext ToolOutcome.Failure(
                                "No contact found with that name",
                            )
                        ContactResolution.Ambiguous ->
                            return@withContext ToolOutcome.Failure(
                                "Multiple contacts or numbers match that name. " +
                                    "Ask the user which number to call.",
                            )
                        ContactResolution.PermissionDenied ->
                            return@withContext ToolOutcome.NeedsPermission(
                                Capability.CONTACTS.name,
                                CONTACTS_LABEL,
                            )
                    }
                }

                val dialable = dialableNumber(rawNumber)
                    ?: return@withContext ToolOutcome.Failure(
                        "Enter a valid phone number with at least 3 digits",
                    )
                val intent = Intent(Intent.ACTION_CALL, Uri.fromParts("tel", dialable, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)

                val data = buildJsonObject {
                    put("number", dialable)
                    resolvedName?.let { put("contactName", it.truncateTo(200)) }
                }
                ToolOutcome.Success("Call started", data)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (security: SecurityException) {
                ToolOutcome.NeedsPermission(Capability.PHONE.name, PHONE_LABEL)
            } catch (missingDialer: ActivityNotFoundException) {
                ToolOutcome.Failure("No phone app is available to place the call")
            } catch (error: Exception) {
                safeFailure("Could not start the call", error)
            }
        }
    }

    private sealed interface ContactResolution {
        data class Found(val number: String, val name: String?) : ContactResolution
        data object NotFound : ContactResolution
        data object Ambiguous : ContactResolution
        data object PermissionDenied : ContactResolution
    }

    private fun resolveContactNumber(
        context: Context,
        name: String,
    ): ContactResolution = try {
        // Prefer an exact (case-insensitive) name match; fall back to contains.
        val exact = queryContactMatches(context, name, exact = true)
        val matches = exact.ifEmpty { queryContactMatches(context, name, exact = false) }
        when {
            matches.isEmpty() -> ContactResolution.NotFound
            matches.size == 1 -> matches.first()
            else -> ContactResolution.Ambiguous
        }
    } catch (security: SecurityException) {
        ContactResolution.PermissionDenied
    }

    private fun queryContactMatches(
        context: Context,
        name: String,
        exact: Boolean,
    ): List<ContactResolution.Found> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val matches = LinkedHashMap<String, ContactResolution.Found>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf(if (exact) name else "%$name%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC LIMIT 20",
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            )
            val nameIndex = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            )
            val numberIndex = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            )
            while (cursor.moveToNext()) {
                val number = if (cursor.isNull(numberIndex)) null else {
                    cursor.getString(numberIndex)?.takeIf { it.isNotBlank() }
                }
                if (number.isNullOrBlank()) continue
                val contactId = if (cursor.isNull(idIndex)) null else cursor.getLong(idIndex)
                val displayName = if (cursor.isNull(nameIndex)) null else {
                    cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }
                }
                // One contact can have several numbers; any two distinct matches
                // are ambiguous, so never guess.
                val key = "${contactId ?: "unknown"}:${number.filter { it.isDigit() }}"
                if (!matches.containsKey(key)) {
                    matches[key] = ContactResolution.Found(number, displayName)
                }
            }
        }
        return matches.values.toList()
    }
}

internal class CallLogTool(
    private val context: Context,
    private val capabilities: CapabilityCenter,
) : Tool {

    override val spec = ToolSpec(
        name = "call_log",
        description = "Read recent call log entries, newest first. Filter by all, " +
            "missed, incoming or outgoing. Returns number, cached contact name, type, " +
            "duration in seconds and timestamp.",
        category = ToolCategory.COMMUNICATION,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "limit" to ToolSchema.integer(
                    description = "Maximum number of call log entries to return",
                    minimum = 1,
                    maximum = 50,
                    default = 20,
                ),
                "filter" to ToolSchema.string(
                    description = "Which calls to include",
                    choices = listOf("all", "missed", "incoming", "outgoing"),
                    default = "all",
                ),
            ),
        ),
    )

    override suspend fun execute(arguments: JsonObject): ToolOutcome {
        val limit = ToolArgs.clamp(
            arguments.int("limit") ?: 20,
            1,
            50,
        )
        val filter = (arguments.string("filter") ?: "all").trim().lowercase()
        if (filter !in FILTERS) {
            return ToolOutcome.Failure("filter must be all, missed, incoming or outgoing")
        }
        if (!capabilities.isGranted(Capability.CALL_LOG)) {
            return ToolOutcome.NeedsPermission(Capability.CALL_LOG.name, CALL_LOG_LABEL)
        }

        return withContext<ToolOutcome>(Dispatchers.IO) {
            try {
                val selection = when (filter) {
                    "missed" -> "${CallLog.Calls.TYPE} = ?"
                    "incoming" -> "${CallLog.Calls.TYPE} = ?"
                    "outgoing" -> "${CallLog.Calls.TYPE} = ?"
                    else -> null
                }
                val selectionArgs = when (filter) {
                    "missed" -> arrayOf(CallLog.Calls.MISSED_TYPE.toString())
                    "incoming" -> arrayOf(CallLog.Calls.INCOMING_TYPE.toString())
                    "outgoing" -> arrayOf(CallLog.Calls.OUTGOING_TYPE.toString())
                    else -> null
                }
                val calls = mutableListOf<JsonObject>()
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(
                        CallLog.Calls._ID,
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.CACHED_NAME,
                        CallLog.Calls.TYPE,
                        CallLog.Calls.DURATION,
                        CallLog.Calls.DATE,
                    ),
                    selection,
                    selectionArgs,
                    "${CallLog.Calls.DATE} DESC LIMIT $limit",
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                    val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                    val nameIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                    val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                    val durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                    val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                    while (cursor.moveToNext()) {
                        if (calls.size >= limit) break
                        val type = when (cursor.getInt(typeIndex)) {
                            CallLog.Calls.MISSED_TYPE -> "missed"
                            CallLog.Calls.INCOMING_TYPE -> "incoming"
                            CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                            else -> "other"
                        }
                        calls += buildJsonObject {
                            put("id", cursor.getLong(idIndex))
                            put(
                                "number",
                                if (cursor.isNull(numberIndex)) null else {
                                    cursor.getString(numberIndex)?.takeIf { it.isNotBlank() }
                                        ?.truncateTo(64)
                                },
                            )
                            put(
                                "name",
                                if (cursor.isNull(nameIndex)) null else {
                                    cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }
                                        ?.truncateTo(200)
                                },
                            )
                            put("type", type)
                            put("durationSec", cursor.getLong(durationIndex))
                            put("timestamp", cursor.getLong(dateIndex))
                        }
                    }
                }

                val data = buildJsonObject {
                    put("filter", filter)
                    put("count", calls.size)
                    put("calls", JsonArray(calls))
                }
                val summary = when (calls.size) {
                    0 -> "No call log entries found"
                    1 -> "Read 1 call log entry"
                    else -> "Read ${calls.size} call log entries"
                }
                ToolOutcome.Success(summary, data)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (security: SecurityException) {
                ToolOutcome.NeedsPermission(Capability.CALL_LOG.name, CALL_LOG_LABEL)
            } catch (error: Exception) {
                safeFailure("Could not read the call log", error)
            }
        }
    }

    private companion object {
        val FILTERS = setOf("all", "missed", "incoming", "outgoing")
    }
}

private fun dialableNumber(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.length !in 3..40) return null
    val allowed = trimmed.filter { character ->
        character.isDigit() || character == '+' || character == '*' || character == '#' ||
            character == ' ' || character == '-' || character == '(' ||
            character == ')' || character == '.'
    }
    if (allowed.length != trimmed.length) return null
    val dialable = allowed.filter {
        it.isDigit() || it == '+' || it == '*' || it == '#'
    }
    if (dialable.count { it.isDigit() } < 3) return null
    val plusIndex = dialable.indexOf('+')
    if (plusIndex > 0 || dialable.count { it == '+' } > 1) return null
    return dialable
}
