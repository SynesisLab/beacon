package dev.beacon.core.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.beacon.core.api.FilesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface UploadGraph {
    fun filesApi(): FilesApi
}

/**
 * Upload queue (agent.md §10): WorkManager-backed, retry + exponential backoff,
 * network-constrained. The send flow awaits the upload ids before completion.
 */
@Singleton
class UploadQueue @Inject constructor(
    @ApplicationContext private val context: Context,
    private val filesApi: FilesApi,
) {
    private val workManager: WorkManager = WorkManager.getInstance(context)

    data class Entry(val name: String, val mime: String, val localPath: String)

    /** Enqueue + await → server file id (null when it never succeeds). */
    suspend fun upload(entry: Entry, bytes: ByteArray): String? {
        val temp = File(context.cacheDir, "upload-${UUID.randomUUID()}").apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    KEY_NAME to entry.name,
                    KEY_MIME to entry.mime,
                    KEY_PATH to temp.absolutePath,
                ),
            )
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork("upload-${UUID.randomUUID()}", ExistingWorkPolicy.KEEP, request)

        val info = runCatching {
            workManager.getWorkInfoByIdFlow(request.id)
                .filterNotNull()
                .first { it.state.isFinished }
        }.getOrNull()
        return info?.outputData?.getString(KEY_FILE_ID)
    }
}

internal class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val data = params.inputData

    override suspend fun doWork(): Result = try {
        val filesApi = dagger.hilt.android.EntryPointAccessors
            .fromApplication(applicationContext, UploadGraph::class.java).filesApi()
        val bytes = File(requireNotNull(data.getString(KEY_PATH))).readBytes()
        when (val id = filesApi.upload(
            name = requireNotNull(data.getString(KEY_NAME)),
            mime = data.getString(KEY_MIME) ?: "application/octet-stream",
            bytes = bytes,
        )) {
            null -> Result.retry()
            else -> Result.success(workDataOf(KEY_FILE_ID to id))
        }
    } catch (_: java.io.IOException) {
        Result.retry()
    }
}

private const val KEY_NAME = "name"
private const val KEY_MIME = "mime"
private const val KEY_PATH = "path"
const val KEY_FILE_ID = "file_id"
private const val TAG = "beacon-upload"