package com.hermes.agent.data.tools

import com.hermes.agent.data.notifications.NotificationGateway
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Post an Android status-bar notification.
 * Ported from OpenClaw notification specification (docs/nodes/notifications.md).
 */
@Singleton
class PostNotificationTool @Inject constructor(
    private val notificationGateway: NotificationGateway,
) : Tool {

    override val descriptor = ToolDescriptor(
        name = "post_notification",
        description = "Post a system notification to the device status bar. Use this when you want to alert or remind the user " +
            "with a visible status bar alert, or when background tasks/standing orders finish.",
        parameters = listOf(
            ToolParameter(
                name = "title",
                type = ToolParameterType.STRING,
                description = "Title of the notification.",
                required = true,
            ),
            ToolParameter(
                name = "message",
                type = ToolParameterType.STRING,
                description = "Body text / content of the notification.",
                required = true,
            ),
            ToolParameter(
                name = "priority",
                type = ToolParameterType.STRING,
                description = "Priority level: 'low', 'default', 'high', 'urgent' (default 'default').",
                required = false,
                enumValues = listOf("low", "default", "high", "urgent"),
            ),
        ),
        category = "system",
        capabilities = setOf("notification", "system", "automation"),
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val title = arguments.string("title")
        val message = arguments.string("message")
        val priority = arguments.string("priority") ?: "default"

        if (title.isNullOrBlank() || message.isNullOrBlank()) {
            return@withContext ToolResult.error("Both 'title' and 'message' parameters are required", System.currentTimeMillis() - start)
        }

        try {
            val notifId = notificationGateway.postNotification(
                title = title,
                message = message,
                priority = priority,
            )
            ToolResult.ok("Notification posted successfully (id=$notifId, priority=$priority).", System.currentTimeMillis() - start)
        } catch (e: Exception) {
            ToolResult.error("Failed to post notification: ${e.message}", System.currentTimeMillis() - start)
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PostNotificationToolModule {
    @Binds
    @IntoSet
    abstract fun bindPostNotificationTool(tool: PostNotificationTool): Tool
}
