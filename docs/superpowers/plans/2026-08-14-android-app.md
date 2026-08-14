# Android App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Jetpack Compose Android app (`android/`) with a calm-themed Lobby, a working chat screen against the backend's `POST /chat`, and a professional login + case dashboard against `POST /professionals/login`, `GET /professionals/me/cases`, `POST /professionals/cases/{id}/close`.

**Architecture:** MVVM. Each screen is a stateless `@Composable` fed by a `StateFlow<UiState>` from a `ViewModel`; ViewModels depend on small API-client interfaces (`ChatApiClient`, `ProfessionalApiClient`) so they're unit-testable with fakes — no Android framework dependency in the tested logic. A single `GiveMeUrHandApp` (custom `Application`) builds the shared `HttpClient` and exposes the concrete implementations; there is no DI framework for this MVP. Per the spec, only ViewModels get automated tests — Compose screens are verified by running the app.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Navigation-Compose, Ktor Client (OkHttp engine), kotlinx.serialization, AndroidX DataStore Preferences, JUnit4 + kotlinx-coroutines-test + ktor-client-mock for ViewModel/API-client tests.

**Spec:** `docs/superpowers/specs/2026-08-14-give-me-ur-hand-mvp-design.md`

## Global Constraints

- All user-facing text is in Spanish.
- The app talks to the backend over HTTP only — it never touches Mongo or DeepSeek directly, and holds no secrets. The backend base URL is a build-time value (`BuildConfig.BACKEND_BASE_URL`), not a runtime secret.
- No login for the victim-facing flow (Lobby/Chat); a per-install anonymous `sessionId` (UUID) is generated once and persisted via DataStore, sent with every `/chat` request.
- Lobby shows 6 tiles: "Chat de apoyo psicológico" (active) and 5 disabled "Próximamente" tiles (Reporta tu estado / Estoy bien, Directorio de contactos de emergencia, Mapa de refugios/albergues, Recursos y guías descargables, Chequeo de integridad estructural), plus a discreet "Acceso profesionales" link.
- Professional session token is held in memory only for this MVP (`InMemoryTokenStore`) — closing the app requires logging in again; this is an accepted MVP trade-off, not an oversight.
- Package root: `com.givemeurhand.android`. App label: "Da Una Mano".

---

### Task 1: Android project scaffold + ChatApiClient

**Files:**
- Create: `android/settings.gradle.kts`
- Create: `android/build.gradle.kts`
- Create: `android/gradle.properties`
- Create: `android/app/build.gradle.kts`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/res/values/styles.xml`
- Create: `android/app/src/main/kotlin/com/givemeurhand/android/GiveMeUrHandApp.kt`
- Create: `android/app/src/main/kotlin/com/givemeurhand/android/data/ChatModels.kt`
- Create: `android/app/src/main/kotlin/com/givemeurhand/android/data/ChatApiClient.kt`
- Test: `android/app/src/test/kotlin/com/givemeurhand/android/data/HttpChatApiClientTest.kt`

**Interfaces:**
- Produces: `@Serializable data class ChatRequest(val sessionId: String, val message: String)`, `@Serializable data class ChatResponse(val reply: String, val kind: String)`, `interface ChatApiClient { suspend fun sendMessage(sessionId: String, message: String): ChatResponse }`, `class HttpChatApiClient(httpClient: HttpClient, baseUrl: String) : ChatApiClient`, `class GiveMeUrHandApp : Application()` exposing `val chatApiClient: ChatApiClient` (this class grows in later tasks).

- [ ] **Step 1: Create the Gradle scaffold**

`android/settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "give-me-ur-hand-android"
include(":app")
```

`android/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
}
```

`android/gradle.properties`:
```properties
android.useAndroidX=true
kotlin.code.style=official
```

`android/app/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.givemeurhand.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.givemeurhand.android"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        val backendBaseUrl = project.findProperty("backendBaseUrl") as String? ?: "http://10.0.2.2:8080"
        buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.ktor:ktor-client-mock:2.3.12")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
```

`android/app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:name=".GiveMeUrHandApp"
        android:allowBackup="true"
        android:label="Da Una Mano"
        android:theme="@style/Theme.GiveMeUrHand">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.GiveMeUrHand">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`android/app/src/main/res/values/styles.xml`:
```xml
<resources>
    <style name="Theme.GiveMeUrHand" parent="Theme.Material3.DayNight.NoActionBar" />
</resources>
```

- [ ] **Step 2: Write the failing test**

```kotlin
// android/app/src/test/kotlin/com/givemeurhand/android/data/HttpChatApiClientTest.kt
package com.givemeurhand.android.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpChatApiClientTest {
    @Test
    fun `posts to baseUrl chat and parses the response`() = runTest {
        var capturedUrl: String? = null
        val engine = MockEngine { request ->
            capturedUrl = request.url.toString()
            respond(
                content = """{"reply":"hola","kind":"answer"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(engine) { install(ContentNegotiation) { json() } }
        val client: ChatApiClient = HttpChatApiClient(httpClient, "http://localhost:8080")

        val result = client.sendMessage("session-1", "hola")

        assertEquals(ChatResponse("hola", "answer"), result)
        assertEquals("http://localhost:8080/chat", capturedUrl)
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.givemeurhand.android.data.HttpChatApiClientTest"`
Expected: FAIL — classes don't exist yet.

