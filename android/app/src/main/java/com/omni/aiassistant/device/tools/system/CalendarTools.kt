package com.omni.aiassistant.device.tools.system

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.omni.aiassistant.core.agent.ToolArgs.string
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSchema
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.device.capability.Capability
import com.omni.aiassistant.device.capability.CapabilityCenter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

internal class CalendarEventsTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(
    context = context,
    capabilities = capabilities,
    checkedCapability = Capability.CALENDAR,
    capabilityLabel = "Allow calendar access",
) {

    override val spec = ToolSpec(
        name = "calendar_events",
        description = "List calendar events in the next day, week or 30 days. Returns at most 50 " +
            "events with start/end times in ISO-8601 device-local time and an optional title filter.",
        category = ToolCategory.SYSTEM,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "range" to ToolSchema.string(
                    "Range to list, starting today",
                    choices = listOf("today", "week", "month"),
                    default = "today",
                ),
                "query" to ToolSchema.string("Optional text to match in the event title"),
            ),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val range = (arguments.string("range") ?: "today").lowercase(Locale.US)
        if (range !in RANGES) return ToolOutcome.Failure("range must be today, week or month")
        val search = arguments.string("query")?.take(100)

        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val begin = start.timeInMillis
        start.add(
            Calendar.DAY_OF_YEAR,
            when (range) {
                "today" -> 1
                "week" -> 7
                else -> 30
            },
        )
        val end = start.timeInMillis

        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, begin)
        ContentUris.appendId(uriBuilder, end)
        val uri = uriBuilder.build()

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val selection = if (search != null) "${CalendarContract.Instances.TITLE} LIKE ?" else null
        val selectionArgs = if (search != null) arrayOf("%$search%") else null

        val events = mutableListOf<JsonObject>()
        var payloadChars = 0
        var payloadTruncated = false
        var hitLimit = false

        val cursor = context.contentResolver.query(
            uri,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Instances.BEGIN} ASC",
        ) ?: return ToolOutcome.Failure("The calendar provider is unavailable")

        cursor.use {
            val idColumn = it.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val titleColumn = it.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val beginColumn = it.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endColumn = it.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val allDayColumn = it.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val locationColumn = it.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)

            while (it.moveToNext()) {
                if (events.size >= MAX_RESULT_ITEMS) {
                    hitLimit = true
                    break
                }
                val eventBegin = it.getLong(beginColumn)
                val eventEnd = it.getLong(endColumn)
                val location = if (it.isNull(locationColumn)) {
                    null
                } else {
                    it.getString(locationColumn)?.take(160)
                }
                val event = buildJsonObject {
                    put("eventId", it.getLong(idColumn))
                    put("title", it.getString(titleColumn).orEmpty().take(160))
                    put("start", formatDateTime(eventBegin))
                    put("end", formatDateTime(eventEnd))
                    put("allDay", it.getInt(allDayColumn) != 0)
                    location?.let { value -> put("location", value) }
                }
                val eventChars = event.toString().length
                if (payloadChars + eventChars > MAX_RESULT_CHARS - 500) {
                    payloadTruncated = true
                    break
                }
                payloadChars += eventChars
                events.add(event)
            }
        }

        val truncated = payloadTruncated || hitLimit
        val countText = if (hitLimit) "${events.size}+" else events.size.toString()
        val summary = "Found $countText calendar event${if (events.size == 1) "" else "s"} for $range"
            .take(200)

        return ToolOutcome.Success(
            summary = summary,
            data = buildJsonObject {
                put("range", range)
                put("from", formatDateTime(begin))
                put("to", formatDateTime(end))
                put("count", events.size)
                put("truncated", truncated)
                put("events", JsonArray(events))
            },
        )
    }

    private companion object {
        val RANGES = setOf("today", "week", "month")
    }
}

internal class CalendarCreateTool(
    context: Context,
    capabilities: CapabilityCenter,
) : SystemTool(
    context = context,
    capabilities = capabilities,
    checkedCapability = Capability.CALENDAR,
    capabilityLabel = "Allow calendar access",
) {

    override val spec = ToolSpec(
        name = "calendar_create",
        description = "Create a calendar event on the first writable calendar. start_iso and " +
            "end_iso must use 'yyyy-MM-dd'T'HH:mm' or 'yyyy-MM-dd HH:mm' in the device's local " +
            "timezone. end_iso defaults to one hour after start_iso. All-day events are not supported.",
        category = ToolCategory.SYSTEM,
        risk = ToolRisk.MEDIUM,
        parameters = ToolSchema.objectOf(
            properties = mapOf(
                "title" to ToolSchema.string("Event title"),
                "start_iso" to ToolSchema.string("Start time, e.g. 2026-10-21T18:30 or 2026-10-21 18:30"),
                "end_iso" to ToolSchema.string("Optional end time in the same formats"),
                "location" to ToolSchema.string("Optional event location"),
                "notes" to ToolSchema.string("Optional event description"),
            ),
            required = listOf("title", "start_iso"),
        ),
    )

    override suspend fun run(arguments: JsonObject): ToolOutcome {
        val title = arguments.requireTextArg("title").trim().take(200)
        val startRaw = arguments.requireTextArg("start_iso")
        val endRaw = arguments.string("end_iso")
        val location = arguments.string("location")?.trim()?.take(300)
        val notes = arguments.string("notes")?.take(4_000)

        val start = parseDateTime(startRaw)
            ?: return ToolOutcome.Failure(
                "start_iso must look like 2026-10-21T18:30 or 2026-10-21 18:30",
            )
        val end = if (endRaw != null) {
            parseDateTime(endRaw)
                ?: return ToolOutcome.Failure(
                    "end_iso must look like 2026-10-21T18:30 or 2026-10-21 18:30",
                )
        } else {
            start + 3_600_000L
        }
        if (end <= start) return ToolOutcome.Failure("end_iso must be after start_iso")

        var calendarId = -1L
        var calendarName = ""
        val selection = buildString {
            append(CalendarContract.Calendars.VISIBLE)
            append(" = 1 AND ")
            append(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            append(" >= ?")
        }
        val selectionArgs = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        listOf(selection, "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?").forEach { where ->
            if (calendarId >= 0) return@forEach
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                ),
                where,
                selectionArgs,
                "${CalendarContract.Calendars._ID} ASC",
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    calendarId = cursor.getLong(0)
                    calendarName = cursor.getString(1).orEmpty()
                }
            }
        }

        if (calendarId < 0) {
            return ToolOutcome.Failure("No writable calendar is available on this device")
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            notes?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }

        val eventUri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return ToolOutcome.Failure("The calendar provider rejected the event")
        val eventId = eventUri.lastPathSegment?.toLongOrNull()

        val summary = buildString {
            append("Created \"")
            append(title.take(80))
            append("\"")
            if (calendarName.isNotBlank()) {
                append(" on ")
                append(calendarName.take(60))
            }
        }.take(200)

        return ToolOutcome.Success(
            summary = summary,
            data = buildJsonObject {
                eventId?.let { put("eventId", it) }
                put("calendar", calendarName)
                put("title", title)
                put("start", formatDateTime(start))
                put("end", formatDateTime(end))
                put("allDay", false)
            },
        )
    }

    private fun parseDateTime(raw: String): Long? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        for (pattern in DATE_PATTERNS) {
            val format = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getDefault()
            }
            val position = ParsePosition(0)
            val parsed = format.parse(value, position)
            if (parsed != null && position.index == value.length) return parsed.time
        }
        return null
    }

    private companion object {
        val DATE_PATTERNS = arrayOf("yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm")
    }
}
