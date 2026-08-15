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
