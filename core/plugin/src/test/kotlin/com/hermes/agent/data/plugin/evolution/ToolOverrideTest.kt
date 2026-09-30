package com.hermes.agent.data.plugin.evolution

import com.hermes.agent.data.plugin.ScriptPluginRepository
import com.hermes.agent.data.plugin.script.ScriptPluginEngine
import com.hermes.agent.data.plugin.script.ScriptToolParameter
import com.hermes.agent.domain.tool.ToolDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOverrideTest {

    // ── Policy ──────────────────────────────────────────────────────────────

    private fun d(name: String, category: String = "information", confirm: Boolean = false) =
        ToolDescriptor(name, "d", emptyList(), category, confirm)

    private fun allowed(name: String, descriptor: ToolDescriptor? = d(name)) =
        ToolOverridePolicy.evaluate(name, descriptor) == ToolOverridePolicy.Decision.Allowed

    @Test
    fun `read-mostly information tools may be overridden`() {
        assertTrue(allowed("web_search"))
        assertTrue(allowed("calculator"))
        assertTrue(allowed("get_current_datetime"))
    }

    @Test
    fun `safety-critical tools are never overridable`() {
        listOf("communication", "notes", "memory", "device_control", "shell", "skill_manager", "skills_hub",
            "contact_lookup", "app_tap", "desktop_bots", "write_file").forEach { assertFalse(it, allowed(it)) }
        assertFalse(allowed("fancy_tool", d("fancy_tool", category = "device")))
        assertFalse(allowed("fancy_tool", d("fancy_tool", category = "plugin")))
        assertFalse(allowed("fancy_tool", d("fancy_tool", confirm = true)))
        assertFalse(allowed("send_sms_later"))
        assertFalse(allowed("delete_everything"))
        assertFalse(allowed("module_installer"))
        assertFalse(allowed("missing", null))
        assertFalse(allowed("calculator", d("web_search")))
    }

    // ── Controller ──────────────────────────────────────────────────────────

    /** The controller reverts on its own scope; tests wait for that in real time. */
    private suspend fun eventually(condition: suspend () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(10_000) { while (!condition()) delay(10) }
    }

    private class Env {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dao = FakeScriptPluginDao()
        val registry = FakeToolRegistry()
        val repository = ScriptPluginRepository(dao, ScriptPluginEngine(), registry, NoHost())
        val trips = java.util.Collections.synchronizedList(mutableListOf<String>())
        val controller = ToolOverrideController(
            registry, repository, dao,
            listener = { moduleId, tool, _ -> trips += "$moduleId:$tool"; repository.setEnabled(moduleId, false) },
            scope = scope,
            maxConsecutiveFailures = 3,
        )
        val builtIn = FakeBuiltIn("calculator").also { registry.register(it) }
    }

    private val params = listOf(ScriptToolParameter("expression", required = true))

    private fun calculatorFix(body: String) = moduleJson(
        tool = "calculator", params = params, overrides = listOf("calculator"), body = body,
    )

    @Test
    fun `an approved evolution override shadows the built-in and keeps it underneath`() = runTest {
        val env = Env()
        env.repository.installLocal(calculatorFix("return 'module answer';"), emptySet()).getOrThrow()
        env.controller.sync()

        val live = env.registry.byName("calculator") as EvolutionOverrideTool
        assertSame(env.builtIn, live.builtIn)
        assertEquals(env.builtIn.descriptor, live.descriptor)
        assertEquals("module answer", live.execute(mapOf("expression" to JsonPrimitive("1"))).output)
        assertEquals(mapOf("calculator" to "evo-unit-converter"), env.controller.overrides.value)
        assertSame(env.builtIn.descriptor, env.registry.builtInDescriptor("calculator"))
    }

    @Test
    fun `a failing override falls back per call and auto-reverts after three failures in a row`() = runTest {
        val env = Env()
        env.repository.installLocal(calculatorFix("throw new Error('broken fix');"), emptySet()).getOrThrow()
        env.controller.sync()
        val live = env.registry.byName("calculator")!!

        repeat(2) { assertEquals("built-in answer", live.execute(emptyMap()).output) }
        assertTrue(env.trips.isEmpty())
        assertEquals("built-in answer", live.execute(emptyMap()).output)
        eventually { env.dao.items.getValue("evo-unit-converter").enabled.not() && env.trips.isNotEmpty() }

        assertEquals(listOf("evo-unit-converter:calculator"), env.trips)
        assertSame(env.builtIn, env.registry.byName("calculator"))
        assertFalse(env.dao.items.getValue("evo-unit-converter").enabled)
        assertTrue(env.controller.overrides.value.isEmpty())
        // Tripped: even the stale reference only reaches the built-in now.
        live.execute(emptyMap())
        assertEquals(4, env.builtIn.calls)
    }

    @Test
    fun `a success resets the failure streak`() = runTest {
        val env = Env()
        env.repository.installLocal(
            calculatorFix("if (args.expression === 'bad') throw new Error('x'); return 'ok';"), emptySet(),
        ).getOrThrow()
        env.controller.sync()
        val live = env.registry.byName("calculator")!!
        val bad = mapOf("expression" to JsonPrimitive("bad"))
        val good = mapOf("expression" to JsonPrimitive("good"))
        live.execute(bad); live.execute(bad); live.execute(good); live.execute(bad); live.execute(bad)
        assertTrue(env.trips.isEmpty())
        assertTrue(env.registry.byName("calculator") is EvolutionOverrideTool)
    }

    @Test
    fun `disabling or uninstalling the module restores the built-in`() = runTest {
        val env = Env()
        env.repository.installLocal(calculatorFix("return 'm';"), emptySet()).getOrThrow()
        env.controller.sync()
        env.repository.setEnabled("evo-unit-converter", false)
        env.controller.sync()
        assertSame(env.builtIn, env.registry.byName("calculator"))

        env.repository.setEnabled("evo-unit-converter", true)
        env.controller.sync()
        assertTrue(env.registry.byName("calculator") is EvolutionOverrideTool)
        env.repository.uninstall("evo-unit-converter")
        env.controller.sync()
        assertSame(env.builtIn, env.registry.byName("calculator"))
    }

    @Test
    fun `the controller re-checks policy and source at wiring time`() = runTest {
        val env = Env()
        val notes = FakeBuiltIn("notes", category = "productivity").also { env.registry.register(it) }
        // Straight through installLocal, bypassing the vetter: the controller still refuses.
        env.repository.installLocal(
            moduleJson(tool = "notes", params = params, overrides = listOf("notes"), body = "return 'x';"), emptySet(),
        ).getOrThrow()
        val refused = env.controller.sync()
        assertSame(notes, env.registry.byName("notes"))
        assertTrue(refused.single().contains("denylist"))

        // A local module from some other source is not an evolution module.
        env.repository.uninstall("evo-unit-converter")
        env.repository.installLocal(calculatorFix("return 'm';"), emptySet(), source = "sideload").getOrThrow()
        assertTrue(env.controller.sync().single().contains("only evolution modules"))
        assertSame(env.builtIn, env.registry.byName("calculator"))
    }

    @Test
    fun `start follows every module reload`() = runTest {
        val env = Env()
        env.controller.start()
        env.repository.installLocal(calculatorFix("return 'm';"), emptySet()).getOrThrow()
        eventually { env.registry.byName("calculator") is EvolutionOverrideTool }
        env.repository.setEnabled("evo-unit-converter", false)
        eventually { env.registry.byName("calculator") === env.builtIn }
        env.scope.cancel()
    }
}
