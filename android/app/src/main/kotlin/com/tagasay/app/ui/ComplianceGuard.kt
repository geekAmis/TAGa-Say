package com.tagasay.app.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.telephony.TelephonyManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PublicOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tagasay.app.BuildConfig
import com.tagasay.app.ui.theme.TagaBlue
import com.tagasay.app.ui.theme.TagaPurple
import kotlinx.coroutines.delay
import java.net.NetworkInterface
import java.util.Locale

/**
 * Release-ограничение: приложение работает только в России и не работает через VPN.
 *
 * Проверка клиентская; сервер дополнительно может ограничивать по geo-IP.
 * В debug-сборке отключено, чтобы можно было тестировать через adb reverse.
 */
object ComplianceGuard {

    fun vpnActive(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return true
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList().any { iface ->
                iface.isUp && (
                    iface.name.startsWith("tun", true) ||
                        iface.name.startsWith("ppp", true) ||
                        iface.name.startsWith("utun", true)
                    )
            }
        }.getOrDefault(false)
    }

    fun inRussia(context: Context): Boolean {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val iso = sequenceOf(
            runCatching { telephony?.networkCountryIso }.getOrNull(),
            runCatching { telephony?.simCountryIso }.getOrNull(),
            Locale.getDefault().country,
        ).firstOrNull { !it.isNullOrBlank() }
        return iso.equals("RU", ignoreCase = true)
    }

    fun blockReason(context: Context): BlockReason? {
        if (BuildConfig.DEBUG) return null
        if (vpnActive(context)) return BlockReason.VPN
        if (!inRussia(context)) return BlockReason.REGION
        return null
    }

    enum class BlockReason { VPN, REGION }
}

@Composable
fun ComplianceGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var reason by remember { mutableStateOf(ComplianceGuard.blockReason(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            reason = ComplianceGuard.blockReason(context)
            delay(3_000)
        }
    }

    when (val current = reason) {
        null -> content()
        else -> BlockedScreen(current)
    }
}

@Composable
private fun BlockedScreen(reason: ComplianceGuard.BlockReason) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(TagaPurple, TagaBlue))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (reason == ComplianceGuard.BlockReason.VPN) {
                    Icons.Filled.VpnKey
                } else {
                    Icons.Filled.PublicOff
                },
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            if (reason == ComplianceGuard.BlockReason.VPN) {
                "Отключите VPN"
            } else {
                "Доступно только в России"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (reason == ComplianceGuard.BlockReason.VPN) {
                "TAGa-Say не работает через VPN. Отключите туннель и перезапустите приложение."
            } else {
                "TAGa-Say доступен только на территории России. Проверьте страну сети."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://vk.ru/rf_dron")),
                )
            },
        ) { Text("Связаться с владельцем") }
    }
}
