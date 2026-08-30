package com.hermes.agent.data.terminal

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for how one Termux result is turned into agent-visible text.
 *
 * This is the logic behind K20. It has now been wrong in both directions:
 * `err != 0` reported every successful run as a plugin failure (because some
 * Termux builds return `err=-1` on success), and the `err > 0` that replaced it
 * would report a negative-coded genuine failure as success, handing the model an
 * empty result as though the command had worked. The rule under test is
 * sign-agnostic: only a non-blank `errmsg` means Termux failed to run anything.
 */
class TermuxResultFormatTest {

    private val runner = TermuxCommandRunner(
        context = mockk<Context>(relaxed = true),
        productIdentity = mockk(relaxed = true),
    )

    @Test
    fun `a clean run reports its exit code and stdout`() {
        val out = runner.format(stdout = "hello", stderr = "", exit = 0, err = 0, errmsg = "")
        assertEquals("exit_code=0\nhello", out)
    }

    @Test
    fun `err of minus one with no message is a success, not a plugin error`() {
        // The K20 case: Termux returned -1 on a run that actually worked, and
        // the old `err != 0` check swallowed the output behind an error string.
        val out = runner.format(stdout = "uid=2000(shell)", stderr = "", exit = 0, err = -1, errmsg = "")
        assertFalse("should not be reported as a plugin error", out.contains("plugin error"))
        assertTrue(out.contains("uid=2000(shell)"))
        assertTrue(out.startsWith("exit_code=0"))
    }

    @Test
    fun `a negative err WITH a message is still a plugin error`() {
        // The bug the `err > 0` fix would have introduced: a real failure
        // reported with a negative code read as success.
        val out = runner.format(
            stdout = "", stderr = "", exit = -1, err = -8,
            errmsg = "RUN_COMMAND permission denied",
        )
        assertTrue(out.contains("plugin error"))
        assertTrue(out.contains("RUN_COMMAND permission denied"))
        assertTrue(out.contains("allow-external-apps=true"))
    }

    @Test
    fun `a positive err with a message is a plugin error`() {
        val out = runner.format(stdout = "", stderr = "", exit = -1, err = 12, errmsg = "boom")
        assertTrue(out.contains("plugin error (err=12)"))
        assertTrue(out.contains("boom"))
    }

    @Test
    fun `a failing command is a result, not a plugin error`() {
        // A command that ran and exited non-zero is a normal outcome: the agent
        // needs the exit code and stderr, not an error about Termux setup.
        val out = runner.format(stdout = "", stderr = "no such file", exit = 127, err = 0, errmsg = "")
        assertFalse(out.contains("plugin error"))
        assertEquals("exit_code=127\n[stderr]\nno such file", out)
    }

    @Test
    fun `stdout and stderr are both surfaced`() {
        val out = runner.format(stdout = "out", stderr = "warn", exit = 0, err = 0, errmsg = "")
        assertTrue(out.contains("out"))
        assertTrue(out.contains("[stderr]\nwarn"))
    }

    @Test
    fun `the CLI probe checks PATH and both standard install locations`() {
        // The other half of K20: a non-interactive `bash -c` never sources the
        // login profile, so PATH alone missed a real install.
        val probe = HermesCliProbe.COMMAND
        assertTrue("must still try PATH", probe.contains("command -v hermes"))
        assertTrue("must check \$PREFIX/bin", probe.contains("/data/data/com.termux/files/usr/bin/hermes"))
        assertTrue("must check ~/.local/bin", probe.contains("\$HOME/.local/bin/hermes"))
        assertTrue(probe.contains(HermesCliProbe.PRESENT))
        assertTrue(probe.contains(HermesCliProbe.ABSENT))
    }

    @Test
    fun `the probe markers cannot be confused with each other`() {
        // `result.contains(PRESENT)` must not also match the absent marker.
        assertFalse(HermesCliProbe.ABSENT.contains(HermesCliProbe.PRESENT))
        assertFalse(HermesCliProbe.PRESENT.contains(HermesCliProbe.ABSENT))
    }

    @Test
    fun `an errmsg with err zero is still a plugin error`() {
        // The gap a reviewer caught: the check had been written as
        // `err != 0 && errmsg.isNotBlank()`, so a genuine message arriving with
        // err=0 fell through and was rendered as a bare successful result --
        // exactly the silent-success failure this whole check exists to stop.
        val out = runner.format(
            stdout = "", stderr = "", exit = -1, err = 0,
            errmsg = "RUN_COMMAND permission denied",
        )
        assertTrue("an errmsg must never be swallowed", out.contains("plugin error"))
        assertTrue(out.contains("RUN_COMMAND permission denied"))
    }

    @Test
    fun `every run gets a fresh result action`() {
        // The exported receiver's whole safety argument is that its action
        // carries an unguessable per-call UUID. If that ever became a fixed or
        // predictable string, any app on the device could forge a result.
        val actions = (1..200).map { TermuxCommandRunner.newResultAction() }
        assertEquals("actions must never repeat", actions.size, actions.toSet().size)
        assertTrue(actions.all { it.startsWith(TermuxCommandRunner.RESULT_ACTION_PREFIX) })
        val suffixes = actions.map { it.removePrefix(TermuxCommandRunner.RESULT_ACTION_PREFIX) }
        assertTrue("suffix must be a UUID, not a counter", suffixes.all { it.length == 36 && it.count { c -> c == '-' } == 4 })
    }
}
