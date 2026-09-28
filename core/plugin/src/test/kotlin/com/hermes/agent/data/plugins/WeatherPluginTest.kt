package com.hermes.agent.data.plugins

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherPluginTest {

    private val plugin = WeatherPlugin()

    @Test
    fun `manifest declares weather_lookup capability with weather_get tool`() {
        val cap = plugin.manifest.capabilities.firstOrNull { it.name == "weather_lookup" }
        assertTrue("expected weather_lookup capability", cap != null)
        assertEquals("weather_get", cap!!.toolDescriptors.first().name)
    }

    @Test
    fun `manifest declares NETWORK permission`() {
        assertTrue(plugin.manifest.permissions.any { it.type == com.hermes.agent.domain.plugin.PermissionType.NETWORK })
    }

    @Test
    fun `onLoad returns Success`() = runTest {
        val result = plugin.onLoad(object : com.hermes.agent.domain.plugin.PluginContext {
            override fun log(tag: String, level: com.hermes.agent.domain.plugin.LogLevel, message: String, throwable: Throwable?) {}
            override suspend fun hostSetting(key: String): String? = null
            override fun hostAppVersion(): Int = 1
        })
        assertTrue(result is com.hermes.agent.domain.plugin.PluginLifecycleResult.Success)
    }

    @Test
    fun `tools returns the weather_get tool`() {
        val tools = plugin.tools()
        assertEquals(1, tools.size)
        assertEquals("weather_get", tools[0].descriptor.name)
    }

    private val geocode = """{"results":[{"name":"Panaji","country":"India","latitude":15.4909,"longitude":73.8278}]}"""
    private val forecast = """{"current":{"time":"2026-09-28T21:45","temperature_2m":26.4,"relative_humidity_2m":88,"weather_code":61,"wind_speed_10m":9.7}}"""

    private fun pluginAnswering(vararg bodies: String): Pair<WeatherPlugin, MutableList<String>> {
        val urls = mutableListOf<String>()
        val queue = ArrayDeque(bodies.toList())
        return WeatherPlugin({ url -> urls += url; queue.removeFirst() }, null) to urls
    }

    @Test
    fun `weather_get reports live Open-Meteo conditions for the geocoded city`() = runTest {
        // It used to return random data seeded by the city name; on the tablet the model
        // told the user Panaji was 20 C, windy and 41% humid.
        val (live, urls) = pluginAnswering(geocode, forecast)

        val r = live.tools().first().execute(mapOf("city" to JsonPrimitive("Panaji")))

        assertTrue(r.errorMessage, r.success)
        assertEquals(
            "Weather in Panaji, India: 26°C, rain, humidity 88%, wind 10 km/h (Open-Meteo, as of 2026-09-28T21:45).",
            r.output,
        )
        assertTrue(urls[0], urls[0].startsWith("https://geocoding-api.open-meteo.com/v1/search?name=Panaji"))
        assertTrue(urls[1], urls[1].contains("latitude=15.4909") && urls[1].contains("longitude=73.8278"))
    }

    @Test
    fun `an unknown place or a network failure is an error, never made-up weather`() = runTest {
        val (nowhere, _) = pluginAnswering("""{"generationtime_ms":0.3}""")
        val missing = nowhere.tools().first().execute(mapOf("city" to JsonPrimitive("Xyzzyville")))
        assertFalse(missing.success)
        assertTrue(missing.errorMessage!!.contains("Xyzzyville"))

        val offline = WeatherPlugin({ error("HTTP 503") }, null)
        val failed = offline.tools().first().execute(mapOf("city" to JsonPrimitive("Panaji")))
        assertFalse(failed.success)
        assertTrue(failed.errorMessage!!.contains("503"))
    }

    @Test
    fun `with no city it reports the host's device-location weather`() = runTest {
        var fetched = false
        val here = WeatherPlugin(
            { fetched = true; "" },
            DeviceWeather { DeviceWeatherReading(27, "partly cloudy", "Daybook, device location", 0L) },
        )

        val r = here.tools().first().execute(emptyMap())

        assertTrue(r.errorMessage, r.success)
        assertTrue(r.output, r.output.startsWith("Weather here: 27°C, partly cloudy (Daybook, device location"))
        assertFalse("no city means no geocoding", fetched)
    }

    @Test
    fun `weather_get tool errors on missing city`() = runTest {
        val tool = plugin.tools().first()
        val r = tool.execute(emptyMap())
        assertTrue(!r.success)
        assertTrue(r.errorMessage!!.contains("city"))
    }
}
