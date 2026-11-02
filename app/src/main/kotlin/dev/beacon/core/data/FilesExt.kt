package dev.beacon.core.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** Content-resolver helpers for attachment intake (M3-d). */
fun contentQueryName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) {
            return cursor.getString(index) ?: "file"
        }
    }
    return uri.lastPathSegment ?: "file"
}

fun contentQuerySize(context: Context, uri: Uri): Long {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
            return cursor.getLong(index)
        }
    }
    return 0L
}