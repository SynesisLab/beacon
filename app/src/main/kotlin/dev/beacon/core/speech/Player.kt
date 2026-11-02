package dev.beacon.core.speech

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Shared Media3 player instances (TTS read-aloud M3 + inline audio/video cards, §3/§7.3).
 */
object MediaPlayers {
    /** One session player for TTS (reused; state survives composition). */
    @Volatile
    private var ttsPlayer: ExoPlayer? = null

    fun tts(context: Context): ExoPlayer =
        ttsPlayer ?: synchronized(this) {
            ttsPlayer ?: ExoPlayer.Builder(context.applicationContext).build().also { ttsPlayer = it }
        }

    fun releaseTts() {
        ttsPlayer?.release()
        ttsPlayer = null
    }
}

/** Inline audio/video player card (the §7.3 table's Media3 branch). */
@Composable
fun Media3Card(
    localFile: java.io.File,
    mime: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(localFile) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(localFile)))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(localFile) {
        onDispose { player.release() }
    }

    Box(modifier.fillMaxWidth()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    setShowNextButton(false)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(if (mime.startsWith("audio/")) 80.dp else 240.dp),
        )
    }
}