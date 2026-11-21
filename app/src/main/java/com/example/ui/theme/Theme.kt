package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Immutable
data class StudioSemanticColors(
    val recordingActive: Color = RecordingCrimson,
    val recordingGlow: Color = RecordingGlow,
    val offlineReady: Color = OfflineMint,
    val warning: Color = WarningAmber
)

val LocalStudioColors = staticCompositionLocalOf { StudioSemanticColors() }

private val DarkColorScheme = darkColorScheme(
    primary = CobaltPrimaryDark,
    onPrimary = OnCobaltDark,
    primaryContainer = CobaltContainerDark,
    onPrimaryContainer = OnCobaltContainerDark,
    secondary = EmeraldSecondaryDark,
    onSecondary = OnEmeraldDark,
    secondaryContainer = EmeraldContainerDark,
    onSecondaryContainer = OnEmeraldContainerDark,
    tertiary = CyanTertiaryDark,
    onTertiary = OnCyanDark,
    tertiaryContainer = CyanContainerDark,
    onTertiaryContainer = OnCyanContainerDark,
    background = ObsidianBackground,
    onBackground = OnSurfaceDark,
    surface = ObsidianSurface,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    error = RecordingCoral
)

private val LightColorScheme = lightColorScheme(
    primary = CobaltPrimaryLight,
    onPrimary = OnCobaltLight,
    primaryContainer = CobaltContainerLight,
    onPrimaryContainer = OnCobaltContainerLight,
    secondary = EmeraldSecondaryLight,
    onSecondary = OnEmeraldLight,
    secondaryContainer = EmeraldContainerLight,
    onSecondaryContainer = OnEmeraldContainerLight,
    tertiary = CyanTertiaryLight,
    onTertiary = OnCyanLight,
    tertiaryContainer = CyanContainerLight,
    onTertiaryContainer = OnCyanContainerLight,
    background = AlabasterBackground,
    onBackground = OnSurfaceLight,
    surface = AlabasterSurface,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    error = RecordingCrimson
)

val SuaraLingoShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun SuaraLingoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val studioColors = StudioSemanticColors(
        recordingActive = if (darkTheme) RecordingCoral else RecordingCrimson,
        recordingGlow = RecordingGlow,
        offlineReady = if (darkTheme) EmeraldSecondaryDark else OfflineMint,
        warning = WarningAmber
    )

    CompositionLocalProvider(LocalStudioColors provides studioColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SuaraLingoTypography,
            shapes = SuaraLingoShapes,
            content = content
        )
    }
}
