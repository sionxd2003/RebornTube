package com.example.reborntube

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun AmbientInterfaceBackground(vm: MainViewModel, modifier: Modifier = Modifier) {
    if (!vm.ambientMode) return

    val thumbnail = vm.current?.thumbnails
        ?.maxByOrNull { it.width.toLong() * it.height.toLong() }?.url
        ?: vm.shortInfo?.thumbnails?.maxByOrNull { it.width.toLong() * it.height.toLong() }?.url
        ?: vm.offline?.thumb
    if (thumbnail.isNullOrBlank()) return

    val background = androidx.compose.material3.MaterialTheme.colorScheme.background
    Box(modifier) {
        AsyncImage(
            model = thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.4f,
            modifier = Modifier.fillMaxSize().blur(72.dp)
        )
        Box(
            Modifier.fillMaxSize().background(background.copy(alpha = 0.62f))
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        background.copy(alpha = 0.16f),
                        Color.Transparent,
                        background.copy(alpha = 0.22f)
                    )
                )
            )
        )
    }
}
