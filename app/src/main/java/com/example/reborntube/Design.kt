package com.example.reborntube

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** Marca de RebornTube compartida con el icono de la aplicación. */
@Composable
fun AppMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_launcher_art),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(30.dp)
    )
}

/** "Reborn" ligero + "Tube" en negrita con el color de acento. */
@Composable
fun Wordmark() {
    Text(
        buildAnnotatedString {
            append("Reborn")
            withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)) {
                append("Tube")
            }
        },
        style = MaterialTheme.typography.titleLarge,
        fontSize = 22.sp,
        letterSpacing = (-0.6).sp
    )
}

/** Cápsula de filtro que se desliza en la fila de arriba del inicio. */
@Composable
fun PillChip(label: String, icon: ImageVector? = null, selected: Boolean = false, dot: Color? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val content = if (selected) scheme.surface else scheme.onSurface
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) scheme.onSurface else scheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot != null) {
            Box(Modifier.size(14.dp).background(dot, CircleShape))
            Spacer(Modifier.width(8.dp))
        }
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            if (label.isNotEmpty()) Spacer(Modifier.width(6.dp))
        }
        if (label.isNotEmpty()) Text(label, color = content, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Círculo con la inicial del canal (no hay foto de canal en las listas); el color sale del nombre. */
@Composable
fun ChannelDot(name: String, size: androidx.compose.ui.unit.Dp = 38.dp) {
    val hue = ((name.hashCode() % 360) + 360) % 360
    Box(
        Modifier.size(size).clip(CircleShape).background(Color.hsv(hue.toFloat(), 0.45f, 0.55f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.trim().take(1).uppercase(), color = Color.White,
            style = MaterialTheme.typography.titleSmall
        )
    }
}

/** Foto del canal; si todavía no se conoce, el círculo con su inicial. */
@Composable
fun ChannelAvatar(name: String, url: String?, size: androidx.compose.ui.unit.Dp = 38.dp) {
    if (url.isNullOrEmpty()) {
        ChannelDot(name, size)
    } else {
        AsyncImage(
            model = url, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

/** Tarjeta de video grande (inicio y resultados). Es la misma que usan todas las listas de la app. */
@Composable
fun FeedCard(vm: MainViewModel, card: VideoCard) = VideoItem(vm, card, compact = true)

/** Formas redondeadas para toda la app. */
val AppShapes = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

/** Título de apartado con la barrita de color. */
@Composable
fun SectionHeader(text: String) {
    Row(
        Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(width = 4.dp, height = 18.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** Opción que se elige tocando toda la tarjeta (sustituye a los botones de radio). */
@Composable
fun OptionCard(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) scheme.primaryContainer else scheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurface
        )
        if (selected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = scheme.onPrimaryContainer)
        }
    }
}

/** Contenedor redondeado para filas de ajustes. */
@Composable
fun SettingCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        content = content
    )
}

/** Aviso o estado vacío dentro de una tarjeta. */
@Composable
fun NoteBox(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(12.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    )
}
