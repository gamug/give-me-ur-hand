package com.givemeurhand.android.data

import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(val sessionId: String, val message: String)

@Serializable
data class ChatResponse(val reply: String, val kind: String)
