package com.hermes.agent.data.tools

import com.hermes.agent.data.security.OutputRedactor
import com.hermes.agent.domain.device.PrivilegedShellBackend
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import com.hermes.agent.domain.terminal.RemoteTerminalBackend
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShellToolTest {

    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val remoteBackend = mockk<RemoteTerminalBackend>(relaxed = true)
    private val privilegedBackend = mockk<PrivilegedShellBackend>(relaxed = true)
    private val outputRedactor = mockk<OutputRedactor>(relaxed = true)

    private lateinit var tool: ShellTool

    @Before
    fun setup() {
        tool = ShellTool(
            settingsRepository = settingsRepository,
            remoteBackend = remoteBackend,
            privilegedBackend = privilegedBackend,
            outputRedactor = outputRedactor,
        )
        coEvery { outputRedactor.redact(any()) } answers { firstArg() }
    }

    @Test
    fun `missing command returns error`() = runTest {
        val result = tool.execute(emptyMap())
        assertFalse(result.success)
        assertTrue(result.errorMessage?.contains("missing required parameter: command") == true)
    }

    @Test
    fun `privileged shell rejected when disabled in settings`() = runTest {
        coEvery { settingsRepository.current() } returns UserSettings(privilegedShellEnabled = false)

        val result = tool.execute(
            mapOf(
                "command" to JsonPrimitive("pm list packages"),
                "target" to JsonPrimitive("privileged"),
            ),
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage?.contains("disabled in Settings", ignoreCase = true) == true)
    }

    @Test
    fun `privileged shell returns error when Shizuku is not installed`() = runTest {
        coEvery { settingsRepository.current() } returns UserSettings(privilegedShellEnabled = true)
        coEvery { privilegedBackend.getStatus() } returns PrivilegedShellBackend.PrivilegedStatus(
            status = PrivilegedShellBackend.Status.NOT_INSTALLED,
        )

        val result = tool.execute(
            mapOf(
                "command" to JsonPrimitive("pm list packages"),
                "target" to JsonPrimitive("privileged"),
            ),
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage?.contains("not installed", ignoreCase = true) == true)
    }

    @Test
    fun `privileged shell returns adb command when Shizuku service is dead`() = runTest {
        coEvery { settingsRepository.current() } returns UserSettings(privilegedShellEnabled = true)
        coEvery { privilegedBackend.getStatus() } returns PrivilegedShellBackend.PrivilegedStatus(
            status = PrivilegedShellBackend.Status.DEAD,
        )

        val result = tool.execute(
            mapOf(
                "command" to JsonPrimitive("pm list packages"),
                "target" to JsonPrimitive("privileged"),
            ),
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage?.contains("start.sh") == true)
    }

    @Test
    fun `privileged shell returns error when permission required`() = runTest {
        coEvery { settingsRepository.current() } returns UserSettings(privilegedShellEnabled = true)
        coEvery { privilegedBackend.getStatus() } returns PrivilegedShellBackend.PrivilegedStatus(
            status = PrivilegedShellBackend.Status.PERMISSION_REQUIRED,
        )

        val result = tool.execute(
            mapOf(
                "command" to JsonPrimitive("pm list packages"),
                "target" to JsonPrimitive("privileged"),
            ),
        )

        assertFalse(result.success)
        assertTrue(result.errorMessage?.contains("permission has not been granted", ignoreCase = true) == true)
    }

    @Test
    fun `privileged shell executes successfully when ready`() = runTest {
        coEvery { settingsRepository.current() } returns UserSettings(privilegedShellEnabled = true)
        coEvery { privilegedBackend.getStatus() } returns PrivilegedShellBackend.PrivilegedStatus(
            status = PrivilegedShellBackend.Status.READY,
            uid = 2000,
            version = 13,
        )
        coEvery { privilegedBackend.execute("id", any()) } returns Result.success(
            PrivilegedShellBackend.ExecResult(exitCode = 0, output = "uid=2000(shell) gid=2000(shell)"),
        )

        val result = tool.execute(
            mapOf(
                "command" to JsonPrimitive("id"),
                "target" to JsonPrimitive("privileged"),
            ),
        )

        assertTrue(result.success)
        val output = result.output
        assertTrue(output.contains("exit_code=0 (privileged uid=2000)"))
        assertTrue(output.contains("uid=2000(shell)"))
    }
}
