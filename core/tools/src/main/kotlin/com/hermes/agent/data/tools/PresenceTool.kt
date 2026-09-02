package com.hermes.agent.data.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import com.hermes.agent.data.local.dao.PresenceLogDao
import com.hermes.agent.domain.settings.SettingsRepository
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inspect on-device ambient presence context (power, motion, place label, and idle state).
 * Ported from OpenClaw presence specification (docs/nodes/presence.md).
 *
 * Enforces strict privacy invariant: returns NO raw geographic coordinates or precise timestamps.
 */
@Singleton
class PresenceTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val presenceLogDao: PresenceLogDao,
    private val settingsRepository: SettingsRepository,
) : Tool {

    private val json = Json { prettyPrint = true }

    override val descriptor = ToolDescriptor(
        name = "presence",
        description = "Get a compact snapshot of current on-device ambient presence (place label, motion, power/battery, and idle state). " +
            "Returns high-level context without raw coordinates or tracking history. " +
            "Use this to adapt replies to current user context (e.g., if user is moving, charging, or idle).",
        parameters = listOf(
            ToolParameter(
                name = "action",
                type = ToolParameterType.STRING,
                description = "The action to perform: 'get' (default) returns the current presence snapshot.",
                required = false,
                enumValues = listOf("get"),
            ),
        ),
        category = "device",
        capabilities = setOf("presence", "deferrable"),
        requiresConfirmation = false,
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()

        if (!settingsRepository.current().presenceEnabled) {
            return@withContext ToolResult.ok(
                "Presence tracking is turned off. The user can enable it in " +
                    "Settings > Assistant > Presence.",
                System.currentTimeMillis() - start,
            )
        }

        val latest = presenceLogDao.getLatest()

        val (batteryLevel, isCharging) = getBatteryStatus()
        val screenOn = isScreenOn()
        // A snapshot older than the beacon's own cadence tells us nothing about
        // where the user is now, so it degrades to "unknown" rather than lying.
        val fresh = latest?.takeIf { System.currentTimeMillis() - it.timestamp <= STALE_AFTER_MS }
        val place = fresh?.locationName?.takeIf { it.isNotBlank() } ?: "unknown"
        val motion = fresh?.activity?.takeIf { it.isNotBlank() } ?: "unknown"
        val powerStr = if (isCharging) "charging ($batteryLevel%)" else "$batteryLevel%"
        val idleMinutes = if (!screenOn && latest != null) {
            ((System.currentTimeMillis() - latest.timestamp) / (60 * 1000L)).toInt().coerceAtLeast(0)
        } else {
            0
        }

        val snapshot = PresenceOutput(
            place = place,
            motion = motion,
            power = powerStr,
            idle_minutes = idleMinutes,
        )

        val output = json.encodeToString(snapshot)
        ToolResult.ok(output, System.currentTimeMillis() - start)
    }

    private companion object {
        /** Beacon cadence is 15 min; allow one missed cycle before going stale. */
        const val STALE_AFTER_MS = 35L * 60 * 1000
    }

    @kotlinx.serialization.Serializable
    private data class PresenceOutput(
        val place: String,
        val motion: String,
        val power: String,
        val idle_minutes: Int,
    )

    private fun getBatteryStatus(): Pair<Int, Boolean> {
        return try {
            val batteryStatus: Intent? = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) ((level / scale.toFloat()) * 100).toInt() else 100

            val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            batteryPct to isCharging
        } catch (e: Exception) {
            100 to false
        }
    }

    private fun isScreenOn(): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isInteractive ?: false
        } catch (e: Exception) {
            false
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PresenceToolModule {
    @Binds
    @IntoSet
    abstract fun bindPresenceTool(tool: PresenceTool): Tool
}
