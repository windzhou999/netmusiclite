package com.ncm.watch.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** ZXing 生成二维码（圆角白底卡片内呈现，扫码友好） */
@Composable
fun QrCodeImage(content: String, size: Dp, modifier: Modifier = Modifier) {
    val bitmap = remember(content) {
        runCatching {
            val s = 66
            val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, s, s, hints)
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            for (x in 0 until s) for (y in 0 until s) {
                bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
            bmp
        }.getOrNull()
    }
    Box(
        modifier
            .size(size + 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = "二维码", modifier = Modifier.size(size))
        }
    }
}
