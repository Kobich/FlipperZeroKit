package dev.flipper.ui.internal.pages.files

import android.net.Uri
import dev.flipper.api.model.StorageEntry

internal data class SelectedUpload(val uri: Uri, val originalName: String, val sizeBytes: Long?)
internal data class TransferUi(val id: Long, val title: String, val bytes: Long = 0, val total: Long? = null,
    val active: Boolean = true, val message: String = "Подготовка…", val successful: Boolean = false)

internal data class FilesUiState(
    val rpcReady: Boolean = false,
    val path: String = "/ext",
    val isLoading: Boolean = false,
    val files: List<StorageEntry> = emptyList(),
    val error: String? = null,
    val cleanupStatus: String = "",
    val transfer: TransferUi? = null,
    val upload: SelectedUpload? = null,
    val deviceAddress: String? = null,
)
