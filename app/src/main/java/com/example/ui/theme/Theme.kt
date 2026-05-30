package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF00E6FF),       // Glowing Neon Cyan
    secondary = Color(0xFF0E1B1D),     // Metallic Teal
    tertiary = Color(0xFF082D33),      // Deep Cyan Accent
    background = Color(0xFF0B0C15),    // Cosmic Dark
    surface = Color(0xFF0E1B1D),       // Metallic Teal Card
    onPrimary = Color(0xFF0B0C15),
    onSecondary = Color(0xFFFFFFFF),
    onTertiary = Color(0xFF00E6FF),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF00E6FF),
    secondary = Color(0xFF0E1B1D),
    tertiary = Color(0xFF082D33),
    background = Color(0xFF0B0C15),
    surface = Color(0xFF0E1B1D),
    onPrimary = Color(0xFF0B0C15),
    onSecondary = Color(0xFFFFFFFF),
    onTertiary = Color(0xFF00E6FF),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF)
)

@Composable
fun AiProxyGatewayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            try {
                var context = view.context
                while (context is android.content.ContextWrapper) {
                    if (context is Activity) {
                        context.window.statusBarColor = Color(0xFF0B0C15).toArgb() // Fix to Cosmic Dark Status bar background
                        break
                    }
                    val nextContext = context.baseContext ?: break
                    if (nextContext == context) break
                    context = nextContext
                }
            } catch (e: Exception) {
                android.util.Log.e("AiProxyGatewayTheme", "Failed to configure status bar color", e)
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
