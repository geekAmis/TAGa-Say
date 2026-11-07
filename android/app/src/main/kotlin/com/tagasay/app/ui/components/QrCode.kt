package com.tagasay.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun QrCode(
    data: String,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(data) {
        runCatching {
            val matrix = QRCodeWriter().encode(
                data,
                BarcodeFormat.QR_CODE,
                QR_SIZE,
                QR_SIZE,
                mapOf(EncodeHintType.MARGIN to 1),
            )
            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    pixels[y * width + x] = if (matrix.get(x, y)) {
                        0xFF000000.toInt()
                    } else {
                        0xFFFFFFFF.toInt()
                    }
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        }.getOrNull()
    } ?: return

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "QR-код для Google Authenticator",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp)),
    )
}

private const val QR_SIZE = 512
