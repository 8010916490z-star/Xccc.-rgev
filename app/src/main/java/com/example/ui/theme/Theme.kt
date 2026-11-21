package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = BillZoPrimaryNight,
    onPrimary = Color(0xFF062921),
    secondary = BillZoAccent,
    background = BillZoBgDark,
    surface = BillZoSurfaceDark,
    onBackground = Color(0xFFE7EBEE),
    onSurface = Color(0xFFE7EBEE),
  )

private val LightColorScheme =
  lightColorScheme(
    primary = BillZoPrimary,
    onPrimary = Color.White,
    primaryContainer = BillZoPrimaryContainer,
    secondary = BillZoAccent,
    background = BillZoBgLight,
    surface = BillZoSurfaceLight,
    onBackground = Color(0xFF141820),
    onSurface = Color(0xFF141820),
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
