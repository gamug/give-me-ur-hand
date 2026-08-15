package com.givemeurhand.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.givemeurhand.android.data.ChatApiClient
import com.givemeurhand.android.data.SessionIdProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(val text: String, val fromUser: Boolean)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isSending: Boolean = false,
    val errorMessage: String? = null
)

class ChatViewModel(
    private val chatApiClient: ChatApiClient,
    private val sessionIdProvider: SessionIdProvider
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        _uiState.update {
            it.copy(messages = it.messages + ChatMessage(text, fromUser = true), isSending = true, errorMessage = null)
        }

        viewModelScope.launch {
            try {
                val sessionId = sessionIdProvider.getOrCreate()
                val response = chatApiClient.sendMessage(sessionId, text)
                _uiState.update {
                    it.copy(messages = it.messages + ChatMessage(response.reply, fromUser = false), isSending = false)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSending = false, errorMessage = "No pudimos enviar tu mensaje. Intenta de nuevo.")
                }
            }
        }
    }
}
