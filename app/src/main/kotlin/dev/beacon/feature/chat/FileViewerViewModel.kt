package dev.beacon.feature.chat

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.beacon.core.api.FilesApi
import javax.inject.Inject

/** Exposes the FilesApi to the file route via Hilt (composition can't inject directly). */
@HiltViewModel
class FileViewerViewModel @Inject constructor(
    val api: FilesApi,
) : ViewModel()