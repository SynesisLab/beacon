package dev.beacon.core.network

import dev.beacon.core.api.OwuiApiException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** OkHttp 5 exposes this in okhttp-coroutines; a ~20-line local twin avoids the extra artifact. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        },
    )
    continuation.invokeOnCancellation { cancel() }
}

/**
 * Decodes a JSON body; failures type to [OwuiApiException] with a short body
 * excerpt so UI surfaces stay diagnosable .
 */
internal suspend inline fun <reified T> Response.decodeJson(json: Json): T = use {
    if (!isSuccessful) {
        val excerpt = body?.string()?.take(400)
        throw OwuiApiException(code, excerpt, "HTTP $code ${message.orEmpty()}".trim())
    }
    val text = body?.string() ?: throw OwuiApiException(code, null, "Empty body")
    try {
        json.decodeFromString<T>(text)
    } catch (e: SerializationException) {
        throw OwuiApiException(code, text.take(400), "Unexpected payload shape: ${e.message}")
    }
}

internal suspend fun <T> Response.decodeJson(serializer: KSerializer<T>, json: Json): T = use {
    if (!isSuccessful) {
        val excerpt = body?.string()?.take(400)
        throw OwuiApiException(code, excerpt, "HTTP $code ${message.orEmpty()}".trim())
    }
    val text = body?.string() ?: throw OwuiApiException(code, null, "Empty body")
    try {
        json.decodeFromString(serializer, text)
    } catch (e: SerializationException) {
        throw OwuiApiException(code, text.take(400), "Unexpected payload shape: ${e.message}")
    }
}