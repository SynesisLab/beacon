package dev.beacon.core.speech

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow

/**
 * Voice input  — two engines:
 *
 * 1. On-device [SpeechRecognizer] (default) with **partial results** streaming
 *    into the composer; permission denial = graceful disabled state, never a
 *    crash loop (§8.5).
 * 2. Server mode: AudioRecord @16 kHz mono → WAV → POST /audio/transcriptions,
 *    capped at **20 MiB / 10 min** (verified server ceiling,).
 */
class VoiceInput(private val context: Context) {

    /** True when the RECORD_AUDIO permission is granted (mic button hides otherwise). */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private var recognizer: SpeechRecognizer? = null

    fun partials(): StateFlow<VoicePartial?> = partials_
    private val partials_ = kotlinx.coroutines.flow.MutableStateFlow<VoicePartial?>(null)

    fun stopOnDevice() {
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }

    /** On-device dictation: live partials surface into the composer draft. */
    fun startOnDevice(language: String? = null, onError: (String?) -> Unit = {}): Boolean {
        if (!hasPermission()) return false
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("No on-device speech service is available on this device.")
            return false
        }
        stopOnDevice()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(
                object : RecognitionListener {
                    override fun onPartialResults(partialResults: Bundle?) {
                        val text = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.lastOrNull()
                        if (!text.isNullOrEmpty()) {
                            partials_.value = VoicePartial.Partial(text)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        if (!text.isNullOrEmpty()) {
                            partials_.value = VoicePartial.Final(text)
                        }
                        stopOnDevice()
                    }

                    override fun onError(error: Int) {
                        onError(describeError(error))
                        stopOnDevice()
                    }

                    override fun onEndOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                },
            )
            startListening(intent)
        }
        return true
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun describeError(error: Int): String? = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> null
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nothing heard — try again."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed."
        else -> "Speech service error ($error)."
    }

    // ── server mode (AudioRecord → WAV → /api/v1/audio/transcriptions) ──────

    private var recorder: AudioRecord? = null
    private var recorderThread: Thread? = null
    private var wavBuilder: WavBuilder? = null

    fun startServerRecording(): String? {
        if (!hasPermission()) return "Microphone permission needed"
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, ENCODING)
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            ENCODING,
            maxOf(minBuffer, 8192),
        )
        recorder = audioRecord
        audioRecord.startRecording()
        wavBuilder = WavBuilder()
        recorderThread = Thread {
            val buffer = ShortArray(1024)
            while (recorder === audioRecord) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val wvb = wavBuilder ?: return@Thread
                    wvb.append(buffer, read)
                    if (wvb.pcmSize() >= MAX_WAV_BYTES) {
                        // hard-capped before the server rejects (§17)
                        return@Thread
                    }
                }
            }
        }.also { it.start() }
        return null
    }

    /** Ends the server-mode recording and returns the 16 kHz WAV bytes. */
    fun stopServerRecording(): ByteArray? {
        recordingStop()
        val builder = wavBuilder
        wavBuilder = null
        return builder?.toWavBytes()
    }

    private fun recordingStop() {
        val audioRec = recorder
        recorderThread?.join(500)
        audioRec?.stop()
        audioRec?.release()
        recorder = null
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        // 16 kHz × 2 bytes × 600 s = 19.2 MiB — under the server ceiling (§17).
        private const val MAX_WAV_BYTES = SAMPLE_RATE * 2 * 600
    }
}

/** The partial surface consumed by the composer. */
sealed interface VoicePartial {
    data class Partial(val text: String) : VoicePartial
    data class Final(val text: String) : VoicePartial
}

/** PCM accumulator + WAV header assembly (16-bit mono). */
private class WavBuilder {
    private val data = java.io.ByteArrayOutputStream()

    fun pcmSize(): Int = data.size()

    fun append(buffer: ShortArray, read: Int) {
        val bytes = ByteArray(read * 2)
        for (i in 0 until read) {
            bytes[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((buffer[i].toInt() shr 8) and 0xFF).toByte()
        }
        data.write(bytes)
    }

    fun toWavBytes(): ByteArray {
        val pcm = data.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        out.write("RIFF".encodeToByteArray())
        out.write(int32(36 + pcm.size))
        out.write("WAVE".encodeToByteArray())
        out.write("fmt ".encodeToByteArray())
        out.write(int32(16))
        out.write(int16(1)) // PCM
        out.write(int16(1)) // mono
        out.write(int32(SAMPLE_RATE))
        out.write(int32(SAMPLE_RATE * 2)) // byte rate
        out.write(int16(2)) // block align
        out.write(int16(16)) // bits
        out.write("data".encodeToByteArray())
        out.write(int32(pcm.size))
        out.write(pcm)
        return out.toByteArray()
    }

    private fun int32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    private fun int16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}