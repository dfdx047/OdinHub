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

/** Grupos do seletor de temas (a ordem aqui é a ordem no ecrã). */
enum class ThemeGroup(val labelEn: String, val labelPt: String) {
    ODIN("Odin signature", "Assinatura Odin"),
    VIBRANT("Vibrant", "Vibrantes"),
    RETRO("Retro", "Retrô"),
    SYSTEM("System", "Sistema"),
    ;

    fun label(isEn: Boolean): String = if (isEn) labelEn else labelPt
}

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
    val group: ThemeGroup = ThemeGroup.VIBRANT,
    /** Nome em português (o [name] é o inglês). */
    val namePt: String = name,
    /** Segunda cor de destaque, para as amostras do seletor e gradientes. */
    val accent2: Color = primary,
) {
    fun label(isEn: Boolean): String = if (isEn) name else namePt
}

// ---- Assinatura Odin: grafite com o vermelho do logótipo, a versão clara e o roxo translúcido ----
val OdinTheme = ConsoleTheme(
    "Odin Ruby", Color(0xFF0E0E13), Color(0xFF1B1B23), Color(0xFFE5002B), Color(0xFFF3F4F6),
    group = ThemeGroup.ODIN, namePt = "Odin Rubi", accent2 = Color(0xFFFF5A3C),
)
val OdinFrostTheme = ConsoleTheme(
    "Odin Frost", Color(0xFFF1F3F7), Color(0xFFFFFFFF), Color(0xFFD1002A), Color(0xFF16161D),
    group = ThemeGroup.ODIN, namePt = "Odin Ártico", accent2 = Color(0xFF3A3A48),
)
val OdinAuroraTheme = ConsoleTheme(
    "Odin Aurora", Color(0xFF0F0B19), Color(0xFF1C152E), Color(0xFFA884FF), Color(0xFFEEE8FF),
    group = ThemeGroup.ODIN, namePt = "Odin Aurora", accent2 = Color(0xFF5CE1E6),
)
val EliteEmberTheme = ConsoleTheme(
    "Elite Ember", Color(0xFF120C0A), Color(0xFF231713), Color(0xFFFF6A2B), Color(0xFFFFF1EA),
    group = ThemeGroup.ODIN, namePt = "Brasa Elite", accent2 = Color(0xFFFFC14D),
)

/**
 * Exclusivo do Odin 2 Mini: azul-noite de portátil, com o azul das "bolhas" do ecrã inicial do
 * PS Vita como destaque e um rosa suave (o botão quadrado) como segunda cor. Minimalista de
 * propósito: as mesmas superfícies discretas dos outros temas, só a paleta muda.
 */
val BubbleBlueTheme = ConsoleTheme(
    "Bubble Blue", Color(0xFF0A0F1F), Color(0xFF141B33), Color(0xFF4C8DFF), Color(0xFFE9EEFF),
    group = ThemeGroup.ODIN, namePt = "Azul Bolha", accent2 = Color(0xFFF178B6),
)

// ---- Vibrantes ----
val NeonTheme = ConsoleTheme(
    "Neon Grid", Color(0xFF09090C), Color(0xFF14141B), Color(0xFFFCEE0A), Color(0xFFE6FFFA),
    group = ThemeGroup.VIBRANT, namePt = "Grade Neon", accent2 = Color(0xFF00FFD1),
)
val ArenaTheme = ConsoleTheme(
    "Arena Green", Color(0xFF0D0F0D), Color(0xFF181C18), Color(0xFF2BD45A), Color(0xFFF2FFF4),
    group = ThemeGroup.VIBRANT, namePt = "Verde Arena", accent2 = Color(0xFFB6FF5C),
)
val VaporTheme = ConsoleTheme(
    "Vapor Blue", Color(0xFF151A22), Color(0xFF1F2B3A), Color(0xFF66C0F4), Color(0xFFD3DFEA),
    group = ThemeGroup.VIBRANT, namePt = "Azul Vapor", accent2 = Color(0xFF9AD8FF),
)
val RoyalTheme = ConsoleTheme(
    "Royal Blue", Color(0xFF05081E), Color(0xFF101A48), Color(0xFF2F7BFF), Color(0xFFFFFFFF),
    group = ThemeGroup.VIBRANT, namePt = "Azul Real", accent2 = Color(0xFF7FB0FF),
)

