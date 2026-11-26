package com.hermes.agent.domain.tool

import com.hermes.agent.domain.llm.ToolCall
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Decides whether a Home Assistant control call must still ask with HA auto-approve on, using
 * what only Home Assistant knows - the target's friendly name. "Main 1 local APC UPS (Legion,
 * router hub)" is `switch.main_1_local_outlet_3`: its entity id says nothing about the router.
 *
 * Implementations fail closed: when the name cannot be read, they ask.
 */
interface HomeAssistantControlGuard {
    suspend fun mustAsk(call: ToolCall): Boolean

    companion object {
        /** Entity-id-only check, for setups without a Home Assistant connection. */
        val ID_ONLY = object : HomeAssistantControlGuard {
            override suspend fun mustAsk(call: ToolCall): Boolean {
                val target = HomeAssistantProtectedNames.guardedTarget(call) ?: return false
                return HomeAssistantProtectedNames.matches(target, friendlyName = null)
            }
        }
    }
}

/**
 * The Jeeves dashboard's "ask before switching off" rule (HaSafety in the app), applied to the
 * agent: switching off or toggling a switch, or pressing a button, whose entity id or friendly
 * name holds one of [PROTECTED_WORDS] as whole words - turning off the socket that powers Home
 * Assistant, the router or the tailnet box cuts off the very thing that would turn it back on.
 */
object HomeAssistantProtectedNames {
    /** Same list as the dashboard's HaSafety.PROTECTED_WORDS; keep the two in step. */
    val PROTECTED_WORDS: List<String> = listOf("home assistant", "brains", "router", "interloper", "pi", "ups")

    private val SWITCH_OFF_SERVICES = setOf("turn_off", "toggle")
    private val BUTTON_DOMAINS = setOf("button", "input_button")

    /**
     * The entity_id a call would cut or press in a guarded way, or null when the rule does not
     * apply (another service, another domain, or no single entity - place targets always ask).
     */
    fun guardedTarget(call: ToolCall): String? {
        if (!ToolConfirmationService.isHomeAssistantControl(call)) return null
        fun arg(key: String) = (call.arguments[key] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            ?.takeIf { it.isNotEmpty() }
        val entityId = arg("entity_id") ?: return null
        val service = arg("service") ?: return null
        val entityDomain = entityId.substringBefore('.', "")
        val callDomain = arg("domain") ?: entityDomain
        val guarded = when (entityDomain) {
            "switch" -> callDomain in setOf("switch", "homeassistant") && service in SWITCH_OFF_SERVICES
            in BUTTON_DOMAINS -> callDomain == entityDomain && service == "press"
            else -> false
        }
        return entityId.takeIf { guarded }
    }

    /** True when [entityId] or [friendlyName] holds a protected word sequence ("pi" is not "spider"). */
    fun matches(entityId: String, friendlyName: String?): Boolean {
        val tokens = tokens(friendlyName.orEmpty()) + "\u0000" + tokens(entityId.substringAfter('.'))
        return PROTECTED_WORDS.any { containsSequence(tokens, tokens(it)) }
    }

    private fun tokens(s: String): List<String> =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    private fun containsSequence(haystack: List<String>, needle: List<String>): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        for (i in 0..haystack.size - needle.size) {
            if (needle.indices.all { haystack[i + it] == needle[it] }) return true
        }
        return false
    }
}
