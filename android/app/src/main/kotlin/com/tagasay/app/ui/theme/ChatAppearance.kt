package com.tagasay.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Оформление чата. Хранится в настройках и применяется к пузырям и фону
 * переписки, как в референсах Telegram.
 */
data class ChatPalette(
    val outgoing: Color,
    val incoming: Color,
    val accent: Color,
    val background: Brush,
)

fun chatPalette(theme: String, wallpaper: String, dark: Boolean): ChatPalette {
    val (outgoing, incoming, accent) = when (theme) {
        "green" -> Triple(Color(0xFF3E9E6F), if (dark) Color(0xFF1C2A24) else Color.White, Color(0xFF57C08A))
        "blue" -> Triple(Color(0xFF3A73C9), if (dark) Color(0xFF18222F) else Color.White, Color(0xFF5B9BEA))
        "sunset" -> Triple(Color(0xFFC05A3E), if (dark) Color(0xFF2C1F1B) else Color.White, Color(0xFFE0805F))
        "mono" -> Triple(Color(0xFF4A4A55), if (dark) Color(0xFF202027) else Color(0xFFF1F1F4), Color(0xFF8A8A99))
        else -> Triple(TagaPurple, if (dark) Color(0xFF1B1B2A) else Color.White, Color(0xFF9B8AFB))
    }

    val background = when (wallpaper) {
        "solid" -> Brush.linearGradient(
            listOf(
                if (dark) Color(0xFF101018) else Color(0xFFF6F5FB),
                if (dark) Color(0xFF101018) else Color(0xFFF6F5FB),
            ),
        )
        "aurora" -> Brush.linearGradient(
            listOf(
                if (dark) Color(0xFF152236) else Color(0xFFE8F2FF),
                if (dark) Color(0xFF2A1733) else Color(0xFFFFEDF6),
                if (dark) Color(0xFF101018) else Color(0xFFF8F6FF),
            ),
        )
        "dots" -> Brush.linearGradient(
            listOf(
                if (dark) Color(0xFF1A1A24) else Color(0xFFF1EEF9),
                if (dark) Color(0xFF20192B) else Color(0xFFEDE8FA),
            ),
        )
        else -> Brush.linearGradient(
            listOf(
                if (dark) Color(0xFF161622) else Color(0xFFF2F0FA),
                if (dark) Color(0xFF1D1730) else Color(0xFFEDEAF7),
            ),
        )
    }
    return ChatPalette(outgoing = outgoing, incoming = incoming, accent = accent, background = background)
}
