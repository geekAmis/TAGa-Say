package com.tagasay.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tagasay.app.ui.components.Avatar
import com.tagasay.app.ui.components.formatDuration
import com.tagasay.app.ui.theme.TagaGreen
import com.tagasay.app.ui.theme.TagaRed
import kotlinx.coroutines.delay

@Composable
fun CallScreen(
    displayName: String,
    avatarColor: Long,
    onEnd: () -> Unit,
) {
    var connected by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    var muted by remember { mutableStateOf(false) }
    var video by remember { mutableStateOf(false) }
    var speaker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(1800)
        connected = true
        while (true) {
            delay(1000)
            seconds++
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF181233), Color(0xFF0B0C11)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(30.dp))
            Surface(
                color = TagaGreen.copy(alpha = 0.14f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = TagaGreen,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "E2EE · DTLS-SRTP + FrameCryptor",
                        style = MaterialTheme.typography.bodySmall,
                        color = TagaGreen,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Avatar(
                name = displayName,
                color = avatarColor,
                size = 132.dp,
                online = connected,
            )
            Spacer(Modifier.height(22.dp))
            Text(
                displayName,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (connected) formatDuration(seconds) else "Соединение…",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.65f),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Ключ звонка выведен из сессии Double Ratchet",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.45f),
            )

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CallControl(
                    icon = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    label = if (muted) "Вкл. микрофон" else "Микрофон",
                    active = muted,
                    onClick = { muted = !muted },
                )
                CallControl(
                    icon = if (speaker) Icons.Filled.VolumeUp else Icons.Filled.VolumeUp,
                    label = "Динамик",
                    active = speaker,
                    onClick = { speaker = !speaker },
                )
                CallControl(
                    icon = if (video) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                    label = "Видео",
                    active = video,
                    onClick = { video = !video },
                )
            }

            Spacer(Modifier.height(30.dp))

            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(TagaRed),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.IconButton(onClick = onEnd) {
                    Icon(
                        Icons.Filled.CallEnd,
                        contentDescription = "Завершить звонок",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Завершить",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f),
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun CallControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(if (active) Color.White else Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.IconButton(onClick = onClick) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = if (active) Color(0xFF181233) else Color.White,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f))
    }
}
