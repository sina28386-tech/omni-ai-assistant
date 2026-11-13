package com.omni.aiassistant.device.tools.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.omni.aiassistant.core.agent.ToolArgs.string
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSchema
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.device.capability.CapabilityCenter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

internal class ShareTextTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "share_text",
        description = "Open the Android share sheet with plain text and let the user pick a target app.",
        category = ToolCategory.SYSTEM,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "text" to ToolSchema.string("Text to share, up to 8000 characters"),
            ),
            required = listOf("text"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val raw = arguments.requireTextArg("text")
        val text = raw.take(MAX_RESULT_CHARS)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, "Share text")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(chooser)
            ToolOutcome.Success(
                summary = if (raw.length > text.length) {
                    "Opened the share sheet with $MAX_RESULT_CHARS characters (truncated)"
                } else {
                    "Opened the share sheet with ${text.length} characters"
                },
                data = buildJsonObject { put("length", text.length) },
            )
        } catch (notFound: ActivityNotFoundException) {
            ToolOutcome.Failure("No app can receive shared text on this device")
        }
    }
}

internal class OpenUrlTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "open_url",
        description = "Open an http or https link in the default browser. Other schemes are rejected.",
        category = ToolCategory.WEB,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "url" to ToolSchema.string("Absolute http:// or https:// URL"),
            ),
            required = listOf("url"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val raw = arguments.requireTextArg("url").trim().take(2_048)
        if (raw.contains('\n') || raw.contains('\r')) {
            return ToolOutcome.Failure("The URL contains invalid characters")
        }
        val lower = raw.lowercase(Locale.US)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return ToolOutcome.Failure("Only http and https links are allowed")
        }
        val uri = Uri.parse(raw)
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            return ToolOutcome.Failure("Only http and https links are allowed")
        }
        val host = uri.host
        if (host.isNullOrBlank() || host.any { it.isWhitespace() }) {
            return ToolOutcome.Failure("The URL has no valid host")
        }

        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ToolOutcome.Success(
                summary = "Opened link to ${host.take(120)}",
                data = buildJsonObject {
                    put("scheme", scheme)
                    put("host", host)
                },
            )
        } catch (notFound: ActivityNotFoundException) {
            ToolOutcome.Failure("No browser is installed on this device")
        }
    }
}

internal class WebSearchTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "web_search",
        description = "Open a Google search for a query in the default browser.",
        category = ToolCategory.WEB,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "query" to ToolSchema.string("Search query"),
            ),
            required = listOf("query"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val query = arguments.requireTextArg("query").trim().take(500)
        val uri = Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            ToolOutcome.Success(
                summary = "Opened a web search for \"${query.take(80)}\"",
                data = buildJsonObject {
                    put("query", query)
                    put("engine", "google")
                },
            )
        } catch (notFound: ActivityNotFoundException) {
            ToolOutcome.Failure("No browser is installed on this device")
        }
    }
}

internal class MapsSearchTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "maps_search",
        description = "Open a place or address search in the default maps app.",
        category = ToolCategory.WEB,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "query" to ToolSchema.string("Place name or address"),
            ),
            required = listOf("query"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val query = arguments.requireTextArg("query").trim().take(300)
        val uri = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            ToolOutcome.Success(
                summary = "Opened a maps search for \"${query.take(80)}\"",
                data = buildJsonObject { put("query", query) },
            )
        } catch (notFound: ActivityNotFoundException) {
            ToolOutcome.Failure("No maps app is installed on this device")
        }
    }
}

internal class EmailComposeTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(context, capabilities) {

    override val spec = ToolSpec(
        name = "email_compose",
        description = "Open the email composer with a recipient, optional subject and optional body. " +
            "The user still has to send the message.",
        category = ToolCategory.COMMUNICATION,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "to" to ToolSchema.string("Recipient email address"),
                "subject" to ToolSchema.string("Optional subject line"),
                "body" to ToolSchema.string("Optional message body"),
            ),
            required = listOf("to"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val to = arguments.requireTextArg("to").trim().take(320)
        if (to.contains('\n') || to.contains('\r')) {
            return ToolOutcome.Failure("The recipient address contains invalid characters")
        }
        val subject = arguments.string("subject")?.trim()?.take(200)
        val body = arguments.string("body")?.take(MAX_RESULT_CHARS)

        val intent = Intent(
            Intent.ACTION_SENDTO,
            Uri.fromParts("mailto", to, null),
        ).apply {
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            body?.let { putExtra(Intent.EXTRA_TEXT, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            ToolOutcome.Success(
                summary = "Opened the email composer",
                data = buildJsonObject {
                    put("to", to)
                    subject?.let { put("subject", it) }
                },
            )
        } catch (notFound: ActivityNotFoundException) {
            ToolOutcome.Failure("No email app is installed on this device")
        }
    }
}
