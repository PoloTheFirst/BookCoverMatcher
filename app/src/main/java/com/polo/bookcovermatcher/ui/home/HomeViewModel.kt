package com.polo.bookcovermatcher.ui.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Simple ViewModel placeholder for future state. */
class HomeViewModel : ViewModel() {
    // UI state – no fields yet.
    data class UiState(val dummy: Boolean = true)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState
}