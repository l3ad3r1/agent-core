package com.hermes.agent.data.plugin.evolution

import com.hermes.agent.domain.tool.ToolDescriptor

/**
 * Which existing tools an evolution module may shadow — fail closed.
 *
 * An override changes what an already-trusted tool does without the model or
 * the user seeing a new name, so it is only allowed for low-risk, read-mostly
 * tools. Everything that can send, spend, delete, drive the device, touch
 * credentials, or manage the agent's own modules and skills is off limits,
 * whatever the module or the reviewing bot says.
 */
object ToolOverridePolicy {

    sealed interface Decision {
        data object Allowed : Decision
        data class Denied(val reason: String) : Decision
    }

    /** Named tools that may never be overridden. Checked before any other rule. */
    val DENYLIST: Set<String> = setOf(
        // Sends messages, notifications or work off the device, or spends money.
        "communication", "notify", "post_notification", "speak", "desktop_bots", "manage_bots",
        "delegate", "home_assistant", "generate_image",
        // Deletes or rewrites the user's data, files or standing instructions.
        "memory", "notes", "todo", "bookmarks", "calendar", "kanban", "mood", "write_file",
        "patch", "file_checkpoint", "standing_orders", "scheduler",
        // Device control, accessibility, hardware, shells.
        "device_control", "device_settings", "app_launch", "app_tap", "app_type", "app_swipe",
        "app_analyze_screen", "alarm", "media_control", "navigation", "take_photo", "shell",
        "termux", "browser", "read_notifications", "presence",
        // People and credentials.
        "contact_lookup", "contacts_get", "contacts_search",
        // The agent's own machinery: skills, modules, tool results, clarifications.
        "skill_manager", "skills_hub", "read_tool_result", "clarify",
    )

    /** Whole categories that stay off limits, including tools added after this list. */
    val DENIED_CATEGORIES: Set<String> = setOf(
        "device", "communication", "system", "automation", "files", "bot_management", "devops",
        "mcp", "plugin", "security",
    )

    /** Names that suggest a sensitive action, for tools neither list knows about yet. */
    private val SENSITIVE_NAME = Regex(
        "(^|_)(send|sms|call|pay|payment|wallet|bank|transfer|delete|remove|wipe|erase|password|" +
            "credential|secret|token|key|auth|login|account|install|uninstall|module|plugin|skill|" +
            "permission|accessibility|shell|exec|root|admin|contact|message|email|mail)(_|$)",
    )

    /**
     * @param builtIn the descriptor of the tool currently registered under [name],
     *   or null when there is none (then there is nothing to override).
     */
    fun evaluate(name: String, builtIn: ToolDescriptor?): Decision {
        if (builtIn == null) return Decision.Denied("there is no existing tool named '$name' to override")
        if (name != builtIn.name) return Decision.Denied("override name does not match the tool's own name")
        if (name in DENYLIST) return Decision.Denied("'$name' is on the safety denylist")
        if (builtIn.category.lowercase() in DENIED_CATEGORIES) {
            return Decision.Denied("tools in the '${builtIn.category}' category may not be overridden")
        }
        if (builtIn.capabilities.any { it.lowercase() in DENIED_CATEGORIES }) {
            return Decision.Denied("'$name' has a capability that may not be overridden")
        }
        if (builtIn.requiresConfirmation) {
            return Decision.Denied("'$name' requires confirmation, so it has side effects and may not be overridden")
        }
        if (SENSITIVE_NAME.containsMatchIn(name)) {
            return Decision.Denied("'$name' looks like a sensitive action and may not be overridden")
        }
        return Decision.Allowed
    }
}
