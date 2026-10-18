package com.dfdx047.odinhub.ui.theme

import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.Composable
import com.dfdx047.odinhub.R

val ConsoleFont = FontFamily(Font(R.font.outfit_regular))

data class ConsoleTheme(
    val name: String,
    val background: Color,
    val surface: Color,
    val primary: Color,
    val text: Color,
    val isDynamic: Boolean = false,
    val fontFamily: FontFamily = ConsoleFont,
    /**
     * Aparência "vidro" (ao estilo do Liquid Glass da Apple), aplicada POR CIMA de qualquer tema:
     * superfícies translúcidas com reflexo e contorno de luz. As cores de cada tema mantêm-se.
     */
    val glassMode: Boolean = false,
)

val DynamicTheme = ConsoleTheme("Dynamic", Color.Black, Color.DarkGray, Color.White, Color.White, isDynamic = true)
val OdinTheme = ConsoleTheme("Odin OS", Color(0xFF0F0F13), Color(0xFF1C1C24), Color(0xFFE5002B), Color(0xFFF3F4F6))
val LightTheme = ConsoleTheme("Light OS", Color(0xFFF0F3F8), Color(0xFFFFFFFF), Color(0xFF0055FF), Color(0xFF1A1A24)) // Novo Tema Claro
val CyberpunkTheme = ConsoleTheme("Cyberpunk", Color(0xFF09090B), Color(0xFF13131A), Color(0xFFFCEE0A), Color(0xFF00FFD1))
val SnesTheme = ConsoleTheme("SNES", Color(0xFFE5E6EA), Color(0xFFD0D3D9), Color(0xFF5A4D9C), Color(0xFF333333))
val NesTheme = ConsoleTheme("NES", Color(0xFF3C3C3C), Color(0xFF000000), Color(0xFFE60012), Color(0xFFFFFFFF))
val XboxTheme = ConsoleTheme("Xbox", Color(0xFF101010), Color(0xFF1A1A1A), Color(0xFF107C10), Color(0xFFFFFFFF))
val SteamTheme = ConsoleTheme("Steam OS", Color(0xFF171A21), Color(0xFF2A475E), Color(0xFF66C0F4), Color(0xFFC7D5E0))
val PlayStationTheme = ConsoleTheme("PlayStation", Color(0xFF000033), Color(0xFF003399), Color(0xFF0066CC), Color(0xFFFFFFFF))

val AvailableThemes = listOf(DynamicTheme, OdinTheme, LightTheme, CyberpunkTheme, SnesTheme, NesTheme, XboxTheme, SteamTheme, PlayStationTheme)

@Composable
fun getResolvedTheme(theme: ConsoleTheme, useAmoledBlack: Boolean, useGlass: Boolean = false): ConsoleTheme {
    var resolved = theme
    if (theme.isDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dynamicColors = dynamicDarkColorScheme(LocalContext.current)
        resolved = theme.copy(background = dynamicColors.background, surface = dynamicColors.surfaceVariant, primary = dynamicColors.primary, text = dynamicColors.onBackground)
    }

    // Se o AMOLED estiver ligado, forçamos um fundo preto, cards escuros e texto branco para não quebrar temas claros
    if (useAmoledBlack) {
        resolved = resolved.copy(background = Color.Black, surface = Color(0xFF121212), text = Color(0xFFF3F4F6))
    }
    return resolved.copy(glassMode = useGlass)
}
// ------------------------------------------------------------------ contraste
// Cores derivadas do tema para garantir texto legível em TODOS os temas. Antes havia texto branco
// fixo por cima de chips claros (Light OS, SNES) e texto escuro por cima de faixas pretas
// fixas -- em alguns temas mal se conseguia ler as letras.

/** Tema claro (fundo claro, texto escuro)? */
val ConsoleTheme.isLight: Boolean get() = background.luminance() > 0.5f

/** Cor do texto por cima de [ConsoleTheme.primary] (preto em primárias claras, ex: amarelo Cyberpunk). */
val ConsoleTheme.onPrimary: Color get() = if (primary.luminance() > 0.55f) Color(0xFF111111) else Color.White

/** Véu subtil para faixas/segmentos dentro dos cartões (claro em temas claros, escuro nos escuros). */
val ConsoleTheme.scrim: Color get() = when {
    glassMode && isLight -> Color.White.copy(alpha = 0.18f)
    glassMode -> Color.Black.copy(alpha = 0.16f)
    isLight -> Color.Black.copy(alpha = 0.06f)
    else -> Color.Black.copy(alpha = 0.28f)
}

/** Fundo da parte de baixo do gradiente dos ecrãs: nunca preto em temas claros. */
val ConsoleTheme.backdropBottom: Color get() = if (isLight) background.copy(alpha = 0.94f) else Color.Black.copy(alpha = 0.85f)

/** Superfície "vidro fosco" dos cartões: mais opaca que antes, para o texto não competir com o wallpaper. */
fun ConsoleTheme.glass(enabled: Boolean = true): Color = when {
    // Vidro: bem mais translúcido, mas com véu suficiente para o texto continuar legível.
    glassMode && isLight -> Color.White.copy(alpha = if (enabled) 0.46f else 0.28f)
    glassMode -> surface.copy(alpha = if (enabled) 0.34f else 0.22f)
    else -> surface.copy(alpha = if (!enabled) 0.35f else if (isLight) 0.82f else 0.62f)
}

/** Raio dos cantos dos cartões: mais arredondado no modo vidro. */
val ConsoleTheme.cardRadius: Int get() = if (glassMode) 20 else 8

/** Reflexo no topo das superfícies de vidro (mais forte no modo vidro). */
val ConsoleTheme.glassHighlight: Color get() = Color.White.copy(alpha = if (glassMode) (if (isLight) 0.30f else 0.14f) else 0.07f)

/** Contorno de luz do vidro (de cima para baixo): claro no topo, quase nada em baixo. */
val ConsoleTheme.glassEdgeTop: Color get() = Color.White.copy(alpha = if (isLight) 0.85f else 0.38f)
val ConsoleTheme.glassEdgeBottom: Color get() = Color.White.copy(alpha = if (isLight) 0.25f else 0.06f)

/** Texto secundário com contraste suficiente (antes 0.5-0.6 de alpha, ilegível em vários temas). */
val ConsoleTheme.secondaryText: Color get() = text.copy(alpha = 0.78f)
