package dev.flipper.zerokit.sample.presentation.pages

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.flipper.api.Flipper
import javax.inject.Inject

/** Host-specific DI; neither library requires Hilt. */
@HiltViewModel
class FlipperHostViewModel @Inject constructor(
    val flipper: Flipper,
) : ViewModel()
