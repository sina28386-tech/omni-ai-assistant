package com.omni.aiassistant.v2

import com.omni.aiassistant.core.agent.Tool
import com.omni.aiassistant.core.agent.ToolCategory
import com.omni.aiassistant.core.agent.ToolOutcome
import com.omni.aiassistant.core.agent.ToolRisk
import com.omni.aiassistant.core.agent.ToolSchema
import com.omni.aiassistant.core.agent.ToolSpec
import com.omni.aiassistant.device.tools.ToolRegistry
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    @Test
    fun findsToolsByExactNameOnly() {
        val alpha = StubTool(spec("alpha", ToolCategory.APPS))
        val beta = StubTool(spec("beta", ToolCategory.SYSTEM))
        val registry = ToolRegistry(listOf(alpha, beta))

        assertSame(alpha, registry.tool("alpha"))
        assertSame(beta, registry.tool("beta"))
        assertNull(registry.tool("Alpha"))
        assertNull(registry.tool("missing"))
        assertNull(registry.tool(""))
        assertEquals(2, registry.size)
    }

    @Test
    fun exposesFullSpecsSortedByCategoryName() {
        val system = StubTool(spec("system_tool", ToolCategory.SYSTEM))
        val app = StubTool(spec("app_tool", ToolCategory.APPS))
        val communication = StubTool(spec("comm_tool", ToolCategory.COMMUNICATION))
        val registry = ToolRegistry(listOf(system, app, communication))

        assertEquals(
            listOf("app_tool", "comm_tool", "system_tool"),
            registry.specs().map { it.name },
        )
        assertEquals(
            setOf("system_tool", "app_tool", "comm_tool"),
            registry.all().map { it.spec.name }.toSet(),
        )
        assertEquals(3, registry.size)
    }

    @Test
    fun specsPreserveAllProviderFacingFields() {
        val parameters = ToolSchema.objectOf(
            properties = mapOf("path" to ToolSchema.string("File path")),
            required = listOf("path"),
        )
        val spec = ToolSpec(
            name = "file_info",
            description = "Return metadata for one file",
            category = ToolCategory.FILES,
            risk = ToolRisk.LOW,
            parameters = parameters,
        )
        val registry = ToolRegistry(listOf(StubTool(spec)))

        assertEquals(spec, registry.specs().single())
        assertEquals(parameters, registry.specs().single().parameters)
    }

    @Test
    fun emptyRegistryIsValid() {
        val registry = ToolRegistry(emptyList())

        assertEquals(0, registry.size)
        assertEquals(emptyList<ToolSpec>(), registry.specs())
        assertEquals(emptyList<Tool>(), registry.all())
        assertNull(registry.tool("anything"))
    }

    @Test
    fun duplicateNamesAreRejectedAtConstruction() {
        val first = StubTool(spec("duplicate", ToolCategory.APPS, description = "first"))
        val second = StubTool(spec("duplicate", ToolCategory.SYSTEM, description = "second"))

        val error = runCatching { ToolRegistry(listOf(first, second)) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("Duplicate tool name"))
    }

    @Test
    fun blankAndMalformedNamesAreRejected() {
        assertTrue(
            runCatching { ToolRegistry(listOf(StubTool(spec("", ToolCategory.APPS)))) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { ToolRegistry(listOf(StubTool(spec("Bad Name", ToolCategory.APPS)))) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun registryDoesNotObserveLaterMutationsOfTheInputList() {
        val first = StubTool(spec("first", ToolCategory.APPS))
        val second = StubTool(spec("second", ToolCategory.SYSTEM))
        val tools = mutableListOf<Tool>(first)

        val registry = ToolRegistry(tools)
        tools += second

        assertEquals(1, registry.size)
        assertNull(registry.tool("second"))
    }

    private fun spec(
        name: String,
        category: ToolCategory,
        description: String = "description for $name",
    ): ToolSpec = ToolSpec(
        name = name,
        description = description,
        category = category,
        risk = ToolRisk.LOW,
        parameters = ToolSchema.objectOf(emptyMap()),
    )

    private class StubTool(override val spec: ToolSpec) : Tool {
        override suspend fun execute(arguments: JsonObject): ToolOutcome =
            ToolOutcome.Success("ok")
    }
}
