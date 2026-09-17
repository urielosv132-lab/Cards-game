package com.woe.game.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = ImperialCrimsonBright,
    onPrimary = Color.White,
    secondary = AncientGold,
    onSecondary = Obsidian,
    tertiary = AncientGoldSoft,
    background = Obsidian,
    onBackground = Parchment,
    surface = ObsidianSurface,
    onSurface = Parchment,
    surfaceVariant = InkBrown,
    onSurfaceVariant = AncientGoldSoft,
    error = HpLow
)

private val LightColorScheme = lightColorScheme(
    primary = ImperialCrimson,
    onPrimary = Color.White,
    secondary = AncientGold,
    onSecondary = InkBrown,
    tertiary = ImperialCrimsonBright,
    background = Parchment,
    onBackground = InkBrown,
    surface = ParchmentSurface,
    onSurface = InkBrown,
    surfaceVariant = AncientGoldSoft,
    onSurfaceVariant = InkBrown,
    error = HpLow
)

/**
 * Tema visual de War of Empires: carmesí imperial + oro antiguo sobre
 * obsidiana/pergamino. A propósito NO usamos "dynamic color" de Android 12+:
 * queremos que la app se vea igual en cualquier teléfono, con la identidad
 * del juego, en vez de heredar los colores del fondo de pantalla del usuario.
 */
@Composable
fun WarOfEmpiresTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
