package com.givemeurhand.android

import android.app.Application
import com.givemeurhand.android.data.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

class GiveMeUrHandApp : Application() {
    private val httpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = true
            install(ContentNegotiation) { json() }
        }
    }

    val chatApiClient: ChatApiClient by lazy { HttpChatApiClient(httpClient, BuildConfig.BACKEND_BASE_URL) }
    val sessionIdProvider: SessionIdProvider by lazy { DataStoreSessionIdProvider(this) }

    val tokenStore: TokenStore by lazy { InMemoryTokenStore() }
    val professionalApiClient: ProfessionalApiClient by lazy {
        HttpProfessionalApiClient(httpClient, BuildConfig.BACKEND_BASE_URL, tokenStore)
    }
}
