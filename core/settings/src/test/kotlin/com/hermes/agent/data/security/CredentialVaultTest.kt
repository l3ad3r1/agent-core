package com.hermes.agent.data.security

import com.hermes.agent.domain.backup.CredentialsBackup
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CredentialVaultTest {

    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val vault = CredentialVault(settings)

    @Test
    fun `the gateway key is collected`() = runTest {
        coEvery { settings.current() } returns UserSettings(remoteGatewayApiKey = "gw-key")
        val collected = vault.collect()
        assertEquals("gw-key", collected.remoteGatewayApiKey)
        assertFalse("a gateway key alone still counts as something to back up", collected.isEmpty)
    }

    @Test
    fun `every key that was collected is put back`() = runTest {
        val restored = vault.apply(
            CredentialsBackup(
                cloudApiKey = "c", auxApiKey = "a", apiServerKey = "s", sshPassword = "p",
                homeAssistantToken = "h", remoteGatewayApiKey = "g",
            ),
        )
        assertEquals(6, restored)
        coVerify { settings.setApiServerKey("s") }
        coVerify { settings.setSshPassword("p") }
        coVerify { settings.setRemoteGatewayApiKey("g") }
    }

    @Test
    fun `a blank key never overwrites what is stored`() = runTest {
        assertEquals(0, vault.apply(CredentialsBackup()))
        coVerify(exactly = 0) { settings.setRemoteGatewayApiKey(any()) }
        coVerify(exactly = 0) { settings.setApiServerKey(any()) }
    }
}
