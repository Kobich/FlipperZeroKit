package dev.flipper.zerokit.sample.domain.repository

import dev.flipper.zerokit.sample.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

interface ThemeRepository {
    val theme: Flow<ThemeMode>
    suspend fun setTheme(value: ThemeMode)
}
