package com.givemeurhand.android.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.UUID

object SessionIdGenerator {
    fun generate(): String = UUID.randomUUID().toString()
}

interface SessionIdProvider {
    suspend fun getOrCreate(): String
}

private val Context.sessionDataStore by preferencesDataStore(name = "session")
private val SESSION_ID_KEY = stringPreferencesKey("session_id")

class DataStoreSessionIdProvider(private val context: Context) : SessionIdProvider {
    override suspend fun getOrCreate(): String {
        val existing = context.sessionDataStore.data.first()[SESSION_ID_KEY]
        if (existing != null) return existing

        val generated = SessionIdGenerator.generate()
        context.sessionDataStore.edit { it[SESSION_ID_KEY] = generated }
        return generated
    }
}
