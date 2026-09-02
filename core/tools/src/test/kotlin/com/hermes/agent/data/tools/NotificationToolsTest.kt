package com.hermes.agent.data.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hermes.agent.data.notifications.CapturedNotification
import com.hermes.agent.data.notifications.NotificationGateway
import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationToolsTest {

    private lateinit var context: Context
    private lateinit var gateway: NotificationGateway
    private lateinit var postTool: PostNotificationTool
    private lateinit var readTool: ReadNotificationsTool
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        gateway = NotificationGateway(context)
        settings = mockk(relaxed = true)
        coEvery { settings.current() } returns UserSettings(notificationsAgentReadEnabled = true)
        postTool = PostNotificationTool(gateway)
        readTool = ReadNotificationsTool(context, gateway, settings)
    }

    @Test
    fun readNotifications_returnsNothingWithoutTheSecondOptIn() = runTest {
        coEvery { settings.current() } returns UserSettings(notificationsAgentReadEnabled = false)
        NotificationGateway.updateActiveNotifications(
            listOf(
                CapturedNotification(
                    id = 1,
                    packageName = "com.whatsapp",
                    title = "WhatsApp",
                    text = "3 new messages",
                    postTime = System.currentTimeMillis(),
                    key = "k1",
                    isClearable = true,
                ),
            ),
        )
        val output = readTool.execute(emptyMap()).output
        assertTrue("must explain the switch is off", output.contains("turned off", ignoreCase = true))
        assertFalse("must not leak notification text", output.contains("3 new messages"))
    }

    @Test
    fun postNotificationDescriptor_hasCorrectMetadata() {
        assertEquals("post_notification", postTool.descriptor.name)
        assertEquals("system", postTool.descriptor.category)
        assertTrue(postTool.descriptor.capabilities.contains("notifications_post"))
        assertTrue(postTool.descriptor.requiresConfirmation)

        val titleParam = postTool.descriptor.parameters.firstOrNull { it.name == "title" }
        assertNotNull(titleParam)
        assertTrue(titleParam!!.required)

        val msgParam = postTool.descriptor.parameters.firstOrNull { it.name == "message" }
        assertNotNull(msgParam)
        assertTrue(msgParam!!.required)
    }

    @Test
    fun readNotificationsDescriptor_hasCorrectMetadata() {
        assertEquals("read_notifications", readTool.descriptor.name)
        assertEquals("system", readTool.descriptor.category)
        assertTrue(readTool.descriptor.capabilities.contains("notifications_read"))
        assertFalse(readTool.descriptor.requiresConfirmation)

        val pkgParam = readTool.descriptor.parameters.firstOrNull { it.name == "package_name" }
        assertNotNull(pkgParam)
        assertFalse(pkgParam!!.required)
    }

    @Test
    fun postNotification_executesAndReturnsSuccess() = runTest {
        val args = mapOf(
            "title" to JsonPrimitive("Important Alert"),
            "message" to JsonPrimitive("Your task has finished."),
            "priority" to JsonPrimitive("high"),
        )
        val result = postTool.execute(args)
        assertTrue(result.success)
        assertTrue(result.output.contains("Notification posted successfully"))
    }

    @Test
    fun postNotification_failsOnEmptyTitleOrMessage() = runTest {
        val args = mapOf("title" to JsonPrimitive(""))
        val result = postTool.execute(args)
        assertFalse(result.success)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun readNotifications_returnsCapturedNotifications() = runTest {
        NotificationGateway.updateActiveNotifications(emptyList())

        // Empty state
        val emptyResult = readTool.execute(emptyMap())
        assertTrue(emptyResult.success)
        assertTrue(emptyResult.output.contains("No active notifications found"))

        // Add dummy notification
        val dummy = CapturedNotification(
            id = 42,
            packageName = "com.whatsapp",
            title = "Alice",
            text = "Hello there!",
            postTime = System.currentTimeMillis(),
            key = "0|com.whatsapp|42|null|1000",
            isClearable = true,
        )
        NotificationGateway.onNotificationPosted(dummy)

        val result = readTool.execute(mapOf("package_name" to JsonPrimitive("com.whatsapp")))
        assertTrue(result.success)
        assertTrue(result.output.contains("Alice"))
        assertTrue(result.output.contains("Hello there!"))
    }
}
