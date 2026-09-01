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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read active system status-bar notifications.
 * Ported from OpenClaw notification specification (docs/nodes/notifications.md).
 */
@Singleton
class ReadNotificationsTool @Inject constructor(
    private val notificationGateway: NotificationGateway,
) : Tool {

    private val json = Json { prettyPrint = true }
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    override val descriptor = ToolDescriptor(
        name = "read_notifications",
        description = "Read active and recent status-bar notifications received on this device. " +
            "Returns a list of notifications including app package name, title, text, and timestamp. " +
            "Use this when the user asks what notifications they have received, to check for incoming messages or alerts.",
        parameters = listOf(
            ToolParameter(
                name = "package_name",
                type = ToolParameterType.STRING,
                description = "Optional filter by application package name (e.g. 'com.whatsapp', 'com.google.android.talk').",
                required = false,
            ),
            ToolParameter(
                name = "limit",
                type = ToolParameterType.INTEGER,
                description = "Maximum number of notifications to return (default 10, max 50).",
                required = false,
            ),
        ),
        category = "system",
        capabilities = setOf("notification", "system", "information"),
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val packageName = arguments.string("package_name")
        val limit = (arguments.int("limit") ?: 10).coerceIn(1, 50)

        val notifications = notificationGateway.getActiveNotifications(
            packageNameFilter = packageName,
            limit = limit,
        )

        if (notifications.isEmpty()) {
            val note = if (packageName != null) {
                "No active notifications found for package '$packageName'."
            } else {
                "No active notifications found. (Note: Notification listener access may need to be enabled in Android Settings)."
            }
            return@withContext ToolResult.ok(note, System.currentTimeMillis() - start)
        }

        val formattedList = notifications.map { notif ->
            val timeStr = timeFormatter.format(Instant.ofEpochMilli(notif.postTime))
            mapOf(
                "id" to notif.id.toString(),
                "package" to notif.packageName,
                "title" to notif.title,
                "text" to notif.text,
                "time" to timeStr,
            )
        }

        val output = json.encodeToString(formattedList)
        ToolResult.ok(output, System.currentTimeMillis() - start)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ReadNotificationsToolModule {
    @Binds
    @IntoSet
    abstract fun bindReadNotificationsTool(tool: ReadNotificationsTool): Tool
}
