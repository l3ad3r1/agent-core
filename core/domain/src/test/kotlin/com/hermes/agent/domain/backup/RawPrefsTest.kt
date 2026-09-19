package com.hermes.agent.domain.backup

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPrefsTest {

    private fun sample() = mutablePreferencesOf(
        booleanPreferencesKey("b") to true,
        intPreferencesKey("i") to 7,
        longPreferencesKey("l") to 9_000_000_000L,
        floatPreferencesKey("f") to 1.5f,
        doublePreferencesKey("d") to 2.25,
        stringPreferencesKey("s") to "text",
        stringSetPreferencesKey("set") to setOf("b", "a"),
    )

    @Test
    fun `every type comes back as the same kind of value`() {
        val exported = RawPrefs.export(sample())
        val target = mutablePreferencesOf(stringPreferencesKey("stale") to "left over")

        val skipped = RawPrefs.replaceAll(target, exported)

        assertTrue(skipped.isEmpty())
        assertEquals(sample().asMap(), target.asMap())
    }

    @Test
    fun `restoring replaces what was there rather than merging`() {
        val target = mutablePreferencesOf(stringPreferencesKey("stale") to "x", intPreferencesKey("i") to 1)
        RawPrefs.replaceAll(target, RawPrefs.export(sample()))
        assertEquals(null, target[stringPreferencesKey("stale")])
        assertEquals(7, target[intPreferencesKey("i")])
    }

    @Test
    fun `a key nobody listed is still carried`() {
        val prefs = mutablePreferencesOf(stringPreferencesKey("some_future_setting") to "v")
        val target = mutablePreferencesOf()
        RawPrefs.replaceAll(target, RawPrefs.export(prefs))
        assertEquals("v", target[stringPreferencesKey("some_future_setting")])
    }

    @Test
    fun `strings pass through the transforms in each direction`() {
        val prefs = mutablePreferencesOf(stringPreferencesKey("secret") to "enc:sealed", intPreferencesKey("n") to 1)
        val exported = RawPrefs.export(prefs) { _, v -> v.removePrefix("enc:") }
        assertEquals(JsonPrimitive("sealed"), exported.getValue("secret").value)

        val target = mutablePreferencesOf()
        RawPrefs.replaceAll(target, exported) { name, v -> if (name == "secret") "enc:$v" else v }
        assertEquals("enc:sealed", target[stringPreferencesKey("secret")])
    }

    @Test
    fun `an entry of an unknown type is reported and left out`() {
        val target = mutablePreferencesOf()
        val skipped = RawPrefs.replaceAll(
            target,
            mapOf("ok" to RawPref("int", JsonPrimitive(1)), "odd" to RawPref("bytes", JsonPrimitive("x"))),
        )
        assertEquals(listOf("odd"), skipped)
        assertEquals(1, target[intPreferencesKey("ok")])
    }

    @Test
    fun `it survives being written to JSON and read back`() {
        val serializer = MapSerializer(String.serializer(), RawPref.serializer())
        val text = Json.encodeToString(serializer, RawPrefs.export(sample()))
        val target = mutablePreferencesOf()
        RawPrefs.replaceAll(target, Json.decodeFromString(serializer, text))
        assertEquals(sample().asMap(), target.asMap())
    }
}
