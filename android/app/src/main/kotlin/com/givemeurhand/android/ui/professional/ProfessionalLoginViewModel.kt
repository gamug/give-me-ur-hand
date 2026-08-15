package com.givemeurhand.android.ui.professional

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.givemeurhand.android.data.ProfessionalApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfessionalLoginUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val loggedIn: Boolean = false
)

class ProfessionalLoginViewModel(
    private val professionalApiClient: ProfessionalApiClient
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProfessionalLoginUiState())
    val uiState: StateFlow<ProfessionalLoginUiState> = _uiState.asStateFlow()

    fun login(username: String, password: String) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val success = professionalApiClient.login(username, password)
                _uiState.update {
                    if (success) it.copy(isLoading = false, loggedIn = true)
                    else it.copy(isLoading = false, errorMessage = "Usuario o contraseña incorrectos.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "No pudimos iniciar sesión. Intenta de nuevo.")
                }
            }
        }
    }
}
