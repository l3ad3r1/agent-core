package com.hermes.agent.domain.backup

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** One DataStore preference with its type, so it can be written back as the same kind of value. */
@Serializable
data class RawPref(val type: String, val value: JsonElement)

/**
 * A DataStore preferences file as plain data, whatever keys it happens to hold.
 *
 * A whole-app backup cannot depend on someone remembering to list every setting: a key added next
 * month would silently fall out of it. This reads the file's own key set instead, so anything
 * stored is carried.
 */
object RawPrefs {

    /** Every entry of [prefs]. [transformString] sees each string value, to open sealed secrets. */
    fun export(prefs: Preferences, transformString: (name: String, value: String) -> String = { _, v -> v }): Map<String, RawPref> =
        prefs.asMap().entries.mapNotNull { (key, value) ->
            val raw = when (value) {
                is Boolean -> RawPref("boolean", JsonPrimitive(value))
                is Int -> RawPref("int", JsonPrimitive(value))
                is Long -> RawPref("long", JsonPrimitive(value))
                is Float -> RawPref("float", JsonPrimitive(value))
                is Double -> RawPref("double", JsonPrimitive(value))
                is String -> RawPref("string", JsonPrimitive(transformString(key.name, value)))
                is Set<*> -> RawPref("stringSet", JsonArray(value.map { JsonPrimitive(it.toString()) }.sortedBy { it.content }))
                else -> null
            }
            raw?.let { key.name to it }
        }.toMap()

    /**
     * Replaces the whole of [target] with [entries]. [transformString] sees each string value on its
     * way in, to seal secrets. Returns the names of entries of a type this build does not know, which
     * are left out rather than guessed at.
     */
    fun replaceAll(
        target: MutablePreferences,
        entries: Map<String, RawPref>,
        transformString: (name: String, value: String) -> String = { _, v -> v },
    ): List<String> {
        target.clear()
        val skipped = mutableListOf<String>()
        for ((name, pref) in entries) {
            val v = pref.value
            when (pref.type) {
                "boolean" -> target[booleanPreferencesKey(name)] = v.jsonPrimitive.boolean
                "int" -> target[intPreferencesKey(name)] = v.jsonPrimitive.int
                "long" -> target[longPreferencesKey(name)] = v.jsonPrimitive.long
                "float" -> target[floatPreferencesKey(name)] = v.jsonPrimitive.float
                "double" -> target[doublePreferencesKey(name)] = v.jsonPrimitive.double
                "string" -> target[stringPreferencesKey(name)] = transformString(name, v.jsonPrimitive.contentOrNull.orEmpty())
                "stringSet" -> target[stringSetPreferencesKey(name)] = v.jsonArray.map { it.jsonPrimitive.content }.toSet()
                else -> skipped += name
            }
        }
        return skipped
    }
}
