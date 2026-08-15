package com.givemeurhand.android.data

class FakeSessionIdProvider(private val id: String = "fixed-session") : SessionIdProvider {
    override suspend fun getOrCreate(): String = id
}