- [ ] **Step 4: Write minimal implementation**

```kotlin
// android/app/src/main/kotlin/com/givemeurhand/android/data/ChatModels.kt
package com.givemeurhand.android.data

import kotlinx.serialization.Serializable

@Serializable
data class ChatRequest(val sessionId: String, val message: String)

@Serializable
data class ChatResponse(val reply: String, val kind: String)
```

```kotlin
// android/app/src/main/kotlin/com/givemeurhand/android/data/ChatApiClient.kt
package com.givemeurhand.android.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

interface ChatApiClient {
    suspend fun sendMessage(sessionId: String, message: String): ChatResponse
}

class HttpChatApiClient(
    private val httpClient: HttpClient,
    private val baseUrl: String
) : ChatApiClient {
    override suspend fun sendMessage(sessionId: String, message: String): ChatResponse =
        httpClient.post("$baseUrl/chat") {
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(sessionId, message))
        }.body()
}
```

```kotlin
// android/app/src/main/kotlin/com/givemeurhand/android/GiveMeUrHandApp.kt
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
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.givemeurhand.android.data.HttpChatApiClientTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add android/settings.gradle.kts android/build.gradle.kts android/gradle.properties android/app/build.gradle.kts android/app/src/main/AndroidManifest.xml android/app/src/main/res/values/styles.xml android/app/src/main/kotlin/com/givemeurhand/android/GiveMeUrHandApp.kt android/app/src/main/kotlin/com/givemeurhand/android/data/ android/app/src/test/kotlin/com/givemeurhand/android/data/HttpChatApiClientTest.kt
git commit -m "feat(android): scaffold app and add ChatApiClient"
```

---

### Task 2: SessionId + ChatViewModel

**Files:**
- Create: `android/app/src/main/kotlin/com/givemeurhand/android/data/SessionIdProvider.kt`
- Create: `android/app/src/main/kotlin/com/givemeurhand/android/ui/chat/ChatViewModel.kt`
- Create: `android/app/src/test/kotlin/com/givemeurhand/android/MainDispatcherRule.kt`
- Create: `android/app/src/test/kotlin/com/givemeurhand/android/data/FakeChatApiClient.kt`
- Create: `android/app/src/test/kotlin/com/givemeurhand/android/data/FakeSessionIdProvider.kt`
- Test: `android/app/src/test/kotlin/com/givemeurhand/android/data/SessionIdGeneratorTest.kt`
- Test: `android/app/src/test/kotlin/com/givemeurhand/android/ui/chat/ChatViewModelTest.kt`
- Modify: `android/app/src/main/kotlin/com/givemeurhand/android/GiveMeUrHandApp.kt` (add `sessionIdProvider`)

**Interfaces:**
- Consumes: `ChatApiClient`, `ChatResponse` (Task 1).
- Produces: `object SessionIdGenerator { fun generate(): String }`, `interface SessionIdProvider { suspend fun getOrCreate(): String }`, `class DataStoreSessionIdProvider(context: Context) : SessionIdProvider`. `data class ChatMessage(val text: String, val fromUser: Boolean)`, `data class ChatUiState(val messages: List<ChatMessage> = emptyList(), val isSending: Boolean = false, val errorMessage: String? = null)`, `class ChatViewModel(chatApiClient: ChatApiClient, sessionIdProvider: SessionIdProvider) : ViewModel() { val uiState: StateFlow<ChatUiState>; fun sendMessage(text: String) }`. `MainDispatcherRule` (JUnit `TestWatcher` reused by every later ViewModel test task).

- [ ] **Step 1: Write the failing tests**

```kotlin
// android/app/src/test/kotlin/com/givemeurhand/android/MainDispatcherRule.kt
package com.givemeurhand.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class MainDispatcherRule(private val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
```

```kotlin
// android/app/src/test/kotlin/com/givemeurhand/android/data/SessionIdGeneratorTest.kt
package com.givemeurhand.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.UUID

class SessionIdGeneratorTest {
    @Test
    fun `generates a valid UUID`() {
        val id = SessionIdGenerator.generate()
        assertEquals(id, UUID.fromString(id).toString())
    }

    @Test
    fun `generates a different id each time`() {
        assertNotEquals(SessionIdGenerator.generate(), SessionIdGenerator.generate())
    }
}
```

```kotlin
// android/app/src/test/kotlin/com/givemeurhand/android/data/FakeChatApiClient.kt
package com.givemeurhand.android.data

class FakeChatApiClient(
    private val response: ChatResponse = ChatResponse("ok", "answer"),
    private val error: Exception? = null
) : ChatApiClient {
    var lastSessionId: String? = null
    var lastMessage: String? = null

    override suspend fun sendMessage(sessionId: String, message: String): ChatResponse {
        lastSessionId = sessionId
        lastMessage = message
        error?.let { throw it }
        return response
    }
}
```

