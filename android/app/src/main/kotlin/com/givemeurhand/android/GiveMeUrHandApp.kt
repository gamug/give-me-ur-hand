package com.givemeurhand.android

import android.app.Application
import com.givemeurhand.android.data.ChatApiClient
import com.givemeurhand.android.data.HttpChatApiClient
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
}
