package com.givemeurhand.android.data

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(val token: String)

@Serializable
data class CaseResponse(
    val id: String,
    val reasonSnippet: String,
    val status: String,
    val assignedAt: String,
    val closedAt: String?
)
