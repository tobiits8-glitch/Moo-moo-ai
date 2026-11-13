package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ai.configureAppCheck
import com.example.data.preferences.UserPreferences
import com.example.data.preferences.UserPreferencesRepository
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.authStateFlow
import com.example.ui.auth.signOutFromMooMoo
import com.example.ui.chat.ChatScreen
import com.example.ui.chat.ChatViewModel
import com.example.ui.theme.MooMooTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure Firebase App Check before UI, ViewModels, or Firebase operations
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        setContent {
            MooMooApp()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        configureAppCheck(this, intent)
    }
}

@Composable
private fun MooMooApp() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val preferencesRepository = remember(appContext) { UserPreferencesRepository(appContext) }
    val prefs by preferencesRepository.preferences.collectAsStateWithLifecycle(
        initialValue = UserPreferences()
    )
    val currentUser by Firebase.auth.authStateFlow().collectAsStateWithLifecycle(
        initialValue = Firebase.auth.currentUser
    )
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    MooMooTheme(themeMode = prefs.themeMode) {
        val user = currentUser
        if (user == null) {
            AuthScreen(
                onAuthSuccess = { /* authStateFlow emits the signed-in FirebaseUser */ }
            )
        } else {
            val uid = user.uid
            key(uid) {
                val chatViewModel: ChatViewModel = viewModel(
                    key = uid,
                    factory = ChatViewModel.Factory(
                        appContext = appContext,
                        currentUserId = uid,
                        userDisplayName = user.displayName.orEmpty(),
                        userEmail = user.email.orEmpty(),
                        userPhotoUrl = user.photoUrl?.toString()
                    )
                )
                ChatScreen(
                    viewModel = chatViewModel,
                    onSignOut = {
                        signOutFromMooMoo(
                            credentialManager = credentialManager,
                            scope = scope
                        )
                    }
                )
            }
        }
    }
}
