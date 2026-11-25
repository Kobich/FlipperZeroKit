package dev.flipper.api.model

/** Capacity reported by Flipper for the requested storage path, in bytes. */
data class StorageInfo(val totalBytes: Long, val freeBytes: Long) {
    init {
        require(totalBytes >= 0 && freeBytes in 0..totalBytes)
    }

    val usedBytes: Long get() = totalBytes - freeBytes
}
