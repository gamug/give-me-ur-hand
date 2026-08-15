package com.givemeurhand.android.ui.chat

import com.givemeurhand.android.MainDispatcherRule
import com.givemeurhand.android.data.ChatResponse
import com.givemeurhand.android.data.FakeChatApiClient
import com.givemeurhand.android.data.FakeSessionIdProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ChatViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `sending a message adds the user message immediately and the reply once it arrives`() = runTest {
        val api = FakeChatApiClient(ChatResponse("Respira profundo.", "answer"))
        val viewModel = ChatViewModel(api, FakeSessionIdProvider("s1"))

        viewModel.sendMessage("hola")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.messages.size)
        assertEquals("hola", state.messages[0].text)
        assertEquals(true, state.messages[0].fromUser)
        assertEquals("Respira profundo.", state.messages[1].text)
        assertEquals(false, state.messages[1].fromUser)
        assertFalse(state.isSending)
        assertEquals("s1", api.lastSessionId)
    }

    @Test
    fun `a failed request surfaces an error message and stops the sending indicator`() = runTest {
        val api = FakeChatApiClient(error = RuntimeException("network down"))
        val viewModel = ChatViewModel(api, FakeSessionIdProvider("s1"))

        viewModel.sendMessage("hola")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSending)
        assertEquals("No pudimos enviar tu mensaje. Intenta de nuevo.", state.errorMessage)
    }

    @Test
    fun `blank messages are ignored`() = runTest {
        val viewModel = ChatViewModel(FakeChatApiClient(), FakeSessionIdProvider("s1"))

        viewModel.sendMessage("   ")
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.messages.size)
    }

    @Test
    fun `a CancellationException propagates and does not update the error state`() = runTest {
        val api = FakeChatApiClient(error = CancellationException("cancelled"))
        val viewModel = ChatViewModel(api, FakeSessionIdProvider("s1"))

        viewModel.sendMessage("hola")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        // The user message should be in the state (added before the cancellation)
        assertEquals(1, state.messages.size)
        assertEquals("hola", state.messages[0].text)
        // But the error state should NOT be updated
        assertNull(state.errorMessage)
    }
}