```kotlin
// android/app/src/test/kotlin/com/givemeurhand/android/data/FakeSessionIdProvider.kt
package com.givemeurhand.android.data

class FakeSessionIdProvider(private val id: String = "fixed-session") : SessionIdProvider {
    override suspend fun getOrCreate(): String = id
}
```

```kotlin
// android/app/src/test/kotlin/com/givemeurhand/android/ui/chat/ChatViewModelTest.kt
package com.givemeurhand.android.ui.chat

import com.givemeurhand.android.MainDispatcherRule
import com.givemeurhand.android.data.ChatResponse
import com.givemeurhand.android.data.FakeChatApiClient
import com.givemeurhand.android.data.FakeSessionIdProvider
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class ChatViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `sending a message adds the user message immediately and the reply once it arrives`() = runTest {
        val api = FakeChatApiClient(ChatResponse("Respira profundo.", "answer"))
        val viewModel = ChatViewModel(api, FakeSessionIdProvider("s1"))

        viewModel.sendMessage("hola")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.messages.size)
        assertEquals("hola", state.messages[0].text)
        assertEquals(true, state.messages[0].fromUser)
        assertEquals("Respira profundo.", state.messages[1].text)
        assertEquals(false, state.messages[1].fromUser)
        assertFalse(state.isSending)
        assertEquals("s1", api.lastSessionId)
    }

    @Test
    fun `a failed request surfaces an error message and stops the sending indicator`() = runTest {
        val api = FakeChatApiClient(error = RuntimeException("network down"))
        val viewModel = ChatViewModel(api, FakeSessionIdProvider("s1"))

        viewModel.sendMessage("hola")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSending)
        assertEquals("No pudimos enviar tu mensaje. Intenta de nuevo.", state.errorMessage)
    }

    @Test
    fun `blank messages are ignored`() = runTest {
        val viewModel = ChatViewModel(FakeChatApiClient(), FakeSessionIdProvider("s1"))

        viewModel.sendMessage("   ")
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.messages.size)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.givemeurhand.android.data.SessionIdGeneratorTest" --tests "com.givemeurhand.android.ui.chat.ChatViewModelTest"`
Expected: FAIL — `SessionIdGenerator`/`ChatViewModel` don't exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// android/app/src/main/kotlin/com/givemeurhand/android/data/SessionIdProvider.kt
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
```

```kotlin
// android/app/src/main/kotlin/com/givemeurhand/android/ui/chat/ChatViewModel.kt
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
```

Update `GiveMeUrHandApp.kt` to expose the session provider:

```kotlin
// android/app/src/main/kotlin/com/givemeurhand/android/GiveMeUrHandApp.kt
package com.givemeurhand.android

import android.app.Application
import com.givemeurhand.android.data.ChatApiClient
import com.givemeurhand.android.data.DataStoreSessionIdProvider
import com.givemeurhand.android.data.HttpChatApiClient
import com.givemeurhand.android.data.SessionIdProvider
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
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: PASS (all tests so far)

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/com/givemeurhand/android/data/SessionIdProvider.kt android/app/src/main/kotlin/com/givemeurhand/android/ui/chat/ChatViewModel.kt android/app/src/main/kotlin/com/givemeurhand/android/GiveMeUrHandApp.kt android/app/src/test/kotlin/com/givemeurhand/android/MainDispatcherRule.kt android/app/src/test/kotlin/com/givemeurhand/android/data/FakeChatApiClient.kt android/app/src/test/kotlin/com/givemeurhand/android/data/FakeSessionIdProvider.kt android/app/src/test/kotlin/com/givemeurhand/android/data/SessionIdGeneratorTest.kt android/app/src/test/kotlin/com/givemeurhand/android/ui/chat/ChatViewModelTest.kt
git commit -m "feat(android): add session id provider and ChatViewModel"
```

---

### Task 3: Theme + Lobby screen + navigation skeleton

**Files:**
- Create: `android/app/src/main/kotlin/com/givemeurhand/android/ui/theme/Theme.kt`
- Create: `android/app/src/main/kotlin/com/givemeurhand/android/ui/lobby/LobbyScreen.kt`
- Create: `android/app/src/main/kotlin/com/givemeurhand/android/MainActivity.kt`

**Interfaces:**
- Produces: `@Composable fun GiveMeUrHandTheme(content: @Composable () -> Unit)`. `@Composable fun LobbyScreen(onOpenChat: () -> Unit, onOpenProfessionalAccess: () -> Unit)`. `class MainActivity : ComponentActivity` hosting a `NavHost` with routes `"lobby"`, `"chat"`, `"professionalLogin"`, `"professionalDashboard"` (the last three are placeholder composables until Tasks 4, 7, 8, 9 fill them in).

Compose UI has no automated test in this plan (per the spec's testing scope) — verification is manual (Step 2).

- [ ] **Step 1: Write the implementation**

```kotlin
// android/app/src/main/kotlin/com/givemeurhand/android/ui/theme/Theme.kt
package com.givemeurhand.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CalmGreen = Color(0xFF4E9E8E)
val CalmGreenLight = Color(0xFFB7E4D8)
val WarmWhite = Color(0xFFFAF7F2)
val CalmTextDark = Color(0xFF2E3A3A)

