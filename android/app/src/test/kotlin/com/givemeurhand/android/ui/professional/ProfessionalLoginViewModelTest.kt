package com.givemeurhand.android.ui.professional

import com.givemeurhand.android.MainDispatcherRule
import com.givemeurhand.android.data.FakeProfessionalApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfessionalLoginViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `successful login sets loggedIn to true`() = runTest {
        val viewModel = ProfessionalLoginViewModel(FakeProfessionalApiClient(loginResult = true))

        viewModel.login("ana", "clave123")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.loggedIn)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `failed login surfaces an error message`() = runTest {
        val viewModel = ProfessionalLoginViewModel(FakeProfessionalApiClient(loginResult = false))

        viewModel.login("ana", "mala-clave")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.loggedIn)
        assertEquals("Usuario o contraseña incorrectos.", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `a thrown exception during login surfaces a distinct error message and stops loading`() = runTest {
        val viewModel = ProfessionalLoginViewModel(
            FakeProfessionalApiClient(loginException = RuntimeException("server error"))
        )

        viewModel.login("ana", "clave123")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loggedIn)
        assertFalse(state.isLoading)
        assertEquals("No pudimos iniciar sesión. Intenta de nuevo.", state.errorMessage)
    }

    @Test
    fun `a CancellationException during login propagates and does not update the error state`() = runTest {
        val viewModel = ProfessionalLoginViewModel(
            FakeProfessionalApiClient(loginException = CancellationException("cancelled"))
        )

        viewModel.login("ana", "clave123")
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.loggedIn)
    }
}
