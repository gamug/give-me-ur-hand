// android/app/src/main/kotlin/com/givemeurhand/android/ui/professional/ProfessionalLoginScreen.kt
package com.givemeurhand.android.ui.professional

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
