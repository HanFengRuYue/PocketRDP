package com.hanfengruyue.pocketrdp.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Brand40,
    onPrimary = Brand99,
    primaryContainer = Brand90,
    onPrimaryContainer = Brand10,
    secondary = Brand60,
    onSecondary = Brand99,
    background = Brand99,
    onBackground = Neutral10,
    surface = Brand99,
    onSurface = Neutral10,
    error = Error40,
    onError = Brand99,
    errorContainer = Error90,
)

private val DarkColors = darkColorScheme(
    primary = Brand80,
    onPrimary = Brand20,
    primaryContainer = Brand30,
    onPrimaryContainer = Brand90,
    secondary = Brand70,
    onSecondary = Brand20,
    background = Neutral10,
    onBackground = Neutral90,
    surface = Neutral10,
    onSurface = Neutral90,
    error = Error80,
    onError = Brand20,
)

@Composable
fun PocketRdpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PocketRdpTypography,
        content = content,
    )
}
