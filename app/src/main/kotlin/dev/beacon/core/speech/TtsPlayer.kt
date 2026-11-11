package dev.beacon.core.speech

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.beacon.core.api.AudioApi
import java.io.File

/**
 * Server TTS read-aloud : POST /api/v1/audio/speech →
 * the shared session player. Gated on the server's engines (/audio/models).
 */
class TtsPlayer(private val api: AudioApi) {
    private var player: ExoPlayer? = null

    fun attach(context: Context): ExoPlayer = player ?: ExoPlayer.Builder(context).build().also { player = it }

    suspend fun speak(context: Context, messageId: String, text: String, voice: String? = null): Boolean {
        val bytes = api.speech(text.take(4_000), voice) ?: return false
        val file = File(context.cacheDir, "tts/$messageId").apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
        player?.release()
        val fresh = attach(context)
        fresh.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
        fresh.prepare()
        fresh.play()
        return true
    }

    fun toggle(): Boolean = when (player?.isPlaying) {
        true -> {
            player?.pause(); false
        }
        false -> {
            player?.play(); true
        }
        else -> false
    }

    fun stop() {
        player?.stop()
    }

    fun release() {
        player?.release()
        player = null
    }
}

/** Biometric app-lock gate : resumes behind a credential check. */
class AppLock(
    private val activity: androidx.fragment.app.FragmentActivity,
    /** Compose bridge: the gate re-renders when authentication resolves. */
    private val onUnlocked: () -> Unit = {},
) {
    private val prompt = androidx.biometric.BiometricPrompt(
        activity,
        androidx.core.content.ContextCompat.getMainExecutor(activity),
        object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) {
                unlocked = true
                onUnlocked()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Keep the lock up; the user can retry (device credential path included).
                unlocked = false
            }
        },
    )

    var unlocked: Boolean = false
        private set

    fun requireUnlock() {
        if (unlocked) return
        prompt.authenticate(
            androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Beacon")
                // Modern flags: biometric OR device PIN/pattern/password
                // (setDeviceCredentialAllowed(true) is deprecated and throws
                // when combined with authenticator flags).
                .setAllowedAuthenticators(
                    androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build(),
        )
    }
}