package dev.flipper.api.model

data class StorageEntry(val name: String, val isDirectory: Boolean, val sizeBytes: Long)

data class TransferProgress(val bytesTransferred: Long, val totalBytes: Long?)

/** Returned only after the complete RPC exchange and matching local/device MD5. */
data class TransferResult(val bytesTransferred: Long, val md5: String, val path: String = "")
