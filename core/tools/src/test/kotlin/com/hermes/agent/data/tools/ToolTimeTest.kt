package com.hermes.agent.data.tools

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ToolTimeTest {

    private val ist = ZoneId.of("Asia/Kolkata")
    private val fourPm = LocalDateTime.of(2026, 10, 2, 16, 0).atZone(ist).toInstant().toEpochMilli()

    private fun ok(value: JsonPrimitive) = (ToolTime.parse(value, ist) as ToolTime.Parsed.Ok).epochMs

    @Test
    fun `accepts epoch millis as a number or a string`() {
        assertEquals(fourPm, ok(JsonPrimitive(fourPm)))
        assertEquals(fourPm, ok(JsonPrimitive(fourPm.toString())))
    }

    @Test
    fun `reads local date-times in the user's zone`() {
        assertEquals(fourPm, ok(JsonPrimitive("2026-10-02T16:00")))
        assertEquals(fourPm, ok(JsonPrimitive("2026-10-02 16:00:00")))
        assertEquals(
            LocalDateTime.of(2026, 10, 2, 0, 0).atZone(ist).toInstant().toEpochMilli(),
            ok(JsonPrimitive("2026-10-02")),
        )
    }

    @Test
    fun `honours an explicit offset`() {
        assertEquals(fourPm, ok(JsonPrimitive("2026-10-02T10:30:00Z")))
        assertEquals(fourPm, ok(JsonPrimitive("2026-10-02T16:00+05:30")))
    }

    @Test
    fun `absent is null and nonsense is invalid`() {
        assertNull(ToolTime.parse(null, ist))
        assertNull(ToolTime.parse(JsonPrimitive(""), ist))
        assertTrue(ToolTime.parse(JsonPrimitive("next friday"), ist) is ToolTime.Parsed.Invalid)
    }

    @Test
    fun `words name the day, date and time`() {
        assertEquals("Fri 2 Oct 2026, 16:00", ToolTime.words(fourPm, ist))
    }
}
