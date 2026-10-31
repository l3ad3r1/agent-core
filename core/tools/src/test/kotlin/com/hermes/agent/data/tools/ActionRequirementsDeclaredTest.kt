package com.hermes.agent.data.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hermes.agent.domain.tool.Tool
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `requiredByAction` is what lets the on-device tool caller refuse a call that is well-formed but
 * certain to fail. These tests keep the declarations honest: they must name real actions and real
 * parameters, and the tool itself must reject a call that leaves the declared argument out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActionRequirementsDeclaredTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val tools: List<Tool> = listOf(
        DeviceControlTool(context),
        DeviceSettingsTool(context),
        MediaControlTool(context),
        NavigationTool(context),
        AlarmTool(context),
        CommunicationTool(context),
        TtsTool(mockk(relaxed = true)),
        HomeAssistantTool(mockk(relaxed = true), okhttp3.OkHttpClient()),
    )

    @Test
    fun `every declared requirement names a real action and real parameters`() {
        for (tool in tools) {
            val d = tool.descriptor
            assertTrue("${d.name} should declare requirements", d.requiredByAction.isNotEmpty())
            val params = d.parameters.associateBy { it.name }
            val actions = params["action"]?.enumValues.orEmpty()
            for ((action, needs) in d.requiredByAction) {
                if (action != "*") assertTrue("${d.name}: '$action' is not one of $actions", action in actions)
                for (need in needs) for (alt in need.split('|')) {
                    assertTrue("${d.name}: '$alt' (needed by '$action') is not a declared parameter", alt in params)
                    // The schema must keep it optional: required=true on a per-action argument makes strict providers reject valid calls.
                    assertFalse("${d.name}.$alt must stay optional in the schema", params.getValue(alt).required)
                }
            }
        }
    }

    private fun args(vararg p: Pair<String, Any>): Map<String, JsonElement> =
        p.associate { (k, v) ->
            k to when (v) {
                is Boolean -> JsonPrimitive(v)
                is Int -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        }

    private suspend fun assertRejected(tool: Tool, label: String, arguments: Map<String, JsonElement>) {
        val result = tool.execute(arguments)
        assertFalse("$label should be rejected but succeeded: ${result.output}", result.success)
        assertTrue("$label: unexpected error '${result.errorMessage}'", result.errorMessage.orEmpty().contains("required", ignoreCase = true) || result.errorMessage.orEmpty().contains("missing", ignoreCase = true))
    }

    @Test
    fun `the tools themselves reject a call that omits the declared argument`() = runTest {
        assertRejected(DeviceControlTool(context), "device_control flashlight w/o enabled", args("action" to "flashlight"))
        assertRejected(DeviceControlTool(context), "device_control set_volume w/o level", args("action" to "set_volume"))
        assertRejected(DeviceControlTool(context), "device_control set_ringer_mode w/o mode", args("action" to "set_ringer_mode"))
        assertRejected(DeviceControlTool(context), "device_control set_dnd w/o enabled", args("action" to "set_dnd"))
        assertRejected(MediaControlTool(context), "media_control play_search w/o query", args("action" to "play_search"))
        assertRejected(NavigationTool(context), "navigation navigate w/o query", args("action" to "navigate"))
        assertRejected(AlarmTool(context), "alarm set_alarm w/o hour", args("action" to "set_alarm", "minute" to 30))
        assertRejected(AlarmTool(context), "alarm set_alarm w/o minute", args("action" to "set_alarm", "hour" to 6))
        assertRejected(AlarmTool(context), "alarm set_timer w/o duration", args("action" to "set_timer"))
        assertRejected(CommunicationTool(context), "communication dial w/o recipient", args("action" to "dial"))
        assertRejected(CommunicationTool(context), "communication compose_sms w/o recipient", args("action" to "compose_sms"))
        assertRejected(CommunicationTool(context), "communication compose_email w/o recipient", args("action" to "compose_email"))
        assertRejected(TtsTool(mockk(relaxed = true)), "speak w/o text", args("action" to "speak"))
    }

    @Test
    fun `device_settings set without a value is rejected by the tool`() = runTest {
        assertRejected(DeviceSettingsTool(context), "device_settings brightness set w/o value", args("setting" to "brightness", "action" to "set"))
        assertRejected(DeviceSettingsTool(context), "device_settings media_volume set w/o value", args("setting" to "media_volume", "action" to "set"))
    }

    @Test
    fun `brightness accepts auto instead of a level`() = runTest {
        val d = DeviceControlTool(context).descriptor
        assertEquals(listOf("level|auto"), d.requiredByAction["set_brightness"])
    }
}
