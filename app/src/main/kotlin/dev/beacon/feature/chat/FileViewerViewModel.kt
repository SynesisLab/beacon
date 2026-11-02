package dev.beacon.feature.chat

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.beacon.core.api.FilesApi
import javax.inject.Inject

/** Exposes the file/terminal APIs to the file route via Hilt. */
@HiltViewModel
class FileViewerViewModel @Inject constructor(
    val api: FilesApi,
    val terminals: dev.beacon.core.api.TerminalsApi,
) : ViewModel()