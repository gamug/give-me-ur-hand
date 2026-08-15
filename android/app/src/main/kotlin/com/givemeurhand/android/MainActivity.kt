package com.givemeurhand.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.givemeurhand.android.ui.chat.ChatScreen
import com.givemeurhand.android.ui.chat.ChatViewModel
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
                        composable("chat") {
                            val app = LocalContext.current.applicationContext as GiveMeUrHandApp
                            val viewModel: ChatViewModel = viewModel(factory = viewModelFactory {
                                initializer { ChatViewModel(app.chatApiClient, app.sessionIdProvider) }
                            })
                            ChatScreen(viewModel)
                        }
                        composable("professionalLogin") { /* wired in Task 7 */ }
                        composable("professionalDashboard") { /* wired in Task 8 */ }
                    }
                }
            }
        }
    }
}
