package com.givemeurhand.android.data

interface TokenStore {
    fun get(): String?
    fun set(token: String?)
}

class InMemoryTokenStore : TokenStore {
    @Volatile private var token: String? = null
    override fun get(): String? = token
    override fun set(token: String?) { this.token = token }
}
