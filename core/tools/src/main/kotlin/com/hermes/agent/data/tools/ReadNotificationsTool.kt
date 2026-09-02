package com.hermes.agent.data.tools

import android.content.Context
import com.hermes.agent.data.notifications.NotificationContentScreen
import com.hermes.agent.data.notifications.NotificationGateway
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * Read active system status-bar notifications with mandatory injection screening.
 * Ported from OpenClaw notification specification (docs/nodes/notifications.md).
 */
@Singleton
class ReadNotificationsTool @Inject constructor(
    @ApplicationContext private val context: Context,
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
        capabilities = setOf("notifications_read", "deferrable"),
        requiresConfirmation = false,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val packageName = arguments.string("package_name")
        val limit = (arguments.int("limit") ?: 10).coerceIn(1, 50)

        val rawNotifications = notificationGateway.getActiveNotifications(
            packageNameFilter = packageName,
            limit = 50, // Fetch broader window then screen
        )

        val screenedResult = NotificationContentScreen.screen(
            notifications = rawNotifications,
            ownPackageName = context.packageName,
            limit = limit,
        )

        if (screenedResult.notifications.isEmpty()) {
            val note = buildString {
                if (packageName != null) {
                    append("No active notifications found for package '$packageName'.")
                } else {
                    append("No active notifications found. (Note: Notification listener access may need to be enabled in Android Settings).")
                }
                if (screenedResult.droppedCount > 0) {
                    append("\n(${screenedResult.droppedCount} notification(s) hidden: content flagged as unsafe.)")
                }
            }
            return@withContext ToolResult.ok(note, System.currentTimeMillis() - start)
        }

        val formattedList = screenedResult.notifications.map { notif ->
            val timeStr = timeFormatter.format(Instant.ofEpochMilli(notif.postTime))
            mapOf(
                "id" to notif.id.toString(),
                "package" to notif.packageName,
                "title" to notif.title,
                "text" to notif.text,
                "time" to timeStr,
            )
        }

        val jsonOutput = json.encodeToString(formattedList)
        val finalOutput = if (screenedResult.droppedCount > 0) {
            "$jsonOutput\n(${screenedResult.droppedCount} notification(s) hidden: content flagged as unsafe.)"
        } else {
            jsonOutput
        }

        ToolResult.ok(finalOutput, System.currentTimeMillis() - start)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ReadNotificationsToolModule {
    @Binds
    @IntoSet
    abstract fun bindReadNotificationsTool(tool: ReadNotificationsTool): Tool
}
