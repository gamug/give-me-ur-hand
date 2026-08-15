package com.givemeurhand.android.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

interface ProfessionalApiClient {
    suspend fun login(username: String, password: String): Boolean
    suspend fun listCases(): List<CaseResponse>
    suspend fun closeCase(caseId: String): Boolean
}

class HttpProfessionalApiClient(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val tokenStore: TokenStore
) : ProfessionalApiClient {
    override suspend fun login(username: String, password: String): Boolean = try {
        val response: LoginResponse = httpClient.post("$baseUrl/professionals/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, password))
        }.body()
        tokenStore.set(response.token)
        true
    } catch (e: ClientRequestException) {
        false
    }

    override suspend fun listCases(): List<CaseResponse> =
        httpClient.get("$baseUrl/professionals/me/cases") {
            header(HttpHeaders.Authorization, "Bearer ${tokenStore.get()}")
        }.body()

    override suspend fun closeCase(caseId: String): Boolean = try {
        httpClient.post("$baseUrl/professionals/cases/$caseId/close") {
            header(HttpHeaders.Authorization, "Bearer ${tokenStore.get()}")
        }
        true
    } catch (e: ClientRequestException) {
        false
    }
}