private val LightColors = lightColorScheme(
    primary = CalmGreen,
    secondary = CalmGreenLight,
    background = WarmWhite,
    surface = WarmWhite,
    onPrimary = Color.White,
    onBackground = CalmTextDark,
    onSurface = CalmTextDark
)

private val DarkColors = darkColorScheme(
    primary = CalmGreenLight,
    secondary = CalmGreen,
    background = Color(0xFF1B2422),
    surface = Color(0xFF1B2422)
)

@Composable
fun GiveMeUrHandTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
```

```kotlin
// android/app/src/main/kotlin/com/givemeurhand/android/ui/lobby/LobbyScreen.kt
package com.givemeurhand.android.ui.lobby

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class LobbyTile(val title: String, val enabled: Boolean, val onClick: () -> Unit = {})

@Composable
fun LobbyScreen(
    onOpenChat: () -> Unit,
    onOpenProfessionalAccess: () -> Unit
) {
    val tiles = listOf(
        LobbyTile("Chat de apoyo psicológico", enabled = true, onClick = onOpenChat),
        LobbyTile("Reporta tu estado / Estoy bien", enabled = false),
        LobbyTile("Directorio de contactos de emergencia", enabled = false),
        LobbyTile("Mapa de refugios/albergues", enabled = false),
        LobbyTile("Recursos y guías descargables", enabled = false),
        LobbyTile("Chequeo de integridad estructural", enabled = false)
    )

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text = "Estamos contigo",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Elige cómo quieres que te acompañemos hoy",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(tiles) { tile -> LobbyTileCard(tile) }
        }

        TextButton(
            onClick = onOpenProfessionalAccess,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Acceso profesionales")
        }
    }
}

