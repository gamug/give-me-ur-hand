// android/app/src/main/kotlin/com/givemeurhand/android/ui/chat/ChatScreen.kt
package com.givemeurhand.android.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val itemCount = uiState.messages.size + if (uiState.isSending) 1 else 0

    // Keep the latest messages in view whenever the list grows. Without this, a short
    // conversation that doesn't yet fill the screen stays scrolled to its initial (top) position,
    // so when the keyboard later shrinks the visible chat area, it clips whatever was showing near
    // the bottom instead of scrolling to compensate — the messages appear to vanish behind the
    // keyboard. Longer conversations don't show the bug because they're already scrolled near the
    // bottom by the time this fires repeatedly.
    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Column(modifier = Modifier.fillMaxSize().imePadding().padding(16.dp)) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(uiState.messages) { message -> ChatBubble(text = message.text, fromUser = message.fromUser) }
            if (uiState.isSending) {
                item { Text("Escribiendo...", modifier = Modifier.padding(8.dp)) }
            }
        }

        uiState.errorMessage?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp))
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    // The keyboard opening is exactly the moment the visible chat area shrinks —
                    // re-scroll to the bottom right when the field gains focus, not just when the
                    // message list changes, so the latest messages stay visible while typing.
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused && itemCount > 0) {
                            coroutineScope.launch { listState.animateScrollToItem(itemCount - 1) }
                        }
                    },
                placeholder = { Text("Cuéntame cómo te sientes...") }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                viewModel.sendMessage(input)
                input = ""
            }) {
                Text("Enviar")
            }
        }
    }
}

@Composable
private fun ChatBubble(text: String, fromUser: Boolean) {
    val alignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = alignment) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(4.dp)
        ) {
            Text(text, modifier = Modifier.padding(12.dp))
        }
    }
}
