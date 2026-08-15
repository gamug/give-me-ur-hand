package com.givemeurhand.android.ui.professional

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.givemeurhand.android.data.CaseResponse
import com.givemeurhand.android.data.ProfessionalApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfessionalDashboardUiState(
    val cases: List<CaseResponse> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class ProfessionalDashboardViewModel(
    private val professionalApiClient: ProfessionalApiClient
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProfessionalDashboardUiState())
    val uiState: StateFlow<ProfessionalDashboardUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val cases = professionalApiClient.listCases()
                _uiState.update { it.copy(cases = cases, isLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "No pudimos actualizar los casos. Intenta de nuevo.")
                }
            }
        }
    }

    fun closeCase(caseId: String) {
        viewModelScope.launch {
            try {
                if (professionalApiClient.closeCase(caseId)) refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = "No pudimos actualizar los casos. Intenta de nuevo.")
                }
            }
        }
    }
}