@Composable
private fun LobbyTileCard(tile: LobbyTile) {
    Card(
        onClick = tile.onClick,
        enabled = tile.enabled,
        modifier = Modifier.fillMaxWidth().height(120.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomStart) {
            Column {
                Text(tile.title, style = MaterialTheme.typography.titleMedium)
                if (!tile.enabled) {
                    Text(
                        "Próximamente",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
```

```kotlin
// android/app/src/main/kotlin/com/givemeurhand/android/MainActivity.kt
package com.givemeurhand.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.givemeurhand.android.ui.lobby.LobbyScreen
import com.givemeurhand.android.ui.theme.GiveMeUrHandTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GiveMeUrHandTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = "lobby") {
                        composable("lobby") {
                            LobbyScreen(
                                onOpenChat = { navController.navigate("chat") },
                                onOpenProfessionalAccess = { navController.navigate("professionalLogin") }
                            )
                        }
                        composable("chat") { /* wired in Task 4 */ }
                        composable("professionalLogin") { /* wired in Task 7 */ }
                        composable("professionalDashboard") { /* wired in Task 8 */ }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Manual verification**

```bash
cd android
./gradlew :app:assembleDebug
```

Expected: build succeeds. Install on an emulator/device (`./gradlew :app:installDebug`) and confirm the Lobby renders with 6 tiles (one enabled, five showing "Próximamente"), a warm/calm color palette, and the "Acceso profesionales" link at the bottom. Tapping the active tile should navigate to an empty screen for now (Task 4 fills it in).

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/com/givemeurhand/android/ui/theme/Theme.kt android/app/src/main/kotlin/com/givemeurhand/android/ui/lobby/LobbyScreen.kt android/app/src/main/kotlin/com/givemeurhand/android/MainActivity.kt
git commit -m "feat(android): add calm theme, lobby screen and navigation skeleton"
```

---

### Task 4: Chat screen

**Files:**
- Create: `android/app/src/main/kotlin/com/givemeurhand/android/ui/chat/ChatScreen.kt`
- Modify: `android/app/src/main/kotlin/com/givemeurhand/android/MainActivity.kt`

**Interfaces:**
- Consumes: `ChatViewModel`, `ChatUiState` (Task 2), `GiveMeUrHandApp` (Task 1/2).
- Produces: `@Composable fun ChatScreen(viewModel: ChatViewModel)`.

- [ ] **Step 1: Write the implementation**

```kotlin
// android/app/src/main/kotlin/com/givemeurhand/android/ui/chat/ChatScreen.kt
package com.givemeurhand.android.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        LazyColumn(modifier = Modifier.weight(1f)) {
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
                modifier = Modifier.weight(1f),
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
```

Wire it into `MainActivity`'s `"chat"` route:

```kotlin
// android/app/src/main/kotlin/com/givemeurhand/android/MainActivity.kt
// (replace the "chat" composable placeholder; add these imports alongside the existing ones)
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.compose.viewModelFactory
import com.givemeurhand.android.ui.chat.ChatScreen
import com.givemeurhand.android.ui.chat.ChatViewModel
```

```kotlin
                        composable("chat") {
                            val app = LocalContext.current.applicationContext as GiveMeUrHandApp
                            val viewModel: ChatViewModel = viewModel(factory = viewModelFactory {
                                initializer { ChatViewModel(app.chatApiClient, app.sessionIdProvider) }
                            })
                            ChatScreen(viewModel)
                        }
```

- [ ] **Step 2: Manual verification**

```bash
cd android
./gradlew :app:assembleDebug
```

With the backend from the earlier plans running locally and reachable (adjust `backendBaseUrl` via `-PbackendBaseUrl=http://10.0.2.2:8080` for an emulator pointing at a host-machine backend), install the app, open the chat, send a message, and confirm the reply bubble appears once the backend responds.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/com/givemeurhand/android/ui/chat/ChatScreen.kt android/app/src/main/kotlin/com/givemeurhand/android/MainActivity.kt
git commit -m "feat(android): add chat screen"
```

---

### Task 5: ProfessionalApiClient

**Files:**
- Create: `android/app/src/main/kotlin/com/givemeurhand/android/data/TokenStore.kt`
- Create: `android/app/src/main/kotlin/com/givemeurhand/android/data/ProfessionalModels.kt`
- Create: `android/app/src/main/kotlin/com/givemeurhand/android/data/ProfessionalApiClient.kt`
- Modify: `android/app/src/main/kotlin/com/givemeurhand/android/GiveMeUrHandApp.kt` (add `tokenStore`, `professionalApiClient`)
- Test: `android/app/src/test/kotlin/com/givemeurhand/android/data/HttpProfessionalApiClientTest.kt`

**Interfaces:**
- Produces: `interface TokenStore { fun get(): String?; fun set(token: String?) }`, `class InMemoryTokenStore : TokenStore`. `@Serializable data class LoginRequest(val username: String, val password: String)`, `@Serializable data class LoginResponse(val token: String)`, `@Serializable data class CaseResponse(val id: String, val reasonSnippet: String, val status: String, val assignedAt: String, val closedAt: String?)`. `interface ProfessionalApiClient { suspend fun login(username: String, password: String): Boolean; suspend fun listCases(): List<CaseResponse>; suspend fun closeCase(caseId: String): Boolean }`, `class HttpProfessionalApiClient(httpClient: HttpClient, baseUrl: String, tokenStore: TokenStore) : ProfessionalApiClient`.

- [ ] **Step 1: Write the failing test**

```kotlin
// android/app/src/test/kotlin/com/givemeurhand/android/data/HttpProfessionalApiClientTest.kt
package com.givemeurhand.android.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpProfessionalApiClientTest {
    @Test
    fun `login stores the token on success`() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"token":"abc123"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(engine) { expectSuccess = true; install(ContentNegotiation) { json() } }
        val tokenStore = InMemoryTokenStore()
        val client: ProfessionalApiClient = HttpProfessionalApiClient(httpClient, "http://localhost:8080", tokenStore)

        assertTrue(client.login("ana", "clave123"))
        assertEquals("abc123", tokenStore.get())
    }

    @Test
    fun `login returns false on 401`() = runTest {
        val engine = MockEngine { respond(content = "", status = HttpStatusCode.Unauthorized) }
        val httpClient = HttpClient(engine) { expectSuccess = true; install(ContentNegotiation) { json() } }
        val client: ProfessionalApiClient =
            HttpProfessionalApiClient(httpClient, "http://localhost:8080", InMemoryTokenStore())

        assertFalse(client.login("ana", "mala-clave"))
    }

    @Test
    fun `listCases sends the stored token as a bearer header`() = runTest {
        var capturedAuth: String? = null
        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond(
                content = """[{"id":"1","reasonSnippet":"necesito ayuda","status":"active","assignedAt":"2026-08-14T10:00:00Z","closedAt":null}]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(engine) { expectSuccess = true; install(ContentNegotiation) { json() } }
        val tokenStore = InMemoryTokenStore().apply { set("abc123") }
        val client: ProfessionalApiClient = HttpProfessionalApiClient(httpClient, "http://localhost:8080", tokenStore)

        val cases = client.listCases()

        assertEquals(1, cases.size)
        assertEquals("Bearer abc123", capturedAuth)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.givemeurhand.android.data.HttpProfessionalApiClientTest"`
Expected: FAIL — classes don't exist yet.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// android/app/src/main/kotlin/com/givemeurhand/android/data/TokenStore.kt
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
```

```kotlin
// android/app/src/main/kotlin/com/givemeurhand/android/data/ProfessionalModels.kt
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
```

```kotlin
// android/app/src/main/kotlin/com/givemeurhand/android/data/ProfessionalApiClient.kt
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
```

Update `GiveMeUrHandApp.kt`:

```kotlin
// android/app/src/main/kotlin/com/givemeurhand/android/GiveMeUrHandApp.kt
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.givemeurhand.android.data.HttpProfessionalApiClientTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/com/givemeurhand/android/data/TokenStore.kt android/app/src/main/kotlin/com/givemeurhand/android/data/ProfessionalModels.kt android/app/src/main/kotlin/com/givemeurhand/android/data/ProfessionalApiClient.kt android/app/src/main/kotlin/com/givemeurhand/android/GiveMeUrHandApp.kt android/app/src/test/kotlin/com/givemeurhand/android/data/HttpProfessionalApiClientTest.kt
git commit -m "feat(android): add ProfessionalApiClient and token store"
```

---

### Task 6: Professional ViewModels

**Files:**
- Create: `android/app/src/main/kotlin/com/givemeurhand/android/ui/professional/ProfessionalLoginViewModel.kt`
- Create: `android/app/src/main/kotlin/com/givemeurhand/android/ui/professional/ProfessionalDashboardViewModel.kt`
- Create: `android/app/src/test/kotlin/com/givemeurhand/android/data/FakeProfessionalApiClient.kt`
- Test: `android/app/src/test/kotlin/com/givemeurhand/android/ui/professional/ProfessionalLoginViewModelTest.kt`
- Test: `android/app/src/test/kotlin/com/givemeurhand/android/ui/professional/ProfessionalDashboardViewModelTest.kt`

**Interfaces:**
- Consumes: `ProfessionalApiClient`, `CaseResponse` (Task 5), `MainDispatcherRule` (Task 2).
- Produces: `data class ProfessionalLoginUiState(val isLoading: Boolean = false, val errorMessage: String? = null, val loggedIn: Boolean = false)`, `class ProfessionalLoginViewModel(professionalApiClient: ProfessionalApiClient) : ViewModel() { val uiState: StateFlow<ProfessionalLoginUiState>; fun login(username: String, password: String) }`. `data class ProfessionalDashboardUiState(val cases: List<CaseResponse> = emptyList(), val isLoading: Boolean = false)`, `class ProfessionalDashboardViewModel(professionalApiClient: ProfessionalApiClient) : ViewModel() { val uiState: StateFlow<ProfessionalDashboardUiState>; fun refresh(); fun closeCase(caseId: String) }`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// android/app/src/test/kotlin/com/givemeurhand/android/data/FakeProfessionalApiClient.kt
package com.givemeurhand.android.data

class FakeProfessionalApiClient(
    private val loginResult: Boolean = true,
    private val cases: List<CaseResponse> = emptyList(),
    private val closeResult: Boolean = true
) : ProfessionalApiClient {
    val closedCaseIds = mutableListOf<String>()

    override suspend fun login(username: String, password: String): Boolean = loginResult
    override suspend fun listCases(): List<CaseResponse> = cases
    override suspend fun closeCase(caseId: String): Boolean {
        if (closeResult) closedCaseIds.add(caseId)
        return closeResult
    }
}
```

```kotlin
// android/app/src/test/kotlin/com/givemeurhand/android/ui/professional/ProfessionalLoginViewModelTest.kt
package com.givemeurhand.android.ui.professional

import com.givemeurhand.android.MainDispatcherRule
import com.givemeurhand.android.data.FakeProfessionalApiClient
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProfessionalLoginViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `successful login sets loggedIn to true`() = runTest {
        val viewModel = ProfessionalLoginViewModel(FakeProfessionalApiClient(loginResult = true))

        viewModel.login("ana", "clave123")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.loggedIn)
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `failed login surfaces an error message`() = runTest {
        val viewModel = ProfessionalLoginViewModel(FakeProfessionalApiClient(loginResult = false))

        viewModel.login("ana", "mala-clave")
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.loggedIn)
        assertEquals("Usuario o contraseña incorrectos.", viewModel.uiState.value.errorMessage)
    }
}
```

```kotlin
// android/app/src/test/kotlin/com/givemeurhand/android/ui/professional/ProfessionalDashboardViewModelTest.kt
package com.givemeurhand.android.ui.professional

import com.givemeurhand.android.MainDispatcherRule
import com.givemeurhand.android.data.CaseResponse
import com.givemeurhand.android.data.FakeProfessionalApiClient
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class ProfessionalDashboardViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun case(id: String, status: String = "active") =
        CaseResponse(id, "necesito ayuda", status, "2026-08-14T10:00:00Z", null)

    @Test
    fun `loads cases on init`() = runTest {
        val viewModel = ProfessionalDashboardViewModel(FakeProfessionalApiClient(cases = listOf(case("1"))))

        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.cases.size)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `closing a case refreshes the list`() = runTest {
        val api = FakeProfessionalApiClient(cases = listOf(case("1")))
        val viewModel = ProfessionalDashboardViewModel(api)
        advanceUntilIdle()

        viewModel.closeCase("1")
        advanceUntilIdle()

        assertEquals(listOf("1"), api.closedCaseIds)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.givemeurhand.android.ui.professional.*"`
Expected: FAIL — the ViewModels don't exist yet.

- [ ] **Step 3: Write minimal implementation**

```kotlin
// android/app/src/main/kotlin/com/givemeurhand/android/ui/professional/ProfessionalLoginViewModel.kt
package com.givemeurhand.android.ui.professional

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.givemeurhand.android.data.ProfessionalApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfessionalLoginUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val loggedIn: Boolean = false
)

class ProfessionalLoginViewModel(
    private val professionalApiClient: ProfessionalApiClient
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProfessionalLoginUiState())
    val uiState: StateFlow<ProfessionalLoginUiState> = _uiState.asStateFlow()

    fun login(username: String, password: String) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val success = professionalApiClient.login(username, password)
            _uiState.update {
                if (success) it.copy(isLoading = false, loggedIn = true)
                else it.copy(isLoading = false, errorMessage = "Usuario o contraseña incorrectos.")
            }
        }
    }
}
```

```kotlin
// android/app/src/main/kotlin/com/givemeurhand/android/ui/professional/ProfessionalDashboardViewModel.kt
package com.givemeurhand.android.ui.professional

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.givemeurhand.android.data.CaseResponse
import com.givemeurhand.android.data.ProfessionalApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfessionalDashboardUiState(
    val cases: List<CaseResponse> = emptyList(),
    val isLoading: Boolean = false
)

class ProfessionalDashboardViewModel(
    private val professionalApiClient: ProfessionalApiClient
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProfessionalDashboardUiState())
    val uiState: StateFlow<ProfessionalDashboardUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val cases = professionalApiClient.listCases()
            _uiState.update { it.copy(cases = cases, isLoading = false) }
        }
    }

    fun closeCase(caseId: String) {
        viewModelScope.launch {
            if (professionalApiClient.closeCase(caseId)) refresh()
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: PASS (all tests so far)

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/com/givemeurhand/android/ui/professional/ android/app/src/test/kotlin/com/givemeurhand/android/data/FakeProfessionalApiClient.kt android/app/src/test/kotlin/com/givemeurhand/android/ui/professional/
git commit -m "feat(android): add professional login and dashboard ViewModels"
```

---

### Task 7: Professional login screen

**Files:**
- Create: `android/app/src/main/kotlin/com/givemeurhand/android/ui/professional/ProfessionalLoginScreen.kt`
- Modify: `android/app/src/main/kotlin/com/givemeurhand/android/MainActivity.kt`

**Interfaces:**
- Consumes: `ProfessionalLoginViewModel`, `ProfessionalLoginUiState` (Task 6).
- Produces: `@Composable fun ProfessionalLoginScreen(viewModel: ProfessionalLoginViewModel, onLoggedIn: () -> Unit)`.

- [ ] **Step 1: Write the implementation**

```kotlin
// android/app/src/main/kotlin/com/givemeurhand/android/ui/professional/ProfessionalLoginScreen.kt
package com.givemeurhand.android.ui.professional

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ProfessionalLoginScreen(viewModel: ProfessionalLoginViewModel, onLoggedIn: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(uiState.loggedIn) {
        if (uiState.loggedIn) onLoggedIn()
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Acceso profesionales", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Usuario") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Contraseña") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        )

        uiState.errorMessage?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp))
        }

        Button(onClick = { viewModel.login(username, password) }, enabled = !uiState.isLoading) {
            Text("Ingresar")
        }
    }
}
```

Wire it into `MainActivity`'s `"professionalLogin"` route (add imports for `ProfessionalLoginScreen`, `ProfessionalLoginViewModel` alongside the existing ones):

```kotlin
                        composable("professionalLogin") {
                            val app = LocalContext.current.applicationContext as GiveMeUrHandApp
                            val viewModel: ProfessionalLoginViewModel = viewModel(factory = viewModelFactory {
                                initializer { ProfessionalLoginViewModel(app.professionalApiClient) }
                            })
                            ProfessionalLoginScreen(
                                viewModel = viewModel,
                                onLoggedIn = { navController.navigate("professionalDashboard") }
                            )
                        }
```

- [ ] **Step 2: Manual verification**

```bash
cd android
./gradlew :app:assembleDebug
```

Expected: build succeeds, the text fields size correctly (not full-screen). With the backend running and a seeded professional (from the professional coordination plan), confirm logging in with correct credentials navigates to the dashboard route (empty until Task 8) and wrong credentials shows the Spanish error message.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/com/givemeurhand/android/ui/professional/ProfessionalLoginScreen.kt android/app/src/main/kotlin/com/givemeurhand/android/MainActivity.kt
git commit -m "feat(android): add professional login screen"
```

---

### Task 8: Professional dashboard screen

**Files:**
- Create: `android/app/src/main/kotlin/com/givemeurhand/android/ui/professional/ProfessionalDashboardScreen.kt`
- Modify: `android/app/src/main/kotlin/com/givemeurhand/android/MainActivity.kt`

**Interfaces:**
- Consumes: `ProfessionalDashboardViewModel`, `ProfessionalDashboardUiState`, `CaseResponse` (Task 6).
- Produces: `@Composable fun ProfessionalDashboardScreen(viewModel: ProfessionalDashboardViewModel)`.

- [ ] **Step 1: Write the implementation**

```kotlin
// android/app/src/main/kotlin/com/givemeurhand/android/ui/professional/ProfessionalDashboardScreen.kt
package com.givemeurhand.android.ui.professional

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.givemeurhand.android.data.CaseResponse

@Composable
fun ProfessionalDashboardScreen(viewModel: ProfessionalDashboardViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Tus casos asignados", style = MaterialTheme.typography.headlineSmall)

        LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
            items(uiState.cases) { case -> CaseCard(case, onClose = { viewModel.closeCase(case.id) }) }
        }
    }
}

@Composable
private fun CaseCard(case: CaseResponse, onClose: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(case.reasonSnippet, style = MaterialTheme.typography.bodyLarge)
            Text("Asignado: ${case.assignedAt}", style = MaterialTheme.typography.labelMedium)
            Text("Estado: ${case.status}", style = MaterialTheme.typography.labelMedium)
            if (case.status == "active") {
                Button(onClick = onClose, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Marcar como atendido")
                }
            }
        }
    }
}
```

Wire it into `MainActivity`'s `"professionalDashboard"` route (add imports for `ProfessionalDashboardScreen`, `ProfessionalDashboardViewModel`):

```kotlin
                        composable("professionalDashboard") {
                            val app = LocalContext.current.applicationContext as GiveMeUrHandApp
                            val viewModel: ProfessionalDashboardViewModel = viewModel(factory = viewModelFactory {
                                initializer { ProfessionalDashboardViewModel(app.professionalApiClient) }
                            })
                            ProfessionalDashboardScreen(viewModel)
                        }
```

- [ ] **Step 2: Manual verification**

```bash
cd android
./gradlew :app:assembleDebug
```

Log in as a seeded professional, confirm the case list loads, trigger a `human_help`/`crisis_risk` chat message from a different device/session to create a new assignment for that professional, pull-to-refresh isn't implemented so re-enter the screen (or add a temporary refresh button while testing) to see it appear, then tap "Marcar como atendido" and confirm it disappears from the active list on the next load.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/com/givemeurhand/android/ui/professional/ProfessionalDashboardScreen.kt android/app/src/main/kotlin/com/givemeurhand/android/MainActivity.kt
git commit -m "feat(android): add professional dashboard screen"
```

---

### Task 9: Final navigation polish + full end-to-end manual verification

**Files:**
- Modify: `android/app/src/main/kotlin/com/givemeurhand/android/ui/professional/ProfessionalDashboardScreen.kt` (add a manual refresh action, since there is no pull-to-refresh in this MVP)

**Interfaces:**
- Consumes: everything from Tasks 1–8.
- Produces: no new public interface — this task closes the loop on Task 8's "no way to refresh" manual-testing gap and does a full walkthrough.

- [ ] **Step 1: Add a refresh button to the dashboard**

In `ProfessionalDashboardScreen.kt`, add `Arrangement` and `TextButton` to the existing imports:

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.TextButton
```

Then replace the `Column`'s first `Text(...)` call with a title row that includes a refresh action:

```kotlin
// android/app/src/main/kotlin/com/givemeurhand/android/ui/professional/ProfessionalDashboardScreen.kt
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Tus casos asignados", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { viewModel.refresh() }) {
                Text("Actualizar")
            }
        }
```

- [ ] **Step 2: Full end-to-end manual verification**

With the backend (all three backend plans implemented, running, and pointed at a populated Atlas cluster) reachable from the device/emulator:

1. Launch the app → Lobby renders with the calm theme and the correct tile states.
2. Tap "Chat de apoyo psicológico" → ask something covered by the PFA documents (e.g. "cómo controlo un ataque de pánico") → get a grounded Spanish answer.
3. Ask something unrelated (e.g. "cuál es la capital de Francia") → get the fixed out-of-scope message.
4. Ask for human help (e.g. "quiero hablar con una persona") → get a message with a real professional's phone number (not just the fallback), confirmed by checking the number matches one of your seeded professionals.
5. Go back to the Lobby, tap "Acceso profesionales", log in with a seeded account, see the case created in step 4 in the list, tap "Marcar como atendido", tap "Actualizar" and confirm it now shows as closed (or drops off the active view per the sort order implemented in Task 4 of the professional coordination plan).

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/com/givemeurhand/android/ui/professional/ProfessionalDashboardScreen.kt
git commit -m "feat(android): add dashboard refresh action; complete MVP walkthrough"
```
