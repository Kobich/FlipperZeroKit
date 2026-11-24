package dev.flipper.zerokit.sample.presentation.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.flipper.zerokit.sample.domain.model.ThemeMode
import dev.flipper.zerokit.sample.domain.repository.ThemeRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val repository: ThemeRepository,
) : ViewModel() {
    val theme = repository.theme.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ThemeMode.SYSTEM,
    )

    fun toggle(currentlyDark: Boolean) {
        viewModelScope.launch {
            repository.setTheme(if (currentlyDark) ThemeMode.LIGHT else ThemeMode.DARK)
        }
    }
}
