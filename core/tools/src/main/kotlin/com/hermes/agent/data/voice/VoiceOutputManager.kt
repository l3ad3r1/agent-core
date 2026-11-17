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

    /**
     * Platform-engine speaking rate (1.0 = the engine's normal; lower is slower). Applied on every
     * [speak], so an app can set it after startup. Hermes keeps 1.0; Jeeves slows its fallback voice.
     */
    @Volatile
    var speechRate: Float = 1.0f

    /**
     * An app's own voice engine, used before the platform one (Jeeves: the Butler's on-device
     * voice). Null keeps the platform engine only, so Hermes is unchanged.
     */
    @Volatile
    var speaker: Speaker? = null

    /** A voice engine an app plugs in; see [speaker]. */
    interface Speaker {
        /** Speaks [text] to the end. False only when it could not speak at all (then the platform voice is used). */
        suspend fun speak(text: String): Boolean

        fun stop()
    }

    /** True if a voice is available: the app's [speaker], or a ready platform engine. */
    fun isAvailable(): Boolean = speaker != null || (tts != null && ready)

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
        val own = speaker
        if (own != null && text.isNotBlank()) {
            trySend(VoiceOutputEvent.Start)
            val spoken = runCatching { own.speak(text) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrDefault(false)
            if (spoken) {
                trySend(VoiceOutputEvent.Done)
                close()
                awaitClose { }
                return@callbackFlow
            }
            Timber.tag("VoiceOutput").w("App voice could not speak; using the platform voice")
        }
        val engine = tts
        // close() before awaitClose: without it the flow never completed, and a caller waiting
        // for the reply to finish (voice chat) waited forever when no engine was ready.
        if (engine == null || !ready) {
            trySend(VoiceOutputEvent.Error("TTS engine not ready"))
            close()
            awaitClose { }
            return@callbackFlow
        }
        if (text.isBlank()) {
            trySend(VoiceOutputEvent.Done)
            close()
            awaitClose { }
            return@callbackFlow
        }

        // Every time: the preference may have been set after the engine started, and setting the
        // language later resets the voice to that language's default.
        applyPreferredVoice()
        runCatching { engine.setSpeechRate(speechRate) }
        if (own == null) trySend(VoiceOutputEvent.Start)

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
        if (runCatching { engine.voice?.name }.getOrNull() == choice) return
        voices.firstOrNull { it.name == choice }?.let { voice ->
            runCatching { engine.voice = voice }
                .onSuccess { Timber.tag("VoiceOutput").i("Voice: %s", choice) }
                .onFailure { Timber.tag("VoiceOutput").w(it, "Could not set voice %s", choice) }
        }
    }

    /** The engine's English voice names, sorted; empty before [initialize] has finished. */
    fun englishVoiceNames(): List<String> =
        runCatching { tts?.voices?.map { it.name }.orEmpty() }.getOrDefault(emptyList())
            .filter { it.startsWith("en-", ignoreCase = true) }
            .sorted()

    /** Stop any in-progress speech. */
    fun stop() {
        speaker?.stop()
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
