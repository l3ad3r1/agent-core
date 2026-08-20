package com.hermes.agent.data.plugin

import android.content.Context
import com.hermes.agent.domain.plugin.PluginInstallApproval
import com.hermes.agent.domain.plugin.PluginInstallApprovalRequest
import com.hermes.agent.domain.plugin.PluginPermission
import com.hermes.agent.domain.plugin.PermissionType
import com.hermes.agent.domain.plugin.PluginInstallAttempt
import com.hermes.agent.domain.plugin.PluginInstallStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AndroidPluginDecisionStoresTest {
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var trust: AndroidPluginPublisherTrustStore
    private lateinit var approvals: AndroidPluginInstallApprovalStore

    @Before fun setUp() {
        preferences = RuntimeEnvironment.getApplication().getSharedPreferences("plugin_decisions_v1", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val storage = PluginDecisionPreferences(preferences)
        trust = AndroidPluginPublisherTrustStore(storage)
        approvals = AndroidPluginInstallApprovalStore(storage)
    }

    @After fun tearDown() { preferences.edit().clear().commit() }

    @Test fun trustIsNormalizedAndRevocable() = runTest {
        val signer = "aa:" + "bb".repeat(31)
        assertTrue(trust.setTrusted("com.example.weather", signer, 10).isSuccess)
        assertTrue(trust.isTrusted("com.example.weather", signer.uppercase()).getOrThrow())
        assertFalse(trust.isTrusted("com.example.other", signer).getOrThrow())
        assertTrue(trust.revoke("com.example.weather", signer).isSuccess)
        assertFalse(trust.isTrusted("com.example.weather", signer).getOrThrow())
    }

    @Test fun approvalMatchesExactArtifactAndSurvivesNewStoreInstance() = runTest {
        val request = request("AA".repeat(32), publisherTrusted = true)
        val saved = PluginInstallApproval(request, 42)
        assertTrue(approvals.save(saved).isSuccess)
        val fresh = AndroidPluginInstallApprovalStore(PluginDecisionPreferences(preferences))
        assertEquals(saved, fresh.find(request).getOrThrow())
        assertNull(fresh.find(request.copy(apkSha256 = "BB".repeat(32))).getOrThrow())
        assertTrue(fresh.revoke(request).isSuccess)
        assertNull(fresh.find(request).getOrThrow())
    }

    @Test fun repeatedApprovalReplacesInsteadOfAppending() = runTest {
        val request = request("CC".repeat(32), publisherTrusted = true)
        approvals.save(PluginInstallApproval(request, 1)).getOrThrow()
        approvals.save(PluginInstallApproval(request, 2)).getOrThrow()
        assertEquals(2, approvals.find(request).getOrThrow()!!.approvedAtEpochSeconds)
        val encoded = preferences.getString("install_approvals", "")!!
        assertEquals(1, "pluginId".toRegex().findAll(encoded).count())
    }

    @Test fun installStateIsIdempotentAndSurvivesCompletion() = runTest {
        val state = AndroidPluginInstallStateStore(PluginDecisionPreferences(preferences))
        val attempt = PluginInstallAttempt("com.example.weather", "com.example.weather", 7, "EE".repeat(32), "FF".repeat(32), 30)

        state.recordHandoff(attempt).getOrThrow()
        state.recordHandoff(attempt.copy(versionCode = 8)).getOrThrow()
        assertEquals(8, state.pendingForPackage(attempt.packageName).getOrThrow()!!.attempt.versionCode)
        val completed = state.markInstalled(attempt.packageName, 31).getOrThrow()!!
        assertEquals(PluginInstallStatus.INSTALLED, completed.status)
        assertNull(state.pendingForPackage(attempt.packageName).getOrThrow())
        assertEquals(completed, state.latestForPlugin(attempt.pluginId).getOrThrow())
        assertNull(state.markInstalled(attempt.packageName, 32).getOrThrow())
    }

    private fun request(digest: String, publisherTrusted: Boolean) = PluginInstallApprovalRequest(
        pluginId = "com.example.weather", displayName = "Weather", versionCode = 7, versionName = "1.2.0",
        apkSha256 = digest, signerCertificateSha256 = "DD".repeat(32),
        permissions = listOf(PluginPermission(PermissionType.NETWORK, "Fetch forecast")), publisherTrusted = publisherTrusted,
    )
}
