package com.omni.aiassistant.device.tools.comm

import android.content.Context
import com.omni.aiassistant.core.agent.Tool
import com.omni.aiassistant.core.agent.ToolGroup
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.device.capability.CapabilityCenter

/**
 * Communication capability: contacts, calls and SMS.
 *
 * The dispatcher owns confirmation and timeouts; these tools only validate
 * arguments, check the capability and talk to the platform APIs.
 */
class CommunicationToolGroup(
    private val context: Context,
    private val capabilities: CapabilityCenter,
) : ToolGroup {

    override fun tools(): List<Tool> = listOf(
        ContactsSearchTool(context, capabilities),
        ContactCreateTool(context, capabilities),
        CallPlaceTool(context, capabilities),
        CallLogTool(context, capabilities),
        SmsSendTool(context, capabilities),
        SmsReadTool(context, capabilities),
        SmsSearchTool(context, capabilities),
    )
}

internal const val CONTACTS_LABEL = "Allow contacts access to search and create contacts"
internal const val PHONE_LABEL = "Allow phone access to place calls"
internal const val CALL_LOG_LABEL = "Allow call log access to read recent calls"
internal const val SMS_LABEL = "Allow SMS access to send and read messages"

/**
 * Convert an exception into a short model-readable failure. Exception messages
 * can be long or contain provider internals, so cap the whole sentence.
 */
internal fun safeFailure(prefix: String, error: Throwable): ToolOutcome.Failure {
    val detail = error.message
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.take(160)
        ?: error::class.simpleName
        ?: "unknown error"
    return ToolOutcome.Failure("$prefix: $detail".take(200))
}

/** Result payload bodies are capped; full SMS bodies stay on the device. */
internal fun String.truncateTo(max: Int): String = if (length <= max) this else take(max)
