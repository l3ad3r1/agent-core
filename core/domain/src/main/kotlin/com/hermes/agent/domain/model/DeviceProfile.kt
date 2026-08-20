package com.hermes.agent.domain.model

import kotlin.math.roundToInt

/** Structured snapshot of the device's hardware/OS capabilities. */
data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val soc: String,
    val abi: String,
    val cpuCores: Int,
    val totalRamGb: Double,
    val totalStorageGb: Double,
    val freeStorageGb: Double,
    val screen: String,
    val gpuRenderer: String,
    val gpuVendor: String,
    val glVersion: String,
    val batteryPct: Int,
    val sensors: List<String>,
) {
    /** Agent-readable summary committed to long-term memory. */
    fun toMemoryText(): String = buildString {
        append("[DEVICE PROFILE] The user's phone is a ")
        append("$manufacturer $model running Android $androidRelease (API $sdkInt). ")
        append("SoC: $soc; CPU: $cpuCores cores ($abi); RAM: ${fmt(totalRamGb)} GB; ")
        append("storage: ${fmt(freeStorageGb)} GB free of ${fmt(totalStorageGb)} GB. ")
        append("GPU: ${gpuRenderer.ifBlank { "unknown" }}")
        if (gpuVendor.isNotBlank()) append(" ($gpuVendor)")
        if (glVersion.isNotBlank()) append(", $glVersion")
        append(". Display: $screen. ")
        if (batteryPct in 0..100) append("Battery: $batteryPct%. ")
        if (sensors.isNotEmpty()) append("Sensors: ${sensors.joinToString(", ")}. ")
        append(
            "Use this to judge on-device capability — e.g. heavier local models need ample " +
                "RAM and a capable GPU; sensor-based features require the listed sensors.",
        )
    }

    private fun fmt(v: Double) = (v * 10).roundToInt() / 10.0
}
