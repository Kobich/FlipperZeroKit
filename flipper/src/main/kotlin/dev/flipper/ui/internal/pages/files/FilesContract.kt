package dev.flipper.ui.internal.pages.files

import android.net.Uri
import dev.flipper.api.model.StorageEntry

internal data class SelectedUpload(val uri: Uri, val originalName: String, val sizeBytes: Long?)
internal data class TransferUi(val id: Long, val title: String, val bytes: Long = 0, val total: Long? = null,
    val active: Boolean = true, val message: String = "Preparing…", val successful: Boolean = false,
    val isCleaningUp: Boolean = false, val outputUri: String? = null, val outputName: String? = null, val diagnostic: String? = null)

internal data class FilesUiState(
    val rpcReady: Boolean = false,
    val path: String = "/ext",
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val isPreparingUpload: Boolean = false,
    val isPreparingDownload: Boolean = false,
    val transferBusy: Boolean = false,
    val files: List<StorageEntry> = emptyList(),
    val error: String? = null,
    val cleanupStatus: String = "",
    val transfer: TransferUi? = null,
    val upload: SelectedUpload? = null,
    val showUploadDialog: Boolean = false,
    val deviceAddress: String? = null,
) {
    val isPreparingDocument: Boolean get() = isPreparingUpload || isPreparingDownload
}
