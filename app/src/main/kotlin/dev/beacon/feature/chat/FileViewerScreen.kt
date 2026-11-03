package dev.beacon.feature.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.beacon.core.api.FilesApi
import dev.beacon.core.render.DisplayFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Native file viewers for display_file (agent.md §7.3). Download-once to cache;
 * images pinch-zoom; pdf pages render via PdfRenderer; text/code scrolls
 * monospace; everything else falls back to "Open with…" + download intents.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(
    filesApi: FilesApi,
    terminalsApi: dev.beacon.core.api.TerminalsApi? = null,
    fileId: String,
    name: String,
    mime: String,
    terminalServerId: String? = null,
    terminalPath: String? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewer = DisplayFile.viewerForMime(mime)
    var localFile by remember { mutableStateOf<File?>(null) }
    var terminalText by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(fileId, terminalServerId, terminalPath) {
        try {
            localFile = when {
                terminalServerId != null && terminalPath != null -> {
                    val terminals = terminalsApi
                        ?: error("Terminal support unavailable")
                    // Terminal files read through the OWUI proxy; text inline,
                    // images cached to disk for the image viewer.
                    if (viewer == dev.beacon.core.render.FileViewer.Image) {
                        val raw = terminals.read(terminalServerId, terminalPath)
                        val bytes = (raw as? dev.beacon.core.api.TsRead.Raw)?.bytes ?: ByteArray(0)
                        val file = File(File(context.cacheDir, "files"), "terminal-$terminalServerId")
                        file.writeBytes(bytes)
                        file
                    } else {
                        terminalText = (terminals.read(terminalServerId, terminalPath) as? dev.beacon.core.api.TsRead.Text)?.text
                        null
                    }
                }
                else -> filesApi.download(fileId, File(context.cacheDir, "files"))
            }
        } catch (e: Exception) {
            error = e.message ?: "Download failed"
        }
    }

    if (terminalServerId != null && localFile == null) {
        // Pure-text terminal view (file viewers below handle binary/image cases)
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(name.ifBlank { "Terminal file" }, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            val text = terminalText
            val errorText = error
            Box(Modifier.padding(padding).fillMaxSize()) {
                when {
                    errorText != null -> Text(
                        errorText,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                    text != null -> TextView(file = null, fallbackText = text)
                    else -> androidx.compose.material3.CircularProgressIndicator(
                        Modifier
                            .size(24.dp)
                            .align(Alignment.Center),
                    )
                }
            }
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                        Text(name.ifBlank { fileId }, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            localFile?.let { openExternally(context, it, mime) }
                        },
                        enabled = localFile != null,
                    ) { Text("Open with…") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val errorText = error
            when {
                errorText != null -> Text(
                    errorText,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
                localFile == null -> CircularProgressIndicator(Modifier.padding(16.dp))
                else -> when (viewer) {
                    dev.beacon.core.render.FileViewer.Image -> ImageView(localFile!!)
                    dev.beacon.core.render.FileViewer.Pdf -> PdfView(localFile!!, name)
                    dev.beacon.core.render.FileViewer.Text -> TextView(localFile!!)
                    dev.beacon.core.render.FileViewer.AudioVideo ->
                        dev.beacon.core.speech.Media3Card(localFile!!, mime)
                    dev.beacon.core.render.FileViewer.Download ->
                        OpenExternallyColumn(localFile!!, viewer, onOpen = { openExternally(context, localFile!!, mime) })
                }
            }
        }
    }
}

@Composable
private fun ImageView(file: File) {
    val bitmap = remember(file) {
        runCatching {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap == null) {
        Text(
            "This image couldn't be decoded locally.",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Image(
        bitmap = bitmap,
        contentDescription = file.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.5f, 6f)
                    offset = if (scale > 1f) offset + pan else androidx.compose.ui.geometry.Offset.Zero
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

@Composable
private fun PdfView(file: File, name: String) {
    var page by rememberSaveable { mutableStateOf(0) }
    var bitmap by remember(page) { mutableStateOf<ImageBitmap?>(null) }
    var pageCount by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(file, page) {
        withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        pageCount = renderer.pageCount
                        renderer.openPage(page.coerceIn(0, renderer.pageCount - 1)).use { pdfPage ->
                            val scale = 1440 / pdfPage.width.coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(
                                pdfPage.width * scale,
                                pdfPage.height * scale,
                                Bitmap.Config.ARGB_8888,
                            )
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            pdfPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmap = bmp.asImageBitmap()
                        }
                    }
                }
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        bitmap?.let {
            Image(bitmap = it, contentDescription = name, modifier = Modifier.fillMaxWidth())
        } ?: CircularProgressIndicator(Modifier.padding(16.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            OutlinedButton(onClick = { if (page > 0) page -= 1 }, enabled = page > 0) { Text("Prev") }
            Text("${page + 1} / $pageCount", modifier = Modifier.padding(vertical = 12.dp))
            Button(onClick = { if (page < pageCount - 1) page += 1 }, enabled = page < pageCount - 1) { Text("Next") }
        }
    }
}

@Composable
private fun TextView(file: File?, fallbackText: String? = null) {
    val content = fallbackText ?: remember(file) {
        runCatching {
            file?.readLines()?.take(4000)?.joinToString("\n")
        }.getOrDefault(null) ?: "(unreadable binary content)"
    }
    Text(
        text = content,
        style = MaterialTheme.typography.labelLarge,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    )
}

@Composable
private fun OpenExternallyColumn(
    file: File,
    viewer: dev.beacon.core.render.FileViewer,
    onOpen: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Text(file.name, style = MaterialTheme.typography.titleMedium)
        Text(
            if (viewer == dev.beacon.core.render.FileViewer.AudioVideo) {
                "Audio/video opens in your installed player (Media3 inline playback lands in M2-3)."
            } else {
                "This format has no native viewer yet — open with a compatible app or copy for sending."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onOpen) { Text("Open with…") }
    }
}

private fun openExternally(context: Context, file: File, mime: String) {
    try {
        val uri: Uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            file,
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        Toast.makeText(context, "No app can open this file type", Toast.LENGTH_SHORT).show()
    }
}