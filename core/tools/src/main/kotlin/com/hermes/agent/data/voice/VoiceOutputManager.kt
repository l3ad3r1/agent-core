package com.hermes.agent.data.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps Android's [TextToSpeech] engine for spoken agent replies.
 *
 * Per Section 7.3 of the plan: "a lightweight TTS engine for spoken
 * responses."
 *
 * Exposes a cold Flow per [speak] call that completes when the engine
 * finishes speaking (or errors out). The chat UI uses this to toggle
 * the speaker icon between "playing" and "idle" states.
 *
 * The engine is lazily initialized on the first [speak] call and held
 * for the lifetime of the singleton. [shutdown] releases native
 * resources — typically called from [com.hermes.agent.HermesApp.onTerminate].
 */
@Singleton
class VoiceOutputManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready: Boolean = false

    /**
     * Voice names to use, best first, matched as prefixes of the engine's voice names
     * (e.g. "en-gb-x-rjs"). Empty keeps the engine's default voice. Android reports no gender
     * for a voice, so an app that wants a particular kind of voice names them; see [chooseVoice].
     */
    @Volatile
    var preferredVoices: List<String> = emptyList()

    /** True if a TTS engine is available on this device. */
    fun isAvailable(): Boolean = tts != null && ready

    /**
     * Initialize the TTS engine. Safe to call multiple times.
     * Returns true once the engine reports [TextToSpeech.SUCCESS].
     */
    fun initialize(onReady: ((Boolean) -> Unit)? = null) {
        if (tts != null) {
            onReady?.invoke(ready)
            return
        }
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.US
                applyPreferredVoice()
                Timber.tag("VoiceOutput").i("TTS engine ready")
            } else {
                Timber.tag("VoiceOutput").w("TTS init failed: status=%d", status)
            }
            onReady?.invoke(ready)
        }
    }

    /**
     * Speak the given text. Returns a Flow that emits [VoiceOutputEvent.Start]
     * immediately, [VoiceOutputEvent.Done] when the engine finishes, or
     * [VoiceOutputEvent.Error] on failure.
     */
    fun speak(text: String, utteranceId: String = UUID.randomUUID().toString()): Flow<VoiceOutputEvent> = callbackFlow {
        val engine = tts
        if (engine == null || !ready) {
            trySend(VoiceOutputEvent.Error("TTS engine not ready"))
            awaitClose { }
            return@callbackFlow
        }
        if (text.isBlank()) {
            trySend(VoiceOutputEvent.Done)
            awaitClose { }
            return@callbackFlow
        }

        trySend(VoiceOutputEvent.Start)

        engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                trySend(VoiceOutputEvent.Done)
                channel.close()
            }
            override fun onError(id: String?) {
                trySend(VoiceOutputEvent.Error("TTS playback failed"))
                channel.close()
            }
        })

        engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)

        awaitClose {
            // Do not stop the engine entirely here or it kills the whole queue.
            // A dedicated stop() function handles interruption.
        }
    }

    /** Switches to the first installed voice in [preferredVoices]; the default stays otherwise. */
    private fun applyPreferredVoice() {
        val engine = tts ?: return
        if (preferredVoices.isEmpty()) return
        val voices = runCatching { engine.voices?.toList() }.getOrNull().orEmpty()
        val choice = chooseVoice(
            voices.map { VoiceOption(it.name, it.isNetworkConnectionRequired, it.features.orEmpty()) },
            preferredVoices,
        ) ?: return
        voices.firstOrNull { it.name == choice }?.let { voice ->
            runCatching { engine.voice = voice }
                .onSuccess { Timber.tag("VoiceOutput").i("Voice: %s", choice) }
                .onFailure { Timber.tag("VoiceOutput").w(it, "Could not set voice %s", choice) }
        }
    }

    /** Stop any in-progress speech. */
    fun stop() {
        tts?.stop()
    }

    /** Release native resources. */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}

sealed class VoiceOutputEvent {
    object Start : VoiceOutputEvent()
    object Done : VoiceOutputEvent()
    data class Error(val message: String) : VoiceOutputEvent()
}

/** What [chooseVoice] needs to know about an engine voice. */
data class VoiceOption(val name: String, val needsNetwork: Boolean, val features: Set<String> = emptySet())

/**
 * The voice to use: the first entry of [preferred] (a name prefix) that an available voice matches.
 * A voice the engine has installed beats one that needs the network, and one the engine reports
 * as not installed is never picked. Null when nothing matches, so the default stays.
 */
fun chooseVoice(available: List<VoiceOption>, preferred: List<String>): String? {
    val usable = available.filterNot { "notInstalled" in it.features }
    for (prefix in preferred) {
        val matches = usable.filter { it.name.startsWith(prefix, ignoreCase = true) }
        val pick = matches.firstOrNull { !it.needsNetwork } ?: matches.firstOrNull()
        if (pick != null) return pick.name
    }
    return null
}
