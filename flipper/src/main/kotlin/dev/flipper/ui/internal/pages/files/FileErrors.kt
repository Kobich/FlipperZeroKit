package dev.flipper.ui.internal.pages.files

import com.flipperdevices.protobuf.FlipperProto.CommandStatus
import dev.flipper.api.model.FlipperRpcException
import dev.flipper.internal.io.ProviderBusyException
import dev.flipper.internal.io.ProviderTimeoutException
import java.io.FileNotFoundException

internal fun fileErrorMessage(error: Throwable): String {
    // A finalization failure deliberately carries the path that must be inspected, not auto-deleted.
    if (error.message?.startsWith("Finalization failed.") == true) return error.message.orEmpty()
    return when (error) {
        is ProviderTimeoutException -> "The document provider stopped responding. Open the Files app or reconnect its storage, then retry. Partial cleanup may be deferred."
        is ProviderBusyException -> "A document provider is still busy. Wait, or restart the provider/app before retrying."
        is SecurityException -> "Access to the document was denied. Choose the file or destination again and grant access."
        is FileNotFoundException -> "The file or destination is no longer available. Choose it again."
        is FlipperRpcException -> when (error.reason) {
            FlipperRpcException.Reason.NOT_READY, FlipperRpcException.Reason.SESSION_CLOSED,
            FlipperRpcException.Reason.SEND_FAILED -> "Connection to Flipper was lost or is not ready. Reconnect, then retry the whole file."
            FlipperRpcException.Reason.TIMEOUT -> "The operation stopped responding. Check Flipper and its SD card, then retry."
            FlipperRpcException.Reason.REMOTE_ERROR -> when (error.statusCode) {
                CommandStatus.ERROR_STORAGE_NOT_READY_VALUE -> "Flipper storage is not ready. Check the SD card, then refresh."
                CommandStatus.ERROR_STORAGE_NOT_EXIST_VALUE -> "The file or folder no longer exists. Refresh the file list."
                CommandStatus.ERROR_STORAGE_DENIED_VALUE -> "Flipper denied access. Check the destination and available storage space."
                CommandStatus.ERROR_STORAGE_INVALID_NAME_VALUE -> "Flipper rejected the filename. Use an ASCII name without path separators."
                else -> "Flipper could not complete the storage operation. Check the SD card and free space; details are available in Copy result."
            }
            FlipperRpcException.Reason.UNEXPECTED_RESPONSE -> "Flipper returned an unexpected reply. Reconnect and retry; copy the result if it repeats."
        }
        else -> if (error.message?.contains("changed", ignoreCase = true) == true)
            "The source or connected device changed. Select the file again on the intended Flipper."
        else "The file operation failed. Check access, free space and the connection, then retry. Copy the result for diagnostics."
    }
}
