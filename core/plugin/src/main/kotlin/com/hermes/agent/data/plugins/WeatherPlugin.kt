package com.hermes.agent.data.plugins

import com.hermes.agent.domain.plugin.Plugin
import com.hermes.agent.domain.plugin.PluginCapability
import com.hermes.agent.domain.plugin.PluginContext
import com.hermes.agent.domain.plugin.PluginLifecycleResult
import com.hermes.agent.domain.plugin.PluginManifest
import com.hermes.agent.domain.plugin.PluginPermission
import com.hermes.agent.domain.plugin.PermissionType
import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Weather plugin.
 *
 * Advertises a single `weather_get` tool that returns the current weather for a
 * city from Open-Meteo (free, no key): the city is geocoded, then the current
 * conditions are fetched for its coordinates. It used to return random data
 * seeded by the city name, which the model passed on as real weather.
 *
 * With no city it reports the weather where the device is, from [deviceWeather]
 * when the host app has one (Jeeves: Daybook's weather).
 *
 * Permissions: NETWORK.
 */
class WeatherPlugin internal constructor(
    /** GETs a URL and returns the body; throws on failure. */
    private val fetch: suspend (String) -> String,
    private val deviceWeather: DeviceWeather?,
) : Plugin {

    constructor(deviceWeather: DeviceWeather? = null) : this(::httpGet, deviceWeather)

    override val manifest = PluginManifest(
        id = "com.hermes.plugin.weather",
        displayName = "Weather",
        versionCode = 1,
        versionName = "1.0.0",
        author = "agent-core",
        signatureFingerprint = "(in-process, no signature required)",
        capabilities = listOf(
            PluginCapability(
                name = "weather_lookup",
                description = "Look up current weather conditions for a city.",
                toolDescriptors = listOf(
                    ToolDescriptor(
                        name = "weather_get",
                        description = "Get current temperature, conditions, and humidity for a city.",
                        parameters = listOf(
                            ToolParameter(
                                name = "city",
                                type = ToolParameterType.STRING,
                                description = "City name, e.g. 'Tokyo' or 'San Francisco'. " +
                                    "Omit for the weather where the device is.",
                                required = false,
                            ),
                            ToolParameter(
                                name = "units",
                                type = ToolParameterType.STRING,
                                description = "Temperature units: 'celsius' or 'fahrenheit'.",
                                required = false,
                                enumValues = listOf("celsius", "fahrenheit"),
                            ),
                        ),
                        category = "information",
                    ),
                ),
            ),
        ),
        permissions = listOf(
            PluginPermission(
                type = PermissionType.NETWORK,
                rationale = "Fetches live weather data from a public API.",
            ),
        ),
    )

    private var loaded = false

    override fun tools(): List<Tool> = listOf(weatherTool)

    override suspend fun onLoad(context: PluginContext): PluginLifecycleResult {
        context.log("Weather", com.hermes.agent.domain.plugin.LogLevel.INFO, "loading")
        loaded = true
        return PluginLifecycleResult.Success
    }

    override suspend fun onSuspend(): PluginLifecycleResult {
        loaded = false
        return PluginLifecycleResult.Success
    }

    override suspend fun onResume(): PluginLifecycleResult {
        loaded = true
        return PluginLifecycleResult.Success
    }

    override suspend fun onUnload(): PluginLifecycleResult {
        loaded = false
        return PluginLifecycleResult.Success
    }

    private val weatherTool = object : Tool {
        override val descriptor = manifest.capabilities.first().toolDescriptors.first()

        override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
            val fahrenheit = arguments["units"]?.extractString() == "fahrenheit"
            val city = arguments["city"]?.extractString()?.takeIf { it.isNotBlank() }
                ?: return here(fahrenheit)

            return runCatching {
                val place = json.parseToJsonElement(
                    fetch(
                        GEOCODE_URL.toHttpUrl().newBuilder()
                            .addQueryParameter("name", city)
                            .addQueryParameter("count", "1")
                            .build().toString(),
                    ),
                ).jsonObject["results"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: return ToolResult.error("No place called '$city' was found.")
                val name = listOfNotNull(place.text("name"), place.text("country")).joinToString(", ")

                val current = json.parseToJsonElement(
                    fetch(
                        FORECAST_URL.toHttpUrl().newBuilder()
                            .addQueryParameter("latitude", place.number("latitude").toString())
                            .addQueryParameter("longitude", place.number("longitude").toString())
                            .addQueryParameter(
                                "current",
                                "temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m",
                            )
                            .apply { if (fahrenheit) addQueryParameter("temperature_unit", "fahrenheit") }
                            .build().toString(),
                    ),
                ).jsonObject["current"]?.jsonObject
                    ?: return ToolResult.error("The weather service returned no current conditions.")

                val temp = Math.round(current.number("temperature_2m"))
                val humidity = Math.round(current.number("relative_humidity_2m"))
                val wind = Math.round(current.number("wind_speed_10m"))
                val conditions = describe(current["weather_code"]?.jsonPrimitive?.intOrNull)
                ToolResult.ok(
                    output = "Weather in $name: $temp${if (fahrenheit) "°F" else "°C"}, $conditions, " +
                        "humidity $humidity%, wind $wind km/h (Open-Meteo, as of ${current.text("time")}).",
                )
            }.getOrElse { ToolResult.error("Weather lookup failed: ${it.message}") }
        }

        private suspend fun here(fahrenheit: Boolean): ToolResult {
            val source = deviceWeather
                ?: return ToolResult.error("Name a city: this app has no weather for the device's location.")
            val reading = runCatching { source.current() }.getOrNull()
                ?: return ToolResult.error(
                    "Couldn't get the weather where the device is (location off or not permitted). Name a city instead.",
                )
            val temp = if (fahrenheit) Math.round(reading.tempC * 9 / 5.0 + 32) else reading.tempC.toLong()
            return ToolResult.ok(
                output = "Weather here: $temp${if (fahrenheit) "°F" else "°C"}, ${reading.conditions} " +
                    "(${reading.source}, updated ${LOCAL_TIME.format(java.time.Instant.ofEpochMilli(reading.fetchedAt))}).",
            )
        }

        private fun JsonElement.extractString(): String? =
            (this as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.number(key: String): Double =
            get(key)?.jsonPrimitive?.doubleOrNull ?: error("missing $key")

        private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
    }
}

/** Current weather where the device is, supplied by the host app. */
fun interface DeviceWeather {
    /** Null when there is no location or no network. */
    suspend fun current(): DeviceWeatherReading?
}

data class DeviceWeatherReading(
    val tempC: Int,
    val conditions: String,
    /** Where the reading came from, shown to the user, e.g. "Daybook, device location". */
    val source: String,
    val fetchedAt: Long,
)

private val LOCAL_TIME = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault())

private const val GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search"
private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"

private val json = Json { ignoreUnknownKeys = true }

private val httpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
    httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
        check(response.isSuccessful) { "HTTP ${response.code}" }
        response.body?.string().orEmpty()
    }
}

/** WMO weather code to words. */
private fun describe(code: Int?): String = when (code) {
    0 -> "clear"
    1, 2 -> "partly cloudy"
    3 -> "overcast"
    45, 48 -> "fog"
    in 51..57 -> "drizzle"
    in 61..67, in 80..82 -> "rain"
    in 71..77, 85, 86 -> "snow"
    in 95..99 -> "thunderstorm"
    else -> "conditions unknown"
}
