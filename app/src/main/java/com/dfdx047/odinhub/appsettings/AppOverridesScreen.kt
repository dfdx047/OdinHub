package com.dfdx047.odinhub.appsettings

import com.dfdx047.odinhub.ui.theme.onPrimary
import com.dfdx047.odinhub.ui.theme.glass
import com.dfdx047.odinhub.ui.theme.backdropBottom
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.graphics.Brush
import com.dfdx047.odinhub.models.FanMode
import com.dfdx047.odinhub.models.FeatureFlags
import com.dfdx047.odinhub.models.TdpProfiles
import com.dfdx047.odinhub.ui.screens.ClockChipRow
import com.dfdx047.odinhub.ui.screens.MacroEditorDialog
import com.dfdx047.odinhub.ui.screens.MacroModeChips
import com.dfdx047.odinhub.models.ButtonMacros
import com.dfdx047.odinhub.models.MacroMode
import com.dfdx047.odinhub.tools.hardware.ThermalLimitModes
import com.dfdx047.odinhub.ui.theme.AvailableThemes
import com.dfdx047.odinhub.ui.theme.ConsoleTheme
import com.dfdx047.odinhub.ui.theme.getResolvedTheme

/**
 * Editor da regra de UM jogo. Usa exatamente os mesmos modelos que o ecrã principal e o overlay:
 * perfis de TDP de [TdpProfiles] (watts do aparelho detetado / Stock, independentes do slider) e
 * chips de clock com as frequências REAIS do SoC (as mesmas tabelas do PerformanceManager).
 * Todo o texto existe em EN e PT-BR e segue o idioma escolhido na app.
 */
