package com.omni.aiassistant.v2

import com.omni.aiassistant.core.agent.ToolArgs
import com.omni.aiassistant.core.agent.ToolCall
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSchema
import com.omni.aiassistant.core.agent.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun objectSchemaHasTheJsonSchemaShapeProvidersExpect() {
        val schema = ToolSchema.objectOf(
            properties = mapOf(
                "query" to ToolSchema.string(
                    description = "Search query",
                    choices = listOf("all", "missed"),
                    default = "all",
                ),
                "limit" to ToolSchema.integer(
                    description = "Maximum results",
                    minimum = 1,
                    maximum = 50,
                    default = 20,
                ),
                "enabled" to ToolSchema.boolean("Include disabled entries", default = true),
                "tags" to ToolSchema.stringArray("Optional tags"),
                "ratio" to ToolSchema.number("Ranking ratio"),
            ),
            required = listOf("query", "limit"),
        )

        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertFalse(schema["additionalProperties"]!!.jsonPrimitive.boolean)
        assertEquals(
            listOf("query", "limit"),
            schema["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )

        val properties = schema["properties"]!!.jsonObject
        assertEquals(
            setOf("query", "limit", "enabled", "tags", "ratio"),
            properties.keys,
        )

        val query = properties["query"]!!.jsonObject
        assertEquals("string", query["type"]!!.jsonPrimitive.content)
        assertEquals("Search query", query["description"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("all", "missed"),
            query["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals("all", query["default"]!!.jsonPrimitive.content)

        val limit = properties["limit"]!!.jsonObject
        assertEquals("integer", limit["type"]!!.jsonPrimitive.content)
        assertEquals(1, limit["minimum"]!!.jsonPrimitive.int)
        assertEquals(50, limit["maximum"]!!.jsonPrimitive.int)
        assertEquals(20, limit["default"]!!.jsonPrimitive.int)

        val enabled = properties["enabled"]!!.jsonObject
        assertEquals("boolean", enabled["type"]!!.jsonPrimitive.content)
        assertTrue(enabled["default"]!!.jsonPrimitive.boolean)

        val tags = properties["tags"]!!.jsonObject
        assertEquals("array", tags["type"]!!.jsonPrimitive.content)
        assertEquals("string", tags["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        assertEquals("number", properties["ratio"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun emptyObjectSchemaOmitsRequiredButKeepsAdditionalProperties() {
        val closed = ToolSchema.objectOf(emptyMap())
        assertEquals("object", closed["type"]!!.jsonPrimitive.content)
        assertEquals(0, closed["properties"]!!.jsonObject.size)
        assertFalse("required" in closed)
        assertFalse(closed["additionalProperties"]!!.jsonPrimitive.boolean)

        val open = ToolSchema.objectOf(emptyMap(), additionalProperties = true)
        assertTrue(open["additionalProperties"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun toolSpecConvertsToOpenAiFunctionWithoutInternalFields() {
        val parameters = ToolSchema.objectOf(
            properties = mapOf("body" to ToolSchema.string("Message body")),
            required = listOf("body"),
        )
        val spec = ToolSpec(
            name = "sms_send",
            description = "Send an SMS",
            category = ToolCategory.COMMUNICATION,
            risk = ToolRisk.HIGH,
            parameters = parameters,
        )

        val function = spec.toOpenAiFunction()
        assertEquals(setOf("name", "description", "parameters"), function.keys)
        assertEquals("sms_send", function["name"]!!.jsonPrimitive.content)
        assertEquals("Send an SMS", function["description"]!!.jsonPrimitive.content)
        assertEquals(parameters, function["parameters"]!!.jsonObject)
        assertFalse("category" in function)
        assertFalse("risk" in function)
    }

    @Test
    fun stringArgumentsRequireNonBlankJsonStrings() {
        val args = buildJsonObject {
            put("name", "Ali")
            put("persian", "سلام")
            put("blank", "   ")
            put("empty", "")
            put("number", 42)
            put("nothing", JsonNull)
        }

        with(ToolArgs) {
            assertEquals("Ali", args.string("name"))
            assertEquals("سلام", args.string("persian"))
            assertNull(args.string("blank"))
            assertNull(args.string("empty"))
            assertNull(args.string("number"))
            assertNull(args.string("nothing"))
            assertNull(args.string("missing"))

            // rawString is for callers that need the exact primitive text.
            assertEquals("   ", args.rawString("blank"))
            assertEquals("42", args.rawString("number"))
            assertNull(args.rawString("nothing"))
            assertNull(args.rawString("missing"))
        }
    }

    @Test
    fun integerArgumentsAcceptNumbersAndNumericStringsOnly() {
        val args = buildJsonObject {
            put("number", 7)
            put("numericText", "8")
            put("negativeText", "-3")
            put("decimal", 1.5)
            put("boolean", true)
            put("junk", "12px")
            put("overflow", "999999999999")
            put("nothing", JsonNull)
        }

        with(ToolArgs) {
            assertEquals(7, args.int("number") ?: -1)
            assertEquals(8, args.int("numericText") ?: -1)
            assertEquals(-3, args.int("negativeText") ?: -1)
            assertNull(args.int("decimal"))
            assertNull(args.int("boolean"))
            assertNull(args.int("junk"))
            assertNull(args.int("overflow"))
            assertNull(args.int("nothing"))
            assertNull(args.int("missing"))
        }
    }

    @Test
    fun booleanArgumentsAcceptBooleansAndStrictBooleanText() {
        val args = buildJsonObject {
            put("yes", true)
            put("no", false)
            put("textTrue", "true")
            put("textFalse", "false")
            put("textUpper", "TRUE")
            put("one", 1)
            put("nothing", JsonNull)
        }

        with(ToolArgs) {
            assertTrue(args.boolean("yes") == true)
            assertFalse(args.boolean("no") ?: true)
            assertEquals(true, args.boolean("textTrue"))
            assertEquals(false, args.boolean("textFalse"))
            // Booleans are case-insensitive: models capitalise them often.
            assertEquals(true, args.boolean("textUpper"))
            // Numbers are not booleans and are never coerced.
            assertNull(args.boolean("one"))
            assertNull(args.boolean("nothing"))
            assertNull(args.boolean("missing"))
        }
    }

    @Test
    fun stringListSkipsWrongTypesAndBlanks() {
        val args = buildJsonObject {
            put(
                "mixed",
                JsonArray(
                    listOf(
                        JsonPrimitive("a"),
                        JsonPrimitive(2),
                        JsonPrimitive(" "),
                        JsonPrimitive("b"),
                    ),
                ),
            )
            put("empty", JsonArray(emptyList()))
            put("blankOnly", JsonArray(listOf(JsonPrimitive(" "), JsonPrimitive(""))))
            put("scalar", "a")
        }

        with(ToolArgs) {
            assertEquals(listOf("a", "b"), args.stringList("mixed"))
            assertNull(args.stringList("empty"))
            assertNull(args.stringList("blankOnly"))
            assertNull(args.stringList("scalar"))
            assertNull(args.stringList("missing"))
        }
    }

    @Test
    fun requiredReadersThrowNamedIllegalArgumentErrors() {
        val args = buildJsonObject { put("blank", "   ") }

        val stringError = assertThrows(IllegalArgumentException::class.java) {
            with(ToolArgs) { args.requireString("name") }
        }
        assertEquals("Missing required argument 'name'", stringError.message)

        val intError = assertThrows(IllegalArgumentException::class.java) {
            with(ToolArgs) { args.requireInt("limit") }
        }
        assertEquals("Missing required integer 'limit'", intError.message)
    }

    @Test
    fun clampCapsUntrustedNumbersAndRejectsInvertedBounds() {
        assertEquals(0, ToolArgs.clamp(-5, 0, 100))
        assertEquals(100, ToolArgs.clamp(500, 0, 100))
        assertEquals(42, ToolArgs.clamp(42, 0, 100))

        assertThrows(IllegalArgumentException::class.java) {
            ToolArgs.clamp(1, 10, 5)
        }
    }

    @Test
    fun nullSafeReturnsJsonNullForMissingKeys() {
        val args = buildJsonObject {
            put("value", 3)
            put("explicit", JsonNull)
        }

        with(ToolArgs) {
            assertEquals(JsonPrimitive(3), args.nullSafe("value"))
            assertEquals(JsonNull, args.nullSafe("explicit"))
            assertEquals(JsonNull, args.nullSafe("missing"))
        }
    }

    @Test
    fun toolCallDefaultsToAnEmptyArgumentsObject() {
        val call = ToolCall(id = "call_1", name = "device_status")
        assertEquals(JsonObject(emptyMap()), call.arguments)
    }

    @Test
    fun outcomePayloadsUseTheModelFacingContract() {
        val data = buildJsonObject { put("count", 2) }
        val success = ToolOutcome.Success("Found 2 contacts", data).toModelPayload()
        assertEquals(true, success["ok"]!!.jsonPrimitive.boolean)
        assertEquals("Found 2 contacts", success["summary"]!!.jsonPrimitive.content)
        assertEquals(2, success["data"]!!.jsonObject["count"]!!.jsonPrimitive.int)
        assertFalse("error" in success)

        val bareSuccess = ToolOutcome.Success("ok").toModelPayload()
        assertFalse("data" in bareSuccess)

        val failure = ToolOutcome.Failure("No contacts permission").toModelPayload()
        assertFalse(failure["ok"]!!.jsonPrimitive.boolean)
        assertEquals("No contacts permission", failure["error"]!!.jsonPrimitive.content)
        assertFalse("denied" in failure)

        val denied = ToolOutcome.Denied("The user declined this action").toModelPayload()
        assertFalse(denied["ok"]!!.jsonPrimitive.boolean)
        assertTrue(denied["denied"]!!.jsonPrimitive.boolean)
        assertEquals("The user declined this action", denied["error"]!!.jsonPrimitive.content)

        val permission = ToolOutcome.NeedsPermission("CONTACTS", "Contacts").toModelPayload()
        assertFalse(permission["ok"]!!.jsonPrimitive.boolean)
        assertEquals("Permission required: Contacts", permission["error"]!!.jsonPrimitive.content)
        assertEquals("CONTACTS", permission["permission_required"]!!.jsonPrimitive.content)

        val disabled = ToolOutcome.Disabled("Accessibility is off").toModelPayload()
        assertFalse(disabled["ok"]!!.jsonPrimitive.boolean)
        assertTrue(disabled["disabled"]!!.jsonPrimitive.boolean)
        assertEquals("Accessibility is off", disabled["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun outcomePayloadEscapesTextAsAJsonString() {
        val text = "line 1\n\"quoted\" \\ path"
        val payload = ToolOutcome.Success(text).toModelPayload()

        val decoded = json.parseToJsonElement(payload.toString()).jsonObject
        assertEquals(text, decoded["summary"]!!.jsonPrimitive.content)
    }
}
