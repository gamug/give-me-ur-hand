// android/app/src/main/kotlin/com/givemeurhand/android/ui/professional/ProfessionalDashboardScreen.kt
package com.givemeurhand.android.ui.professional

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.givemeurhand.android.data.CaseResponse

@Composable
fun ProfessionalDashboardScreen(viewModel: ProfessionalDashboardViewModel, onLogout: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Tus casos asignados", style = MaterialTheme.typography.headlineSmall)
            Row {
                TextButton(onClick = { viewModel.refresh() }) {
                    Text("Actualizar")
                }
                TextButton(onClick = onLogout) {
                    Text("Cerrar sesión")
                }
            }
        }

        uiState.errorMessage?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
        }

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
