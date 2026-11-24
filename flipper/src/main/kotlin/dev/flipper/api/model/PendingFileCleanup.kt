package dev.flipper.api.model

/** An app-owned provisional upload path that must be removed when this device becomes available. */
data class PendingFileCleanup(val deviceAddress: String, val path: String)
