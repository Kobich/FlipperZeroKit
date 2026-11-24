package dev.flipper.zerokit.sample.presentation.pages.files

import dev.flipper.zerokit.sample.domain.model.FlipperFile

data class FilesUiState(
    val isLoading: Boolean = true,
    val files: List<FlipperFile> = emptyList(),
    val hasError: Boolean = false,
)
