package dev.flipper.internal.rpc

/** Only unguessable transfer temp paths are eligible for automatic remote deletion. */
internal fun isOwnedUploadTemp(path: String): Boolean =
    path.startsWith('/') && path.split('/').none { it == "." || it == ".." } &&
        path.substringAfterLast('/').matches(Regex("\\.flipper-transfer-[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}\\.part"))

/** SD-side hashing/copying has no progress messages. Bound that phase, not the whole BLE transfer. */
internal fun finalizationTimeout(bytes: Long): Long =
    120_000L + (bytes.coerceAtLeast(0) / 65_536L).coerceAtMost(780) * 1_000L
