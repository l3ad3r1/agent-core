package com.hermes.agent.data.tools

import com.hermes.agent.domain.settings.SettingsRepository
import com.hermes.agent.domain.settings.UserSettings
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Areas, floors and labels: list_places, place-filtered list_entities, place-targeted call_service. */
class HomeAssistantPlacesToolTest {

    private lateinit var server: MockWebServer
    private lateinit var tool: HomeAssistantTool
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()

    /** Shaped like the live index: "terrace" is both an area and a floor; a label reaches entities via devices. */
    private val placesJson = """
        {"areas": [
            {"id": "living_room", "name": "Living Room", "floor": "ground_floor", "entities": ["light.lamp"]},
            {"id": "guest_bedroom", "name": "Guest Bedroom", "floor": "ground_floor", "entities": []},
            {"id": "upstairs_guest_bedroom", "name": "Upstairs Guest Bedroom", "floor": "first_floor", "entities": ["light.upstairs"]},
            {"id": "terrace", "name": "Terrace", "floor": "terrace", "entities": ["light.terrace", "switch.pump"]},
            {"id": "outside", "name": "Outside", "floor": null, "entities": ["sensor.rain"]}
         ],
         "labels": [{"id": "hyderabad_flat", "name": "Hyderabad Flat", "entities": ["switch.geyser", "light.hall"]}],
         "floors": [
            {"id": "ground_floor", "name": "Ground Floor", "areas": ["living_room", "guest_bedroom"]},
            {"id": "first_floor", "name": "First Floor", "areas": ["upstairs_guest_bedroom"]},
            {"id": "terrace", "name": "Terrace", "areas": ["terrace"]}
         ]}
    """.trimIndent()

    private val statesJson = """
        [
          {"entity_id": "light.lamp", "state": "on", "attributes": {"friendly_name": "Lamp"}},
          {"entity_id": "light.upstairs", "state": "off", "attributes": {"friendly_name": "Upstairs"}},
          {"entity_id": "light.terrace", "state": "off", "attributes": {"friendly_name": "Terrace Light"}},
          {"entity_id": "switch.pump", "state": "on", "attributes": {"friendly_name": "Pump"}},
          {"entity_id": "switch.geyser", "state": "off", "attributes": {"friendly_name": "Geyser"}},
          {"entity_id": "light.hall", "state": "on", "attributes": {"friendly_name": "Hall"}}
        ]
    """.trimIndent()

