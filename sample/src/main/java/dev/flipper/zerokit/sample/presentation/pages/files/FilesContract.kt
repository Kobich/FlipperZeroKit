package dev.flipper.zerokit.sample.presentation.pages.files

import android.net.Uri
import dev.flipper.api.model.StorageEntry

data class SelectedUpload(val uri: Uri, val originalName: String, val sizeBytes: Long?)
data class TransferUi(val title: String, val bytes: Long = 0, val total: Long? = null,
    val active: Boolean = true, val message: String = "Передача…")

data class FilesUiState(
    val rpcReady: Boolean = false,
    val path: String = "/ext",
    val isLoading: Boolean = false,
    val files: List<StorageEntry> = emptyList(),
    val error: String? = null,
    val transfer: TransferUi? = null,
    val upload: SelectedUpload? = null,
    val deviceAddress: String? = null,
)
