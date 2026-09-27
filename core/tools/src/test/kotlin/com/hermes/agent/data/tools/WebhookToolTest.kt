package com.hermes.agent.data.tools

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.hermes.agent.domain.product.ProductIdentity
import com.hermes.agent.domain.repository.ConnectorRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class WebhookToolTest {

    @Test
    fun `the notification reply reuses the open app instead of stacking a second copy`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val connectors = mockk<ConnectorRepository>(relaxed = true)
        coEvery { connectors.getEnabled() } returns emptyList()
        val tool = WebhookTool(OkHttpClient(), connectors, context, ProductIdentity("Hermes", "hermes_test"))

        val result = tool.execute(mapOf("message" to JsonPrimitive("Build finished")))
        assertTrue(result.errorMessage, result.success)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(nm).allNotifications.single()
        val reply = shadowOf(posted.actions.single().actionIntent)
        assertTrue("reply must open an activity", reply.isActivityIntent)
        val intent = reply.savedIntent
        assertEquals("com.hermes.agent.action.NOTIFICATION_REPLY", intent.action)
        val required = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals(required, intent.flags and required)
        assertEquals(reply.requestCode, intent.getIntExtra("EXTRA_NOTIFICATION_ID", 0))
    }
}
