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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tagasay.app.ui.components.Avatar
import com.tagasay.app.ui.components.SectionCard
import com.tagasay.app.ui.theme.TagaBlue
import com.tagasay.app.ui.theme.TagaGreen
import com.tagasay.app.ui.theme.TagaPurple

@Composable
fun ProfileScreen(
    displayName: String,
    username: String,
    phone: String,
    bio: String,
    avatarColor: Long,
    verified: Boolean,
    online: Boolean,
    fingerprint: String,
    phoneVisible: Boolean,
    isSelf: Boolean,
    isRegistered: Boolean,
    onBack: () -> Unit,
    onMessage: () -> Unit,
    onCall: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp)
                .background(
                    Brush.verticalGradient(listOf(TagaPurple.copy(alpha = 0.45f), Color.Transparent)),
                ),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(8.dp),
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
            }
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Avatar(
                    name = displayName.ifBlank { username },
                    color = avatarColor,
                    size = 104.dp,
                    online = online,
                    verified = verified,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        displayName.ifBlank { "Пользователь" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    if (verified) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.Verified,
                            contentDescription = "Проверен",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Text(
                    "@$username",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (online) {
                    Text(
                        "в сети",
                        style = MaterialTheme.typography.bodySmall,
                        color = TagaGreen,
                    )
                }
            }
        }

        Column(Modifier.padding(horizontal = 18.dp)) {
            if (!isRegistered) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("Пользователь не найден", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Проверьте @username или отправьте приглашение по ссылке.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onMessage,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = TagaPurple),
                    ) {
                        Icon(Icons.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Написать")
                    }
                    OutlinedButton(
                        onClick = onCall,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Позвонить")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            SectionCard {
                Column(Modifier.padding(16.dp)) {
                    Text("О себе", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        bio.ifBlank { "Информация не указана" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (phoneVisible && phone.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text("Телефон", style = MaterialTheme.typography.titleMedium)
                        Text(
                            phone,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Shield,
                            contentDescription = null,
                            tint = TagaGreen,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Сквозное шифрование включено",
                            style = MaterialTheme.typography.bodySmall,
                            color = TagaGreen,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            SectionCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.QrCode2, contentDescription = null, tint = TagaPurple)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Проверка ключа", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Сравните отпечаток при личной встрече или по видеозвонку",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            fingerprint.ifBlank { "Отпечаток недоступен" },
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "taga://open/@$username",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { clipboard.setText(AnnotatedString("taga://open/@$username")) },
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Скопировать ссылку")
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            if (isSelf) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(TagaBlue.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = null, tint = TagaBlue, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Приватная часть ключа хранится в Android Keystore и никогда " +
                                "не передаётся на сервер.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}
