package com.hermes.agent.data.plugin

import android.content.Context

/** [ActivePluginStore] in a small prefs file, which full backups carry with the rest. */
class SharedPrefsActivePluginStore(context: Context) : ActivePluginStore {
    private val prefs = context.getSharedPreferences("plugin_state", Context.MODE_PRIVATE)

    override fun load(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()

    override fun save(ids: Set<String>) {
        prefs.edit().putStringSet(KEY, ids).apply()
    }

    private companion object {
        const val KEY = "active_ids"
    }
}
