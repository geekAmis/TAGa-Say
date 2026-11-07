package com.tagasay.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ServerInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

internal fun formatHostDate(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    return runCatching {
        Instant.parse(iso)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
    }.getOrDefault(iso)
}

internal fun formatMoney(amount: String?, currency: String?): String {
    if (amount.isNullOrBlank()) return "—"
    val cur = currency?.uppercase().orEmpty()
    return if (cur.isBlank()) amount else "$amount $cur"
}

internal fun paymentTermLabel(term: String?): String = when (term) {
    "month" -> "/мес"
    "quarter_year" -> "/квартал"
    "half_year" -> "/полгода"
    "year" -> "/год"
    "week" -> "/нед"
    "day" -> "/день"
    else -> ""
}
