package com.tagasay.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tagasay.app.ui.theme.TagaBlue
import com.tagasay.app.ui.theme.TagaGreen
import com.tagasay.app.ui.theme.TagaPurple
import com.tagasay.app.ui.theme.TagaPurpleDark

@Composable
fun OnboardingScreen(onComplete: (displayName: String, username: String, password: String, totpCode: String) -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    BackHandler {
        if (step == 1) step = 0
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(TagaPurple.copy(alpha = 0.35f), Color.Transparent),
                    ),
                ),
        )

        if (step == 0) {
            WelcomeStep(
                onContinue = { step = 1 },
            )
        } else {
            ProfileStep(
                onBack = { step = 0 },
                onComplete = onComplete,
            )
        }
    }
}

@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(54.dp))
        Logo()
        Spacer(Modifier.height(22.dp))
        Text(
            text = "TAGa-Say",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Приватный мессенджер",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = "Переписка защищена оконечным шифрованием X3DH + Double Ratchet " +
                "с пост-квантовым PQXDH. Сервер видит только шифротекст.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(34.dp))

        Feature(Icons.Filled.Lock, "Сквозное шифрование", "libsignal: X3DH + Double Ratchet + PQXDH")
        Feature(Icons.Filled.Shield, "Антивирус", "Проверка файлов до отправки и после получения")
        Feature(Icons.Filled.Call, "Звонки", "Аудио и видео с дополнительным E2EE")
        Feature(Icons.Filled.VerifiedUser, "Без телефона", "Только @username, пароль и device hash")

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = TagaPurple),
        ) {
            Text("Создать аккаунт", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onContinue) {
            Text(
                "У меня уже есть аккаунт",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Продолжая, вы принимаете политику конфиденциальности.\n" +
                "Ключи хранятся только на этом устройстве.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Logo() {
    Box(
        modifier = Modifier
            .size(104.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(listOf(TagaPurple, TagaBlue)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.ChatBubble,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(48.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .size(26.dp)
                .clip(CircleShape)
                .background(TagaGreen),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProfileStep(
    onBack: () -> Unit,
    onComplete: (String, String, String, String) -> Unit,
) {
    var displayName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var totpCode by remember { mutableStateOf("") }

    val usernameValid = username.matches(Regex("[A-Za-z0-9_]{3,32}"))
    val passwordValid = password.length >= 8
    val passwordsMatch = password == confirmPassword
    val canContinue = displayName.isNotBlank() && usernameValid && passwordValid && passwordsMatch

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
        }
        Spacer(Modifier.height(14.dp))
        Text("Создайте профиль", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Имя и @username видны другим пользователям. Пароль защищает аккаунт и " +
                "не хранится на сервере в открытом виде.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(30.dp))

        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it.take(40) },
            label = { Text("Отображаемое имя") },
            placeholder = { Text("Например, Лилиана") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { value ->
                username = value.trimStart('@').filter { it.isLetterOrDigit() || it == '_' }.take(32)
            },
            label = { Text("@username") },
            placeholder = { Text("lilanga") },
            singleLine = true,
            supportingText = {
                Text(
                    if (username.isEmpty() || usernameValid) {
                        "Ссылка: taga://open/@${username.ifBlank { "username" }}"
                    } else {
                        "3–32 символа: латиница, цифры, подчёркивание"
                    },
                )
            },
            isError = username.isNotEmpty() && !usernameValid,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it.take(128) },
            label = { Text("Пароль") },
            placeholder = { Text("Минимум 8 символов") },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            isError = password.isNotEmpty() && !passwordValid,
            supportingText = {
                if (password.isNotEmpty() && !passwordValid) {
                    Text("Минимум 8 символов")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it.take(128) },
            label = { Text("Повторите пароль") },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            isError = confirmPassword.isNotEmpty() && !passwordsMatch,
            supportingText = {
                if (confirmPassword.isNotEmpty() && !passwordsMatch) {
                    Text("Пароли не совпадают")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = totpCode,
            onValueChange = { totpCode = it.filter(Char::isDigit).take(6) },
            label = { Text("Код 2FA (если включён)") },
            placeholder = { Text("000000") },
            singleLine = true,
            supportingText = { Text("Для входа на новом устройстве") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        SurfaceNote(
            "Телефон не требуется. Для входа используются @username, пароль и " +
                "уникальный скрытый хэш этого устройства.",
        )
        Spacer(Modifier.height(30.dp))

        Button(
            onClick = { onComplete(displayName.trim(), username, password, totpCode) },
            enabled = canContinue,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = TagaPurple,
                disabledContainerColor = TagaPurpleDark.copy(alpha = 0.4f),
            ),
        ) {
            Text("Начать общение", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Пароль преобразуется в Argon2id-verifier. Приватные ключи не покидают " +
                "Android Keystore.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SurfaceNote(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
