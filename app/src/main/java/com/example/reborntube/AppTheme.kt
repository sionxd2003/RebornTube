package com.example.reborntube

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private fun gruvboxScheme(dark: Boolean): ColorScheme =
    if (dark) darkColorScheme(
        primary = Color(0xFFFE8019),
        onPrimary = Color(0xFF282828),
        primaryContainer = Color(0xFF504945),
        onPrimaryContainer = Color(0xFFEBDBB2),
        secondary = Color(0xFFB8BB26),
        onSecondary = Color(0xFF282828),
        secondaryContainer = Color(0xFF3C3836),
        onSecondaryContainer = Color(0xFFEBDBB2),
        tertiary = Color(0xFF83A598),
        onTertiary = Color(0xFF282828),
        background = Color(0xFF282828),
        onBackground = Color(0xFFEBDBB2),
        surface = Color(0xFF282828),
        onSurface = Color(0xFFEBDBB2),
        surfaceVariant = Color(0xFF3C3836),
        onSurfaceVariant = Color(0xFFD5C4A1),
        surfaceContainer = Color(0xFF32302F),
        outline = Color(0xFF665C54),
        outlineVariant = Color(0xFF504945),
        error = Color(0xFFFB4934),
        onError = Color(0xFF282828),
        errorContainer = Color(0xFF3C3836),
        onErrorContainer = Color(0xFFFB4934),
        inverseSurface = Color(0xFFEBDBB2),
        inverseOnSurface = Color(0xFF282828),
        inversePrimary = Color(0xFFAF3A03)
    ) else lightColorScheme(
        primary = Color(0xFFAF3A03),
        onPrimary = Color(0xFFF9F5D7),
        primaryContainer = Color(0xFFD5C4A1),
        onPrimaryContainer = Color(0xFF3C3836),
        secondary = Color(0xFF79740E),
        onSecondary = Color(0xFFF9F5D7),
        secondaryContainer = Color(0xFFEBDBB2),
        onSecondaryContainer = Color(0xFF3C3836),
        tertiary = Color(0xFF076678),
        onTertiary = Color(0xFFF9F5D7),
        background = Color(0xFFFBF1C7),
        onBackground = Color(0xFF3C3836),
        surface = Color(0xFFFBF1C7),
        onSurface = Color(0xFF3C3836),
        surfaceVariant = Color(0xFFEBDBB2),
        onSurfaceVariant = Color(0xFF504945),
        surfaceContainer = Color(0xFFF2E5BC),
        outline = Color(0xFFA89984),
        outlineVariant = Color(0xFFD5C4A1),
        error = Color(0xFF9D0006),
        onError = Color(0xFFF9F5D7),
        errorContainer = Color(0xFFEBDBB2),
        onErrorContainer = Color(0xFF9D0006),
        inverseSurface = Color(0xFF3C3836),
        inverseOnSurface = Color(0xFFFBF1C7),
        inversePrimary = Color(0xFFFE8019)
    )

fun buildColorScheme(accent: String, theme: String, systemDark: Boolean, context: Context): ColorScheme {
    if (theme == "gruvbox_light") return gruvboxScheme(dark = false)
    if (theme == "gruvbox_dark") return gruvboxScheme(dark = true)

    val dark = when (theme) {
        "light" -> false
        "system" -> systemDark
        else -> true
    }
    var scheme: ColorScheme = when {
        accent == "dynamic" && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> {
            if (dark) darkColorScheme(
                primary = Color.White,
                onPrimary = Color.Black,
                primaryContainer = Color(0xFF303030),
                onPrimaryContainer = Color.White,
                secondary = Color(0xFFE0E0E0),
                onSecondary = Color.Black,
                secondaryContainer = Color(0xFF303030),
                onSecondaryContainer = Color.White,
                tertiary = Color.White,
                onTertiary = Color.Black,
                background = Color(0xFF121212),
                onBackground = Color.White,
                surface = Color(0xFF121212),
                onSurface = Color.White,
                surfaceVariant = Color(0xFF303030),
                onSurfaceVariant = Color(0xFFE0E0E0),
                surfaceContainer = Color(0xFF1E1E1E),
                outline = Color(0xFF8A8A8A),
                outlineVariant = Color(0xFF494949),
                inverseSurface = Color.White,
                inverseOnSurface = Color.Black,
                inversePrimary = Color.Black
            ) else lightColorScheme(
                primary = Color.Black,
                onPrimary = Color.White,
                primaryContainer = Color(0xFFE5E5E5),
                onPrimaryContainer = Color.Black,
                secondary = Color(0xFF222222),
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFE5E5E5),
                onSecondaryContainer = Color.Black,
                tertiary = Color.Black,
                onTertiary = Color.White,
                background = Color.White,
                onBackground = Color.Black,
                surface = Color.White,
                onSurface = Color.Black,
                surfaceVariant = Color(0xFFF0F0F0),
                onSurfaceVariant = Color(0xFF252525),
                surfaceContainer = Color(0xFFF7F7F7),
                outline = Color(0xFF707070),
                outlineVariant = Color(0xFFD0D0D0),
                inverseSurface = Color(0xFF222222),
                inverseOnSurface = Color.White,
                inversePrimary = Color.White
            )
        }
    }
    if (dark && theme == "black") {
        scheme = scheme.copy(
            primary = Color.White,
            onPrimary = Color.Black,
            primaryContainer = Color(0xFF303030),
            onPrimaryContainer = Color.White,
            secondary = Color.White,
            onSecondary = Color.Black,
            secondaryContainer = Color(0xFF303030),
            onSecondaryContainer = Color.White,
            tertiary = Color.White,
            onTertiary = Color.Black,
            background = Color.Black,
            onBackground = Color.White,
            surface = Color.Black,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF1C1C1E),
            onSurfaceVariant = Color(0xFFE5E5E5),
            surfaceContainer = Color(0xFF0A0A0A),
            outline = Color(0xFF858585),
            outlineVariant = Color(0xFF3D3D3D),
            inverseSurface = Color.White,
            inverseOnSurface = Color.Black,
            inversePrimary = Color.Black
        )
    }
    return scheme
}
