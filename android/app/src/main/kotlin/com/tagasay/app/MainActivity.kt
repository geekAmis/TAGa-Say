package com.tagasay.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tagasay.app.ui.ComplianceGate
import com.tagasay.app.ui.MainViewModel
import com.tagasay.app.ui.TAGaSayRoot
import com.tagasay.app.ui.theme.TAGaSayTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val deepLink = MutableStateFlow<String?>(null)
    private val openChatId = MutableStateFlow<String?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLink.value = parseDeepLink(intent)
        openChatId.value = intent?.getStringExtra(EXTRA_OPEN_CHAT_ID)
        requestNotificationPermissionIfNeeded()

        setContent {
            val viewModel: MainViewModel = viewModel()
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val link by deepLink.collectAsStateWithLifecycle()
            val chatToOpen by openChatId.collectAsStateWithLifecycle()

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
                            openChatId = chatToOpen,
                            onOpenChatConsumed = { openChatId.value = null },
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
        openChatId.value = intent.getStringExtra(EXTRA_OPEN_CHAT_ID)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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

    companion object {
        const val EXTRA_OPEN_CHAT_ID = "com.tagasay.app.OPEN_CHAT_ID"
    }
}
