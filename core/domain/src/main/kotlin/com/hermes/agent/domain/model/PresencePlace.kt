package com.hermes.agent.domain.model

import kotlinx.serialization.Serializable

/**
 * A user-labelled place, used to turn a raw location fix into a coarse label.
 *
 * The centre coordinates live here so the app can resolve "am I at home?", but
 * they are stored through the encrypted settings path and **never** leave this
 * model: `presence_logs` keeps only the resolved [label], and the `presence`
 * tool returns only the label. This mirrors OpenClaw `docs/nodes/presence.md`,
 * which passes the agent a compact hint and no raw events.
 */
@Serializable
data class PresencePlace(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int = DEFAULT_RADIUS_METERS,
) {
    companion object {
        const val DEFAULT_RADIUS_METERS = 150
        const val MAX_PLACES = 16
        const val MAX_LABEL_LENGTH = 32

        /** Trim, cap the label, clamp the radius, drop blanks, cap the list. */
        fun normalize(places: List<PresencePlace>): List<PresencePlace> =
            places.asSequence()
                .map {
                    it.copy(
                        label = it.label.trim().take(MAX_LABEL_LENGTH),
                        radiusMeters = it.radiusMeters.coerceIn(25, 5_000),
                    )
                }
                .filter { it.label.isNotBlank() && it.latitude.isFinite() && it.longitude.isFinite() }
                .distinctBy { it.label.lowercase() }
                .take(MAX_PLACES)
                .toList()

        /**
         * Great-circle distance in metres between two fixes (haversine).
         * Good enough at geofence scale and avoids a Play Services dependency.
         */
        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val earthRadius = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
            return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        }

        /**
         * Resolve a fix to a place label, or null when it is outside every place.
         * The nearest matching place wins when radii overlap.
         */
        fun resolveLabel(places: List<PresencePlace>, latitude: Double, longitude: Double): String? =
            places
                .map { it to distanceMeters(it.latitude, it.longitude, latitude, longitude) }
                .filter { (place, distance) -> distance <= place.radiusMeters }
                .minByOrNull { (_, distance) -> distance }
                ?.first
                ?.label
    }
}
