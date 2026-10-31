package com.hermes.agent.data.llm

import com.hermes.agent.domain.llm.ToolCall
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ArgumentGroundingTest {

    private fun call(name: String, vararg args: Pair<String, JsonElement>) =
        ToolCall(id = "c", name = name, arguments = mapOf(*args))

    private fun alarm(hour: Int, minute: Int) =
        call("set_alarm", "hour" to JsonPrimitive(hour), "minute" to JsonPrimitive(minute), "label" to JsonPrimitive("Wake up"))

    private fun check(turn: String, c: ToolCall) = ungroundedTimeArgument(listOf(c), turn)

    @Test
    fun `an hour the user never said is refused`() {
        // The device run: set_alarm(hour=1, minute=30) scored 1.00 for this request.
        val reason = check("wake me up at 6:30 tomorrow", alarm(1, 30))
        assertNotNull(reason)
        assertEquals(true, "hour" in reason!! && "1" in reason)
    }

    @Test
    fun `the time the user said is accepted`() {
        assertNull(check("wake me up at 6:30 tomorrow", alarm(6, 30)))
    }

    @Test
    fun `a bare time is not silently moved to the afternoon`() {
        // Q8_0 answered 18:30 for "6:30"; only an afternoon cue licenses +12.
        assertNotNull(check("wake me up at 6:30", alarm(18, 30)))
        assertNull(check("wake me up at 6:30pm", alarm(18, 30)))
        assertNull(check("set an alarm for 6 this evening", alarm(18, 0)))
    }

    @Test
    fun `the minute may be left at zero`() {
        assertNull(check("alarm at 7", alarm(7, 0)))
        assertNotNull(check("alarm at 7", alarm(7, 45)))
    }

    @Test
    fun `number words and noon and midnight count`() {
        assertNull(check("wake me at seven thirty", alarm(7, 30)))
        assertNull(check("alarm at noon", alarm(12, 0)))
        assertNull(check("alarm at midnight", alarm(0, 0)))
        assertNull(check("half past six", alarm(6, 30)))
    }

    @Test
    fun `a duration in seconds follows from minutes or hours`() {
        fun timer(seconds: Int) = call("alarm", "action" to JsonPrimitive("set_timer"), "duration_seconds" to JsonPrimitive(seconds))
        assertNull(check("set a timer for ten minutes", timer(600)))
        assertNull(check("timer for two hours", timer(7200)))
        assertNotNull(check("set a timer for ten minutes", timer(300)))
    }

    @Test
    fun `numbers that are not times are left alone`() {
        val search = call("search_files", "limit" to JsonPrimitive(25), "query" to JsonPrimitive("budget"))
        assertNull(check("find the budget file", search))
    }

    @Test
    fun `numbers sent as strings are checked too`() {
        val c = call("set_alarm", "hour" to JsonPrimitive("1"), "minute" to JsonPrimitive("30"))
        assertNotNull(check("wake me up at 6:30", c))
    }
}