@Composable
fun AppOverridesScreen(
    viewModel: AppOverrideViewModel = hiltViewModel(),
    navigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val clockTables by viewModel.clockTables.collectAsState()
    val isEn by viewModel.isEnglish.collectAsState()
    val hasBackButtons by viewModel.hasBackButtons.collectAsState()
    val soc = remember { com.dfdx047.odinhub.tools.DeviceRegistry.current.soc }
    val haptic = LocalHapticFeedback.current

    var expandedLsfgMult by remember { mutableStateOf(false) }
    var expandedSgsrMode by remember { mutableStateOf(false) }
    var expandedReshade by remember { mutableStateOf(false) }
    var confirmUnthrottled by remember { mutableStateOf(false) }


    val sgsrModes = listOf("Quality", "Balanced", "Performance", "Ultra")
    val isTdpMode = uiState.limitMode == "TDP"

    val rawTheme = AvailableThemes.getOrElse(uiState.selectedThemeIndex) { AvailableThemes[0] }
    val theme = getResolvedTheme(rawTheme, uiState.useAmoledBlack, uiState.useGlass)
    val accentColor = theme.primary
    val mutedText = theme.text.copy(alpha = 0.7f)
    val tap = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }

    uiState.macroEditorFor?.let { button ->
        MacroEditorDialog(
            buttonLabel = button.uppercase(),
            initialEnabled = true,
            initialSteps = if (button == "m1") uiState.m1MacroSteps else uiState.m2MacroSteps,
            theme = theme,
            isEn = isEn,
            onCancel = { viewModel.closeMacroEditor() },
            onSave = { _, steps -> viewModel.updateMacroSteps(button, steps) },
            showToggle = false,
        )
    }

    if (confirmUnthrottled) {
        AlertDialog(
            onDismissRequest = { confirmUnthrottled = false },
            containerColor = theme.surface,
            title = { Text(if (isEn) "Disable thermal throttling for this game?" else "Desativar o throttling térmico neste jogo?", color = theme.text, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (isEn) "First-stage trip points go to 115 °C while this game is open (emergency trips are never touched). Keep the fan at maximum -- at your own risk."
                    else "Os trip points de primeira linha sobem para 115 °C enquanto este jogo estiver aberto (os de emergência nunca são tocados). Mantenha a ventoinha no máximo -- por sua conta e risco.",
                    color = theme.text.copy(alpha = 0.8f), fontSize = 13.sp,
                )
            },
            confirmButton = { TextButton(onClick = { confirmUnthrottled = false; viewModel.updateThermalMode(ThermalLimitModes.UNTHROTTLED) }) { Text(if (isEn) "I understand" else "Entendi", color = Color(0xFFFF5252)) } },
            dismissButton = { TextButton(onClick = { confirmUnthrottled = false }) { Text(if (isEn) "Cancel" else "Cancelar", color = theme.primary) } },
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(theme.background)) {
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = listOf(theme.background.copy(alpha = 0.4f), theme.backdropBottom)
                )
            )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp).windowInsetsPadding(WindowInsets.systemBars),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(16.dp)) }

            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (isEn) "PER-APP RULES" else "REGRAS POR APLICATIVO", color = theme.text.copy(alpha = 0.7f), fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(uiState.appName, color = accentColor, fontSize = 26.sp, fontWeight = FontWeight.Black, lineHeight = 28.sp)
                        Text(uiState.packageName, color = mutedText, fontSize = 12.sp)
                    }
                    TextButton(onClick = { tap(); navigateBack() }) {
                        Text(if (isEn) "Back" else "Voltar", color = theme.primary, fontFamily = theme.fontFamily)
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }
            item { Text("⚡ PERFORMANCE & HARDWARE", color = theme.text, fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 1.sp) }

            item {
                // TDP e Clocks são MUTUAMENTE EXCLUSIVOS -- o mesmo modelo do overlay e do ecrã
                // principal: o jogo usa OU um limite de TDP (que conduz CPU+GPU) OU clocks fixos.
                AppOverrideCard(title = if (isEn) "Limit Mode" else "Modo de Limite", subtitle = if (isTdpMode) "TDP" else (if (isEn) "Manual Clocks" else "Clocks Manuais"), enabled = true, onClick = null, theme = theme) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OverrideChip("TDP", isTdpMode, accentColor, theme, Modifier.weight(1f)) { tap(); viewModel.updateLimitMode("TDP") }
                        OverrideChip("Clocks", !isTdpMode, accentColor, theme, Modifier.weight(1f)) { tap(); viewModel.updateLimitMode("CLOCK") }
                    }
                }
            }

            item {
                val tdpSubtitle = if (uiState.tdpProfileId == TdpProfiles.ID_STOCK) {
                    if (isEn) "No limit (Stock)" else "Sem limite (Stock)"
                } else TdpProfiles.formatWatts(uiState.tdpWatts)
                AppOverrideCard(title = if (isEn) "TDP Limit" else "Limite de TDP", subtitle = tdpSubtitle, enabled = isTdpMode, onClick = null, theme = theme) {
                    Column {
                        if (!isTdpMode) {
                            Text(if (isEn) "Disabled while Clock mode is active" else "Desativado enquanto o modo Clocks está ativo", color = accentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TdpProfiles.fixed.forEach { profile ->
                                OverrideChip(profile.label(isEn), uiState.tdpProfileId == profile.id, accentColor, theme, Modifier.weight(1f), enabled = isTdpMode) { tap(); viewModel.selectTdpProfile(profile.id) }
                            }
                        }
                        TdpProfiles.byId(uiState.tdpProfileId)?.let { selected ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(selected.watts?.let { "${TdpProfiles.formatWatts(it)} -- ${selected.description(isEn)}" } ?: selected.description(isEn), color = mutedText, fontSize = 10.sp)
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Slider(
                            value = uiState.tdpWatts.coerceIn(TdpProfiles.TDP_MIN_WATTS, TdpProfiles.TDP_MAX_WATTS),
                            onValueChange = { viewModel.updateTdp(it) },
                            valueRange = TdpProfiles.TDP_MIN_WATTS..TdpProfiles.TDP_MAX_WATTS,
                            enabled = isTdpMode,
                            colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor),
                        )
                    }
                }
            }

            item {
                AppOverrideCard(title = if (isEn) "Manual Clocks" else "Clocks Manuais", subtitle = "Perf ${uiState.perfClockMHz.toInt()} | Prime ${uiState.primeClockMHz.toInt()} | GPU ${uiState.gpuClockMHz.toInt()} MHz", enabled = !isTdpMode, onClick = null, theme = theme) {
                    Column {
                        if (isTdpMode) {
                            Text(if (isEn) "Disabled while TDP mode is active" else "Desativado enquanto o modo TDP está ativo", color = accentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        if (clockTables.hasEfficiencyCluster) {
                            ClockChipRow(
                                title = (if (isEn) "Efficiency Cluster" else "Cluster Eficiência") + soc.effCores.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(),
                                currentMHz = if (uiState.effClockMHz > 0f) uiState.effClockMHz else (clockTables.effMHz.lastOrNull()?.toFloat() ?: 0f),
                                tableMHz = clockTables.effMHz,
                                enabled = !isTdpMode,
                                theme = theme,
                                isEn = isEn,
                            ) { tap(); viewModel.updateClocks(uiState.perfClockMHz, uiState.primeClockMHz, uiState.gpuClockMHz, it.toFloat()) }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        ClockChipRow(
                            title = (if (isEn) "Perf Cluster" else "Cluster Perf") + soc.perfCores.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(),
                            currentMHz = uiState.perfClockMHz,
                            tableMHz = clockTables.perfMHz,
                            enabled = !isTdpMode,
                            theme = theme,
                            isEn = isEn,
                        ) { tap(); viewModel.updateClocks(it.toFloat(), uiState.primeClockMHz, uiState.gpuClockMHz) }
                        Spacer(modifier = Modifier.height(16.dp))
                        ClockChipRow(
                            title = (if (isEn) "Prime Cluster" else "Cluster Prime") + soc.primeCores.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(),
                            currentMHz = uiState.primeClockMHz,
                            tableMHz = clockTables.primeMHz,
                            enabled = !isTdpMode,
                            theme = theme,
                            isEn = isEn,
                        ) { tap(); viewModel.updateClocks(uiState.perfClockMHz, it.toFloat(), uiState.gpuClockMHz) }
                        Spacer(modifier = Modifier.height(16.dp))
                        ClockChipRow(
                            title = soc.gpuName + (if (isEn) " (independent)" else " (independente)"),
                            currentMHz = uiState.gpuClockMHz,
                            tableMHz = clockTables.gpuMHz,
                            enabled = !isTdpMode,
                            theme = theme,
                            isEn = isEn,
                        ) { tap(); viewModel.updateClocks(uiState.perfClockMHz, uiState.primeClockMHz, it.toFloat()) }
                    }
                }
            }

            item {
                AppOverrideCard(title = if (isEn) "Cooling (Fan)" else "Resfriamento (Ventoinha)", subtitle = FanMode.fromSettingsValue(uiState.fanSettingsValue).shortLabel, enabled = true, onClick = null, theme = theme) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (FanMode.selectable + FanMode.Stock).forEach { mode ->
                            OverrideChip(mode.shortLabel, uiState.fanSettingsValue == mode.settingsValue, accentColor, theme, Modifier.weight(1f)) { tap(); viewModel.updateFanMode(mode.settingsValue) }
                        }
                    }
                }
            }

            if (viewModel.thermalSupported) item {
                val thermalLabel: (String) -> String = { mode ->
                    when (mode) {
                        ThermalLimitModes.STOCK -> "Stock"
                        ThermalLimitModes.UNTHROTTLED -> if (isEn) "Off" else "Sem"
                        else -> "$mode°"
                    }
                }
                val subtitle = uiState.thermalMode?.let { thermalLabel(it) } ?: (if (isEn) "Follows global" else "Segue o global")
                AppOverrideCard(title = if (isEn) "Thermal Limit" else "Limite Térmico", subtitle = subtitle, enabled = true, onClick = null, theme = theme) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OverrideChip("Global", uiState.thermalMode == null, accentColor, theme, Modifier.weight(1f)) { tap(); viewModel.updateThermalMode(null) }
                        ThermalLimitModes.all.forEach { mode ->
                            OverrideChip(thermalLabel(mode), uiState.thermalMode == mode, accentColor, theme, Modifier.weight(1f)) {
                                tap()
                                if (mode == ThermalLimitModes.UNTHROTTLED) confirmUnthrottled = true else viewModel.updateThermalMode(mode)
                            }
                        }
                    }
                }
            }

            item {
                AppOverrideCard(
                    title = if (isEn) "Display Color" else "Cor do Ecrã",
                    subtitle = if (uiState.displayOverride) "%.1f | %dK".format(uiState.saturationOverride, uiState.temperatureOverride.toInt()) else (if (isEn) "Follows global" else "Segue o global"),
                    enabled = true, onClick = null, theme = theme,
                ) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (isEn) "Custom color for this game" else "Cor própria neste jogo", color = theme.text, modifier = Modifier.weight(1f))
                            AppOverrideToggle(checked = uiState.displayOverride, accent = accentColor) { tap(); viewModel.updateDisplayOverride(it) }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text((if (isEn) "Saturation: " else "Saturação: ") + "%.2f".format(uiState.saturationOverride), color = theme.text.copy(alpha = if (uiState.displayOverride) 1f else 0.5f))
                        Slider(value = uiState.saturationOverride, onValueChange = { viewModel.updateSaturation(it) }, valueRange = 0.0f..2.0f, enabled = uiState.displayOverride, colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor))
                        Text((if (isEn) "Temperature: " else "Temperatura: ") + "${uiState.temperatureOverride.toInt()}K", color = theme.text.copy(alpha = if (uiState.displayOverride) 1f else 0.5f))
                        Slider(value = uiState.temperatureOverride, onValueChange = { viewModel.updateTemperature(it) }, valueRange = 4000f..9000f, enabled = uiState.displayOverride, colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor))
                        Text(if (isEn) "Warmer ← 6500K neutral → Cooler" else "Mais quente ← 6500K neutro → Mais frio", color = mutedText, fontSize = 10.sp)
                    }
                }
            }

            if (hasBackButtons) item {
                AppOverrideCard(
                    title = if (isEn) "Back Buttons (M1/M2)" else "Botões Traseiros (M1/M2)",
                    subtitle = if (isEn) "Macros / combos for this game" else "Macros / combos neste jogo",
                    enabled = true, onClick = null, theme = theme,
                ) {
                    Column {
                        listOf("m1", "m2").forEachIndexed { i, button ->
                            val mode = if (button == "m1") uiState.m1MacroMode else uiState.m2MacroMode
                            val steps = if (button == "m1") uiState.m1MacroSteps else uiState.m2MacroSteps
                            if (i > 0) Spacer(modifier = Modifier.height(12.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(button.uppercase(), color = theme.text, fontWeight = FontWeight.Bold)
                                if (mode == MacroMode.CUSTOM) {
                                    Text(
                                        (if (steps.isEmpty()) (if (isEn) "No steps" else "Sem passos") else steps.joinToString("  ›  ") { ButtonMacros.describe(it) }) + (if (isEn) "   Edit >" else "   Editar >"),
                                        color = accentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                                        modifier = Modifier.weight(1f, fill = false).padding(start = 12.dp).clickable { tap(); viewModel.openMacroEditor(button) },
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            MacroModeChips(mode = mode, theme = theme, isEn = isEn) { tap(); viewModel.updateMacroMode(button, it) }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            if (isEn) "Global = follows the Controls tab. Off = native button in this game." else "Global = segue a aba Controles. Desligada = botão nativo neste jogo.",
                            color = mutedText, fontSize = 10.sp,
                        )
                    }
                }
            }

            // Motor gráfico (SGSR / ReShade / Frame Gen): escondido enquanto não existir um motor
            // que atue de facto dentro dos jogos -- ver FeatureFlags.GRAPHICS_ENGINE_AVAILABLE.
            if (FeatureFlags.GRAPHICS_ENGINE_AVAILABLE) {
            item { Spacer(modifier = Modifier.height(16.dp)) }
            item { Text(if (isEn) "📺 DISPLAY, UPSCALING & FRAME GEN" else "📺 ECRÃ, UPSCALING & FRAME GEN", color = theme.text, fontWeight = FontWeight.Black, fontSize = 14.sp, letterSpacing = 1.sp) }

            item {
                AppOverrideCard(title = "Lossless Scaling (Frame Gen)", subtitle = if (isEn) "Frame multiplication" else "Multiplicação de quadros", enabled = true, onClick = null, theme = theme) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (isEn) "Enable LSFG for this app" else "Ativar LSFG neste App", color = theme.text)
                            AppOverrideToggle(checked = uiState.lsfgEnabled, accent = accentColor) { tap(); viewModel.updateLsfg(it, uiState.lsfgMultiplier, uiState.lsfgFramePacing, uiState.lsfgPerformanceMode, uiState.lsfgQuality) }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (isEn) "Multiplier" else "Multiplicador", color = theme.text)
                            Box {
                                Text(uiState.lsfgMultiplier, color = accentColor, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { if (uiState.lsfgEnabled) { tap(); expandedLsfgMult = true } }.alpha(if (uiState.lsfgEnabled) 1f else 0.5f))
                                DropdownMenu(expanded = expandedLsfgMult, onDismissRequest = { expandedLsfgMult = false }, modifier = Modifier.background(theme.surface)) {
                                    listOf("2x", "3x", "4x").forEach { mult ->
                                        DropdownMenuItem(text = { Text(mult, color = theme.text) }, onClick = { tap(); viewModel.updateLsfg(uiState.lsfgEnabled, mult, uiState.lsfgFramePacing, uiState.lsfgPerformanceMode, uiState.lsfgQuality); expandedLsfgMult = false })
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (isEn) "Frame pacing" else "Sincronia (Frame Pacing)", color = theme.text)
                            AppOverrideToggle(checked = uiState.lsfgFramePacing, accent = accentColor) { if (uiState.lsfgEnabled) { tap(); viewModel.updateLsfg(uiState.lsfgEnabled, uiState.lsfgMultiplier, it, uiState.lsfgPerformanceMode, uiState.lsfgQuality) } }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (isEn) "LSFG Performance Mode" else "Modo Performance LSFG", color = theme.text)
                            AppOverrideToggle(checked = uiState.lsfgPerformanceMode, accent = accentColor) { if (uiState.lsfgEnabled) { tap(); viewModel.updateLsfg(uiState.lsfgEnabled, uiState.lsfgMultiplier, uiState.lsfgFramePacing, it, uiState.lsfgQuality) } }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text((if (isEn) "Quality: " else "Qualidade: ") + "${(uiState.lsfgQuality * 100).toInt()}%", color = theme.text)
                        Slider(value = uiState.lsfgQuality, onValueChange = { viewModel.updateLsfg(uiState.lsfgEnabled, uiState.lsfgMultiplier, uiState.lsfgFramePacing, uiState.lsfgPerformanceMode, it) }, valueRange = 0.5f..1.0f, enabled = uiState.lsfgEnabled, colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor))
                    }
                }
            }

            item {
                AppOverrideCard(title = "Snapdragon Super Resolution", subtitle = if (isEn) "Spatial upscaling" else "Upscaling espacial dedicado", enabled = true, onClick = null, theme = theme) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (isEn) "Enable SGSR" else "Ativar SGSR", color = theme.text)
                            AppOverrideToggle(checked = uiState.sgsrEnabled, accent = accentColor) { tap(); viewModel.updateSgsr(it, uiState.sgsrMode, uiState.sgsrSharpness) }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (isEn) "Quality Mode" else "Modo de Qualidade", color = theme.text)
                            Box {
                                Text(uiState.sgsrMode, color = accentColor, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { if (uiState.sgsrEnabled) { tap(); expandedSgsrMode = true } }.alpha(if (uiState.sgsrEnabled) 1f else 0.5f))
                                DropdownMenu(expanded = expandedSgsrMode, onDismissRequest = { expandedSgsrMode = false }, modifier = Modifier.background(theme.surface)) {
                                    sgsrModes.forEach { mode ->
                                        DropdownMenuItem(text = { Text(mode, color = theme.text) }, onClick = { tap(); viewModel.updateSgsr(uiState.sgsrEnabled, mode, uiState.sgsrSharpness); expandedSgsrMode = false })
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text((if (isEn) "Sharpness: " else "Nitidez: ") + "${(uiState.sgsrSharpness * 100).toInt()}%", color = theme.text)
                        Slider(value = uiState.sgsrSharpness, onValueChange = { viewModel.updateSgsr(uiState.sgsrEnabled, uiState.sgsrMode, it) }, valueRange = 0.0f..1.0f, enabled = uiState.sgsrEnabled, colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor))
                    }
                }
            }

            item {
                AppOverrideCard(title = if (isEn) "ReShade & Color Calibration" else "ReShade & Calibração de Cor", subtitle = uiState.reshadeProfile, enabled = true, onClick = null, theme = theme) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (isEn) "ReShade Profile" else "Perfil ReShade", color = theme.text)
                            Box {
                                Text(uiState.reshadeProfile, color = accentColor, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { tap(); expandedReshade = true })
                                DropdownMenu(expanded = expandedReshade, onDismissRequest = { expandedReshade = false }, modifier = Modifier.background(theme.surface)) {
                                    uiState.availableReshadeProfiles.forEach { profile ->
                                        DropdownMenuItem(text = { Text(profile, color = theme.text) }, onClick = { tap(); viewModel.updateDisplayColor(profile, uiState.saturationOverride, uiState.temperatureOverride); expandedReshade = false })
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text((if (isEn) "Saturation: " else "Saturação: ") + "%.1f".format(uiState.saturationOverride), color = theme.text)
                        Slider(value = uiState.saturationOverride, onValueChange = { viewModel.updateDisplayColor(uiState.reshadeProfile, it, uiState.temperatureOverride) }, valueRange = 0.0f..2.0f, colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text((if (isEn) "Temperature: " else "Temperatura: ") + "${uiState.temperatureOverride.toInt()}K", color = theme.text)
                        Slider(value = uiState.temperatureOverride, onValueChange = { viewModel.updateDisplayColor(uiState.reshadeProfile, uiState.saturationOverride, it) }, valueRange = 4000f..9000f, colors = SliderDefaults.colors(thumbColor = accentColor, activeTrackColor = accentColor))
                    }
                }
            }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (uiState.isSaved) {
                        Button(
                            onClick = { tap(); viewModel.deleteOverride(); navigateBack() },
                            modifier = Modifier.weight(1f).height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(if (isEn) "Remove" else "Remover", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    Button(
                        onClick = { tap(); viewModel.saveOverride(); navigateBack() },
                        modifier = Modifier.weight(1.5f).height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(if (isEn) "Save Rule" else "Guardar Regra", color = theme.onPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** Chip de seleção deste ecrã (mesmo estilo dos chips da aba Performance). */
@Composable
private fun OverrideChip(
    label: String,
    selected: Boolean,
    accent: Color,
    theme: ConsoleTheme,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) accent else theme.text.copy(alpha = 0.10f))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 8.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) theme.onPrimary else theme.text, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/**
 * Cartão deste ecrã, agora alinhado com o `ConsoleCard` do ecrã principal: mesma forma de 8 dp,
 * mesma superfície translúcida do tema e mesma faixa escura por baixo do conteúdo. Antes tinha
 * cantos de 12 dp e cores fixas em branco, o que fazia este ecrã destoar visivelmente do resto.
 */
@Composable
fun AppOverrideCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    theme: ConsoleTheme,
    content: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = theme.glass()),
        modifier = Modifier.fillMaxWidth().border(1.dp, theme.text.copy(alpha = 0.18f), RoundedCornerShape(8.dp)).alpha(if (enabled) 1f else 0.5f)
            .then(if (enabled && onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = theme.text, fontFamily = theme.fontFamily)
            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(subtitle, fontSize = 12.sp, color = theme.text.copy(alpha = 0.6f), fontFamily = theme.fontFamily)
            }
            HorizontalDivider(color = theme.text.copy(alpha = 0.1f), thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))
            content()
        }
    }
}

@Composable
fun AppOverrideToggle(checked: Boolean, accent: Color, onCheckedChange: (Boolean) -> Unit) {
    val thumbOffset by animateDpAsState(targetValue = if (checked) 24.dp else 4.dp, animationSpec = spring(stiffness = Spring.StiffnessMediumLow), label = "toggleMove")
    val bgColor by animateColorAsState(targetValue = if (checked) accent.copy(alpha = 0.3f) else Color.Transparent, label = "toggleBg")
    val thumbColor by animateColorAsState(targetValue = if (checked) accent else Color.Gray, label = "toggleThumb")

    Box(
        modifier = Modifier.width(44.dp).height(24.dp).clip(RoundedCornerShape(6.dp)).background(bgColor).border(1.dp, thumbColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(modifier = Modifier.padding(start = thumbOffset).size(16.dp).clip(RoundedCornerShape(4.dp)).background(thumbColor))
    }
}