// ---- Retrô ----
val Retro16Theme = ConsoleTheme(
    "Retro 16-bit", Color(0xFFE5E6EA), Color(0xFFD3D6DC), Color(0xFF5A4D9C), Color(0xFF2E2E36),
    group = ThemeGroup.RETRO, namePt = "Retrô 16-bit", accent2 = Color(0xFFB8336A),
)
val Retro8Theme = ConsoleTheme(
    "Retro 8-bit", Color(0xFF2B2B2B), Color(0xFF171717), Color(0xFFE60012), Color(0xFFFFFFFF),
    group = ThemeGroup.RETRO, namePt = "Retrô 8-bit", accent2 = Color(0xFFBDBDBD),
)

// ---- Sistema ----
val DynamicTheme = ConsoleTheme(
    "Dynamic", Color.Black, Color.DarkGray, Color.White, Color.White, isDynamic = true,
    group = ThemeGroup.SYSTEM, namePt = "Dinâmico",
)

/**
 * A lista que o índice gravado (`selected_theme_index`) indexa. NÃO reordenar nem remover: o
 * índice está nas preferências. Temas novos entram no FIM. O seletor mostra-os por [ThemeGroup].
 * Migração da lista antiga (v0.13: Dynamic, Odin, Light, Cyberpunk, SNES, NES, Xbox, Steam,
 * PlayStation) em [migrateLegacyThemeIndex].
 */
val AvailableThemes = listOf(
    DynamicTheme, OdinTheme, OdinFrostTheme, OdinAuroraTheme, EliteEmberTheme,
    NeonTheme, ArenaTheme, VaporTheme, RoyalTheme, Retro16Theme, Retro8Theme,
    BubbleBlueTheme,
)

/** Índice do tema exclusivo do Odin 2 Mini em [AvailableThemes] (ver DeviceProfiles.ODIN2_MINI). */
const val VITA_THEME_INDEX = 11

/** Temas por grupo, na ordem do seletor, com o índice de cada um em [AvailableThemes]. */
val ThemesByGroup: List<Pair<ThemeGroup, List<IndexedValue<ConsoleTheme>>>>
    get() = ThemeGroup.entries.map { g -> g to AvailableThemes.withIndex().filter { it.value.group == g } }

/** Índice da lista antiga (até v0.13) -> índice em [AvailableThemes]. */
fun migrateLegacyThemeIndex(old: Int): Int = when (old) {
    0 -> 0 // Dynamic
    1 -> 1 // Odin OS -> Odin Rubi
    2 -> 2 // Light OS -> Odin Ártico
    3 -> 5 // Cyberpunk -> Grade Neon
    4 -> 9 // SNES -> Retrô 16-bit
    5 -> 10 // NES -> Retrô 8-bit
    6 -> 6 // Xbox -> Verde Arena
    7 -> 7 // Steam -> Azul Vapor
    8 -> 8 // PlayStation -> Azul Real
    else -> 1
}

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

/**
 * Cor dos avisos (pisos do firmware, OC, GPU quente, bateria baixa). Um âmbar só, que se lê em
 * TODOS os temas: claro sobre os temas escuros, escuro sobre os claros (Light OS, SNES). Antes
 * havia um laranja fixo (#FFA23A) que quase desaparecia nos temas claros.
 */
val ConsoleTheme.warning: Color get() = if (isLight) Color(0xFF8A4B00) else Color(0xFFFFB454)

/** Cor de perigo/erro, legível em temas claros e escuros. */
val ConsoleTheme.danger: Color get() = if (isLight) Color(0xFFC62828) else Color(0xFFFF5A5A)

/** Raio dos cantos dos cartões: mais arredondado no modo vidro. */
val ConsoleTheme.cardRadius: Int get() = if (glassMode) 20 else 8

/** Reflexo no topo das superfícies de vidro (mais forte no modo vidro). */
val ConsoleTheme.glassHighlight: Color get() = Color.White.copy(alpha = if (glassMode) (if (isLight) 0.30f else 0.14f) else 0.07f)

/** Contorno de luz do vidro (de cima para baixo): claro no topo, quase nada em baixo. */
val ConsoleTheme.glassEdgeTop: Color get() = Color.White.copy(alpha = if (isLight) 0.85f else 0.38f)
val ConsoleTheme.glassEdgeBottom: Color get() = Color.White.copy(alpha = if (isLight) 0.25f else 0.06f)

/** Texto secundário com contraste suficiente (antes 0.5-0.6 de alpha, ilegível em vários temas). */
val ConsoleTheme.secondaryText: Color get() = text.copy(alpha = 0.78f)
