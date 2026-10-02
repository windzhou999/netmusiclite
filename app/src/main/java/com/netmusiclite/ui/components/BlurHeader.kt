package com.netmusiclite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.Bg
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import com.netmusiclite.ui.nav.NavMotionKind

/**
 * 大图头部：照片裁满 + 高斯模糊 + 从透明渐变到主题底色。
 * 用于艺人页/歌单/专辑（Build.VERSION < S 时退化为暗化蒙层）。
 */
@Composable
fun BlurHeader(
    imageUrl: String?,
    title: String,
    subtitle: String? = null,
    height: Dp = 150.dp,
    avatarUrl: String? = null,
    avatarSize: Dp = 56.dp,
    actions: (@Composable () -> Unit)? = null,
) {
    Box(Modifier.fillMaxWidth().height(height)) {
        val canBlur = android.os.Build.VERSION.SDK_INT >= 31
        val fadeOut = com.netmusiclite.data.BackgroundStore.ready
        // 图像层（offscreen）：启用背景图时用 DstIn 沿高度擦除图像 alpha = 真渐隐
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (fadeOut) {
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Black,
                                0.5f to Color.Black.copy(alpha = 0.75f),
                                
                                1f to Color.Transparent,
                            ),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                        )
                    }
                }
        ) {
            if (imageUrl != null) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                AsyncImage(
                    // 限定解码尺寸：头图最大 ~466px 宽，避免整张原封面进内存造成卡顿
                    model = coil.request.ImageRequest.Builder(ctx).data(imageUrl).size(520).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .let { if (canBlur) it.blur(26.dp) else it },
                )
            } else {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF23233A), SongAmbient.color))))
            }
        }
        if (!fadeOut) {
            // 纯色模式：保留原有压暗渐变，末端融入当前歌曲环境色（无背景图全局取色模式）
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.25f),
                            0.55f to Color(0x66000000),
                            1f to SongAmbient.color,
                        )
                    )
            )
        } else {
            // ★ 背景图模式（2026-09-30 修「艺人页『N 首热度歌曲 · M 张专辑』这行字看不清」）：
            //   旧版这条分支只沿高度擦除图像 alpha，没有任何压暗层 —— 头图整块是高亮原图，
            //   白色小字（尤其 10sp 副标题）落在明亮照片上直接糊掉。
            //   ★ 钟形压暗（同日二改，修「渐变头图有割裂感」）：压暗只压在**文字所在的中段**
            //   最暗（0.62 处 ~30%），向下到 1f 重新回到全透明 —— 头图底边与下方页面同亮度，
            //   不再出现一道深色横带/硬边；上端从 0.35 才开始渐入，也不会在图片上部留痕。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to Color.Transparent,
                            0.35f to Color.Transparent,
                            0.62f to Color.Black.copy(alpha = 0.30f),
                            0.86f to Color.Black.copy(alpha = 0.12f),
                            1.00f to Color.Transparent,
                        )
                    )
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp).padding(bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (avatarUrl != null) {
                    CoverImage(avatarUrl, avatarSize, Modifier, CircleShape)
                    Spacer(Modifier.width(10.dp))
                }
                Column(horizontalAlignment = if (avatarUrl != null) Alignment.Start else Alignment.CenterHorizontally) {
                    Text(
                        title,
                        fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                        textAlign = TextAlign.Center, maxLines = 1,
                        // 轻投影：任何底图上都不糊（1dp 偏移 + 6px 模糊，肉眼只感到字更实）
                        style = HEADER_TEXT_SHADOW,
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle, fontSize = 10.sp,
                            // 副标题从 70% 白提到 92% 白 + 同款投影：小字号抗锯齿后不再发灰
                            color = TextPrimary.copy(alpha = 0.92f), maxLines = 1,
                            style = HEADER_TEXT_SHADOW,
                        )
                    }
                }
            }
            if (actions != null) {
                Spacer(Modifier.height(8.dp))
                actions()
            }
        }
    }
}

private val BgElevatedBrush = Brush.verticalGradient(listOf(Color(0xFF23233A), Bg))

/** 头图文字投影（静态复用，避免每帧新建 TextStyle）：
 *  1dp 下偏移 + 6px 模糊 + 55% 黑，底图再亮也能压出字缘，视觉上只觉字更实、不显阴影 */
private val HEADER_TEXT_SHADOW = TextStyle(
    shadow = Shadow(
        color = Color.Black.copy(alpha = 0.55f),
        offset = Offset(0f, 1f),
        blurRadius = 6f,
    )
)

/** 艺人页 SVG 圆形操作按钮（无文字）：关注 / 加入一起听 */
@Composable
fun CircleSvgButton(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 21.dp,
    container: Color = Color.White.copy(alpha = 0.14f),
    tint: Color = TextPrimary,
    contentDesc: String = "",
    navMotionKind: NavMotionKind? = null,
    onClick: () -> Unit,
) {
    Pressable(onClick = onClick, modifier = modifier, navMotionKind = navMotionKind) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDesc, tint = tint, modifier = Modifier.size(iconSize))
        }
    }
}

/** 已关注态的实心红底 */
@Composable
fun CircleSvgButtonFilled(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = com.netmusiclite.ui.theme.OnAccent,
    navMotionKind: NavMotionKind? = null,
    onClick: () -> Unit,
) = CircleSvgButton(icon = icon, modifier = modifier, size = size,
    container = Accent, tint = tint, navMotionKind = navMotionKind, onClick = onClick)