    private var templateResponse: (String) -> MockResponse = { body ->
        if (body.contains("floors()")) MockResponse().setBody(placesJson) else MockResponse().setBody("3")
    }
    private var assistResponse = MockResponse().setBody("{}")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                requests += request.path.orEmpty() to body
                return when (request.path) {
                    "/api/template" -> templateResponse(body)
                    "/api/states" -> MockResponse().setBody(statesJson)
                    "/api/conversation/process" -> assistResponse
                    else -> if (request.path.orEmpty().startsWith("/api/services/")) {
                        MockResponse().setBody("[]")
                    } else {
                        MockResponse().setResponseCode(404)
                    }
                }
            }
        }
        server.start()
        val settings = mockk<SettingsRepository>()
        coEvery { settings.current() } returns UserSettings(
            homeAssistantUrl = server.url("/").toString(),
            homeAssistantToken = "t",
        )
        val client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build()
        tool = HomeAssistantTool(settings, client)
    }

    @After
    fun tearDown() = server.shutdown()

    private suspend fun run(vararg args: Pair<String, String>) =
        tool.execute(args.associate { (k, v) -> k to JsonPrimitive(v) })

    private fun serviceBody(): Map<String, String> {
        val (_, body) = requests.last { it.first.startsWith("/api/services/") }
        return Json.parseToJsonElement(body).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    @Test
    fun `list_places shows floors with their areas, floorless areas and labels`() = runTest {
        val result = run("action" to "list_places")

        assertTrue(result.output, result.success)
        assertTrue(result.output.contains("3 floors, 5 areas, 1 labels"))
        assertTrue(result.output.contains("Floors: Ground Floor, First Floor, Terrace"))
        assertTrue(result.output.contains("Floor Ground Floor [ground_floor]: 1 entities"))
        assertTrue(result.output.contains("  • Area Living Room [living_room]: 1 entities"))
        assertTrue(result.output.contains("Areas without a floor:"))
        assertTrue(result.output.contains("Area Outside [outside]"))
        assertTrue(result.output.contains("Label Hyderabad Flat [hyderabad_flat]: 2 entities"))
    }

    @Test
    fun `list_entities in a label finds entities reached through devices`() = runTest {
        val result = run("action" to "list_entities", "area" to "hyderabad flat")

        assertTrue(result.output, result.success)
        assertTrue(result.output.contains("in label 'Hyderabad Flat'"))
        assertTrue(result.output.contains("switch.geyser"))
        assertTrue(result.output.contains("light.hall"))
        assertFalse(result.output.contains("light.lamp"))
    }

    @Test
    fun `list_entities on a floor includes every area on it`() = runTest {
        val result = run("action" to "list_entities", "area" to "first floor", "domain" to "light")

        assertTrue(result.output, result.success)
        assertTrue(result.output.contains("light.upstairs"))
        assertFalse(result.output.contains("light.lamp"))
    }

    @Test
    fun `the place index is rendered once and reused`() = runTest {
        run("action" to "list_entities", "area" to "terrace")
        run("action" to "list_entities", "area" to "living room")

        assertEquals(1, requests.count { it.first == "/api/template" })
    }

    @Test
    fun `call_service on an area sends area_id and lets HA fan out`() = runTest {
        // "terrace" is both an area and a floor; the exact area wins.
        val result = run("action" to "call_service", "domain" to "light", "service" to "turn_off", "area" to "Terrace")

        assertTrue(result.output, result.success)
        assertTrue(result.output.contains("on area 'Terrace' [terrace]"))
        assertEquals(mapOf("area_id" to "terrace"), serviceBody())
        assertEquals("/api/services/light/turn_off", requests.last().first)
    }

    @Test
    fun `call_service targets floors and labels with their own keys`() = runTest {
        run("action" to "call_service", "domain" to "light", "service" to "turn_on", "area" to "ground floor")
        assertEquals(mapOf("floor_id" to "ground_floor"), serviceBody())

        run("action" to "call_service", "domain" to "switch", "service" to "turn_off", "area" to "hyderabad_flat")
        assertEquals(mapOf("label_id" to "hyderabad_flat"), serviceBody())
    }

    @Test
    fun `an ambiguous place is refused before anything is switched`() = runTest {
        val result = run("action" to "call_service", "domain" to "light", "service" to "turn_off", "area" to "guest")

        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("guest_bedroom"))
        assertTrue(result.errorMessage.orEmpty().contains("upstairs_guest_bedroom"))
        assertNull(requests.firstOrNull { it.first.startsWith("/api/services/") })
    }

    @Test
    fun `an unknown place lists the known ones`() = runTest {
        val result = run("action" to "list_entities", "area" to "garage")

        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("Known places: Living Room"))
    }

    @Test
    fun `render_template returns the rendered text`() = runTest {
        val result = run("action" to "render_template", "template" to "{{ 1 + 2 }}")

        assertTrue(result.output, result.success)
        assertEquals("3", result.output)
        assertEquals("{\"template\":\"{{ 1 + 2 }}\"}", requests.single().second)
    }

    @Test
    fun `render_template reports HA's template error`() = runTest {
        templateResponse = { MockResponse().setResponseCode(400).setBody("Error rendering template: TemplateSyntaxError") }

        val result = run("action" to "render_template", "template" to "{{ oops")

        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("TemplateSyntaxError"))
    }

    @Test
    fun `assist relays HA's spoken reply and what it acted on`() = runTest {
        assistResponse = MockResponse().setBody(
            """
            {"response": {"response_type": "action_done",
              "speech": {"plain": {"speech": "Turned off the lights"}},
              "data": {"success": [{"name": "Terrace Light", "id": "light.terrace"}], "failed": []}},
             "conversation_id": "x"}
            """.trimIndent(),
        )

        val result = run("action" to "assist", "text" to "turn off the terrace lights")

        assertTrue(result.output, result.success)
        assertTrue(result.output.contains("Assist (action_done): Turned off the lights"))
        assertTrue(result.output.contains("Acted on: Terrace Light"))
        assertEquals("{\"text\":\"turn off the terrace lights\"}", requests.single().second)
    }

    @Test
    fun `assist reports HA's error replies as failures`() = runTest {
        assistResponse = MockResponse().setBody(
            """{"response": {"response_type": "error", "speech": {"plain": {"speech": "Sorry, I couldn't understand that"}}}}""",
        )

        val result = run("action" to "assist", "text" to "blorp")

        assertFalse(result.success)
        assertTrue(result.errorMessage.orEmpty().contains("couldn't understand"))
    }

    @Test
    fun `parse rejects anything that is not the index object`() {
        assertNull(HomeAssistantPlaces.parse("[]"))
        assertNull(HomeAssistantPlaces.parse("not json"))
        assertEquals(1, HomeAssistantPlaces.parse("""{"areas":[{"id":"a"}]}""")!!.areas.size)
    }
}
