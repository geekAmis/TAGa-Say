package com.tagasay.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tagasay.app.ui.ComplianceGate
import com.tagasay.app.ui.MainViewModel
import com.tagasay.app.ui.TAGaSayRoot
import com.tagasay.app.ui.theme.TAGaSayTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val deepLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLink.value = parseDeepLink(intent)

        setContent {
            val viewModel: MainViewModel = viewModel()
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val link by deepLink.collectAsStateWithLifecycle()

            LaunchedEffect(state.settings.screenSecurity) {
                if (state.settings.screenSecurity) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            TAGaSayTheme(darkTheme = state.settings.darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ComplianceGate {
                        TAGaSayRoot(
                            viewModel = viewModel,
                            state = state,
                            deepLink = link,
                            onDeepLinkConsumed = { deepLink.value = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink.value = parseDeepLink(intent)
    }

    private fun parseDeepLink(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND) return null
        val data: Uri = intent?.data ?: return null
        val candidate = when {
            data.scheme == "taga" && data.host == "open" ->
                data.pathSegments.lastOrNull()
            data.scheme == "https" || data.scheme == "http" ->
                data.pathSegments.lastOrNull()
            else -> null
        } ?: return null
        return candidate.trim().trimStart('@').takeIf { it.matches(Regex("[A-Za-z0-9_]{3,32}")) }
    }
}
