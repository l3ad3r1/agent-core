package com.hermes.agent.data.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Home Assistant's areas, floors and labels, with the entities each one holds.
 *
 * Entity state attributes do not carry an area: an entity is in an area either directly
 * or through its device, and labels are usually put on devices rather than entities.
 * HA's own Android app reads the area/device/entity registries over the WebSocket API to
 * work this out; one template render through the REST API ([INDEX_TEMPLATE]) gives the
 * same answer without a socket, because area_entities() and device_entities() already
 * follow devices.
 */
internal data class HomeAssistantPlaces(
    val areas: List<Place>,
    val floors: List<Place>,
    val labels: List<Place>,
) {
    enum class Kind(val targetKey: String) { AREA("area_id"), FLOOR("floor_id"), LABEL("label_id") }

    data class Place(
        val kind: Kind,
        val id: String,
        val name: String,
        /** For an area, the floor it is on; for a floor, the areas on it. */
        val floorId: String? = null,
        val areaIds: List<String> = emptyList(),
        val entityIds: Set<String> = emptySet(),
    )

    /** Every entity in [place], counting a floor's areas. */
    fun entitiesOf(place: Place): Set<String> = when (place.kind) {
        Kind.FLOOR -> areas.filter { it.id in place.areaIds }.flatMap { it.entityIds }.toSet()
        else -> place.entityIds
    }

    /**
     * The place [query] names. An exact id or name wins over a partial one, and areas win
     * over floors and labels ("terrace" is both an area and a floor here). Null when nothing
     * matches, or when a partial match is ambiguous - the caller lists the candidates.
     */
    fun resolve(query: String): Resolution {
        val q = normalise(query)
        if (q.isEmpty()) return Resolution.None
        val all = areas + floors + labels
        all.firstOrNull { normalise(it.id) == q || normalise(it.name) == q }
            ?.let { return Resolution.Found(it) }
        val partial = all.filter { normalise(it.name).contains(q) || normalise(it.id).contains(q) }
        return when {
            partial.isEmpty() -> Resolution.None
            partial.size == 1 -> Resolution.Found(partial.single())
            else -> Resolution.Ambiguous(partial)
        }
    }

    sealed interface Resolution {
        data class Found(val place: Place) : Resolution
        data class Ambiguous(val candidates: List<Place>) : Resolution
        data object None : Resolution
    }

    companion object {
        /**
         * Renders the whole index as JSON. Labels expand through their devices, since
         * label_entities() only returns entities labelled directly.
         */
        val INDEX_TEMPLATE = """
            {%- set ns = namespace(a=[], l=[], f=[], e=[]) -%}
            {%- for a in areas() -%}{%- set ns.a = ns.a + [{'id': a, 'name': area_name(a), 'floor': floor_id(a), 'entities': area_entities(a)}] -%}{%- endfor -%}
            {%- for l in labels() -%}{%- set ns.e = label_entities(l) -%}{%- for d in label_devices(l) -%}{%- set ns.e = ns.e + device_entities(d) -%}{%- endfor -%}{%- set ns.l = ns.l + [{'id': l, 'name': label_name(l), 'entities': ns.e | unique | list}] -%}{%- endfor -%}
            {%- for f in floors() -%}{%- set ns.f = ns.f + [{'id': f, 'name': floor_name(f), 'areas': floor_areas(f)}] -%}{%- endfor -%}
            {{ {'areas': ns.a, 'labels': ns.l, 'floors': ns.f} | tojson }}
        """.trimIndent()

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(rendered: String): HomeAssistantPlaces? = runCatching {
            val root = json.parseToJsonElement(rendered.trim()) as JsonObject
            fun list(key: String) = (root[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
            fun JsonObject.strings(key: String) =
                (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            HomeAssistantPlaces(
                areas = list("areas").mapNotNull { o ->
                    val id = o.str("id") ?: return@mapNotNull null
                    Place(Kind.AREA, id, o.str("name") ?: id, floorId = o.str("floor"), entityIds = o.strings("entities").toSet())
                },
                floors = list("floors").mapNotNull { o ->
                    val id = o.str("id") ?: return@mapNotNull null
                    Place(Kind.FLOOR, id, o.str("name") ?: id, areaIds = o.strings("areas"))
                },
                labels = list("labels").mapNotNull { o ->
                    val id = o.str("id") ?: return@mapNotNull null
                    Place(Kind.LABEL, id, o.str("name") ?: id, entityIds = o.strings("entities").toSet())
                },
            )
        }.getOrNull()

        private fun normalise(s: String) = s.lowercase().replace('_', ' ').replace(Regex("\\s+"), " ").trim()
    }
}
