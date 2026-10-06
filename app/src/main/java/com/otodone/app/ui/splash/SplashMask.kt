package com.otodone.app.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.otodone.app.R
import kotlinx.coroutines.delay

/**
 * 启动遮罩：复刻 wgpro 的闪屏动画 —— logo 由 alpha 0 / scale 0.88 在 300ms 内
 * 以 fast_out_slow_in 过渡到 alpha 1 / scale 1，短暂停留后回调 [onFinished] 淡出。
 */
@Composable
fun SplashMask(
    modifier: Modifier = Modifier,
    holdMillis: Long = 420L,
    onFinished: () -> Unit,
) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        )
        delay(holdMillis)
        onFinished()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.splash_logo),
            contentDescription = null,
            modifier = Modifier
                .size(160.dp)
                .scale(0.88f + 0.12f * progress.value)
                .alpha(progress.value),
        )
    }
}
