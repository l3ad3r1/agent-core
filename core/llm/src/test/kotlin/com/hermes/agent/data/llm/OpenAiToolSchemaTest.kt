package com.hermes.agent.data.llm

import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAiToolSchemaTest {

    @Test
    fun `array parameters carry an items schema of their element type`() {
        val tool = ToolDescriptor(
            name = "kanban",
            description = "Board.",
            parameters = listOf(
                ToolParameter("tags", ToolParameterType.ARRAY, "Tags."),
                ToolParameter("tickets", ToolParameterType.ARRAY, "Tickets.", itemType = ToolParameterType.OBJECT),
            ),
        )

        val properties = tool.toOpenAiJsonObject()["function"]!!.jsonObject["parameters"]!!
            .jsonObject["properties"]!!.jsonObject

        fun itemType(name: String) =
            properties[name]!!.jsonObject["items"]!!.jsonObject["type"]!!.jsonPrimitive.content
        assertEquals("string", itemType("tags"))
        assertEquals("object", itemType("tickets"))
    }
}
