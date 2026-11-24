package dev.flipper.zerokit.sample.domain.repository

import dev.flipper.zerokit.sample.domain.model.FlipperFile

interface FlipperFileRepository {
    suspend fun loadFiles(): List<FlipperFile>
}
