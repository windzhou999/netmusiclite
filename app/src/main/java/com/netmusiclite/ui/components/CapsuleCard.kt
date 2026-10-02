package com.netmusiclite.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netmusiclite.ui.theme.SurfaceGlass
import com.netmusiclite.ui.theme.SurfaceStrong
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import com.netmusiclite.ui.theme.T
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.TransitionCoordinator

/** 按压缩放（按下慢速下沉 + 松开带回弹，参数见 [PressMotion]）。
 *  流畅度（W5）：graphicsLayer block 延迟读取动画值，按压动画期间只更新图层属性，零重组。 */
@Composable
fun Modifier.pressScale(scaleTo: Float = PressMotion.SCALE): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(
        targetValue = if (pressed) scaleTo else 1f,
        animationSpec = PressMotion.spec(pressed),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = s
        scaleY = s
    }
}

/** 可按压缩放的点击容器 */
@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    scaleTo: Float = PressMotion.SCALE,
    navMotionKind: NavMotionKind? = null,
    content: @Composable () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val coordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(
        targetValue = if (pressed) scaleTo else 1f,
        animationSpec = PressMotion.spec(pressed),
        label = "press",
    )
    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates.value = it }
            .graphicsLayer {
                scaleX = s
                scaleY = s
            }
            .clickable(interactionSource = src, indication = null) {
                if (navMotionKind == null) onClick()
                else TransitionCoordinator.withSource(navMotionKind, coordinates.value, onClick)
            },
    ) { content() }
}

/**
 * 主页堆叠胶囊卡片：左侧圆形图标底 + 标题(+可选副标题)
 */
@Composable
fun CapsuleCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconTint: Color = TextPrimary,
    iconBg: Color = SurfaceStrong,
    navMotionKind: NavMotionKind? = null,
    onClick: () -> Unit = {},
) {
    val shape = RoundedCornerShape(50)
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val coordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
    val s by animateFloatAsState(
        targetValue = if (pressed) PressMotion.SCALE else 1f,
        animationSpec = PressMotion.spec(pressed),
        label = "capsule",
    )
    Row(
        modifier = modifier
            .onGloballyPositioned { coordinates.value = it }
            .fillMaxWidth()
            .height(66.dp)
            .graphicsLayer {
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .background(SurfaceGlass)
            .glassEdgeHighlight(shape)
            .clickable(interactionSource = src, indication = null) {
                if (navMotionKind == null) onClick()
                else TransitionCoordinator.withSource(navMotionKind, coordinates.value, onClick)
            }
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = title, tint = iconTint, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column {
            Text(title, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, fontSize = 10.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
