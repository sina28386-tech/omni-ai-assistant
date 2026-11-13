package com.omni.aiassistant.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.omni.aiassistant.R
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One human readable fact extracted from a tool result.
 *
 * Tool payloads are machine data. The chat timeline shows a localized
 * one-line summary instead of the raw English sentence the model receives, so
 * the Persian UI never mixes languages.
 */
data class ToolFact(val label: String, val value: String)

/**
 * Extracts the handful of facts that every phone assistant actually shows:
 * battery, storage, sound mode, network, screen state and the device itself.
 *
 * Unknown shapes fall back to an empty list, and the caller then uses the raw
 * summary from the tool.
 */
fun extractToolFacts(payload: String): List<ToolFact> {
    val obj = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(payload).jsonObject }
        .getOrNull() ?: return emptyList()
    val data = (obj["data"] as? JsonObject) ?: return emptyList()
    val facts = mutableListOf<ToolFact>()

    (data["battery"] as? JsonObject)?.let { battery ->
        (battery["percent"] as? JsonPrimitive)?.contentOrNull
            ?.toIntOrNull()
            ?.let { facts += ToolFact("battery", it.toString()) }
        (battery["charging"] as? JsonPrimitive)?.booleanOrNull?.let {
            facts += ToolFact("charging", if (it) "on" else "off")
        }
    }
    (data["storage"] as? JsonObject)?.let { storage ->
        (storage["free"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { facts += ToolFact("storage_free", it) }
        (storage["total"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { facts += ToolFact("storage_total", it) }
    }
    (data["ringer"] as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotBlank() && it != "unknown" }
        ?.let { facts += ToolFact("ringer", it) }
    (data["network"] as? JsonObject)?.let { network ->
        (network["type"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() && it != "unknown" }
            ?.let { facts += ToolFact("network", it) }
        (network["signal"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { facts += ToolFact("signal", it) }
        (network["carrier"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { facts += ToolFact("carrier", it) }
    }
    (data["screenOn"] as? JsonPrimitive)?.booleanOrNull?.let {
        facts += ToolFact("screen", if (it) "on" else "off")
    }
    (data["uptime"] as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?.let { facts += ToolFact("uptime", it) }
    val model = (data["model"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    val android = (data["android"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    if (model != null) {
        facts += ToolFact("device", listOfNotNull(model, android).joinToString(" · "))
    }
    return facts.take(4)
}

/** Localizes one fact. Unknown fact keys return null so the caller can skip. */
@Composable
private fun localizeFact(fact: ToolFact): String? = when (fact.label) {
    "battery" -> stringResource(R.string.chat_fact_battery, fact.value)
    "charging" -> stringResource(
        if (fact.value == "on") R.string.chat_fact_charging else R.string.chat_fact_not_charging,
    )
    "storage_free" -> stringResource(R.string.chat_fact_storage_free, fact.value)
    "storage_total" -> stringResource(R.string.chat_fact_storage_total, fact.value)
    "ringer" -> stringResource(
        when (fact.value.lowercase()) {
            "normal" -> R.string.chat_fact_ringer_normal
            "vibrate" -> R.string.chat_fact_ringer_vibrate
            "silent" -> R.string.chat_fact_ringer_silent
            else -> R.string.chat_fact_ringer_other
        },
        fact.value,
    )
    "network" -> stringResource(R.string.chat_fact_network, fact.value)
    "signal" -> stringResource(R.string.chat_fact_signal, fact.value)
    "carrier" -> stringResource(R.string.chat_fact_carrier, fact.value)
    "screen" -> stringResource(
        if (fact.value == "on") R.string.chat_fact_screen_on else R.string.chat_fact_screen_off,
    )
    "uptime" -> stringResource(R.string.chat_fact_uptime, fact.value)
    "device" -> stringResource(R.string.chat_fact_device, fact.value)
    else -> null
}

/**
 * Builds the localized one-line summary of a tool result. Falls back to the
 * tool's own English summary when nothing structured is available.
 */
@Composable
internal fun localizedToolSummary(payload: String, fallback: String): String {
    val facts = extractToolFacts(payload)
    if (facts.isEmpty()) return fallback
    val localized = facts.mapNotNull { localizeFact(it) }
    if (localized.isEmpty()) return fallback
    return localized.joinToString(" · ")
}

/** Best-effort extraction of a plain list of strings (search results, apps…). */
internal fun extractListFacts(payload: String, key: String): List<String> {
    val obj = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(payload).jsonObject }
        .getOrNull() ?: return emptyList()
    val data = (obj["data"] as? JsonObject) ?: return emptyList()
    val array = (data[key] as? JsonArray) ?: return emptyList()
    return array.mapNotNull { element ->
        when (element) {
            is JsonPrimitive -> element.contentOrNull
            is JsonObject -> (element["name"] as? JsonPrimitive)?.contentOrNull
                ?: (element["title"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }
    }.filter { it.isNotBlank() }.take(5)
}

/** Reads a scalar string from the tool payload data object. */
internal fun dataString(payload: String, path: String): String? {
    val obj = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(payload).jsonObject }
        .getOrNull() ?: return null
    var current: JsonElement = obj["data"] ?: return null
    for (segment in path.split('.')) {
        val asObject = current as? JsonObject ?: return null
        current = asObject[segment] ?: return null
    }
    return (current as? JsonPrimitive)?.contentOrNull
}
