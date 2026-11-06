package dev.beacon.core.chat

/**
 * Transport classification  — driven by response Content-Type
 * (verified: middleware.py:6545-6549 — both text/event-stream AND
 * application/x-ndjson carry the same data:-frame protocol; task_id JSON → taskSocket;
 * plain JSON → jsonCompletion).
 */
sealed interface TransportKind {
    data object HttpStream : TransportKind
    data object TaskSocket : TransportKind
    data object JsonCompletion : TransportKind
}

fun classifyTransport(contentType: String?, bodyIsJsonObjectWithTaskId: Boolean? = null): TransportKind = when {
    contentType.orEmpty().contains("text/event-stream") -> TransportKind.HttpStream
    contentType.orEmpty().contains("application/x-ndjson") -> TransportKind.HttpStream
    bodyIsJsonObjectWithTaskId == true -> TransportKind.TaskSocket
    else -> TransportKind.JsonCompletion
}