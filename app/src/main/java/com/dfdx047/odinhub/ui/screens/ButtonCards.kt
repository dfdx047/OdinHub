package com.dfdx047.odinhub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dfdx047.odinhub.models.ButtonAction
import com.dfdx047.odinhub.models.ButtonMacros
import com.dfdx047.odinhub.models.HomeGestureConfig
import com.dfdx047.odinhub.models.MacroMode
import com.dfdx047.odinhub.models.MacroStep
import com.dfdx047.odinhub.ui.theme.ConsoleTheme
import com.dfdx047.odinhub.ui.theme.onPrimary

/**
 * Cartão "Gestos do botão Home": substitui o antigo "Toque único no Home" (que já existe nas
 * definições da AYN). Cada gesto dispara uma ButtonAction à escolha.
 */
@Composable
fun HomeGesturesCard(
    config: HomeGestureConfig,
    theme: ConsoleTheme,
    isEn: Boolean,
    onChange: (HomeGestureConfig) -> Unit,
) {
    ConsoleCard(
        if (isEn) "Home Button Gestures" else "Gestos do Botão Home",
        if (isEn) "Tap, double tap, triple tap and long press" else "Toque, toque duplo, triplo e toque longo",
        theme,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (isEn) "Enable gestures" else "Ativar gestos", color = theme.text, fontFamily = theme.fontFamily)
                    Text(
                        if (isEn) "Needs the Odin Hub accessibility service. Off = native Home button."
                        else "Precisa do serviço de acessibilidade do Odin Hub. Desligado = botão Home nativo.",
                        color = theme.text.copy(alpha = 0.55f), fontFamily = theme.fontFamily, fontSize = 10.sp,
                    )
                }
                ConsoleToggle(checked = config.enabled, theme = theme, onCheckedChange = { onChange(config.copy(enabled = it)) })
            }
            if (config.enabled) {
                // Diagnóstico: sem isto, "não funciona" podia ser o serviço desligado, o botão a
                // não chegar, ou só a ação escolhida a não fazer nada fora de um jogo.
                val a11yOn by com.dfdx047.odinhub.service.ForegroundAppWatcherService.connectedFlow.collectAsState()
                val last by com.dfdx047.odinhub.tools.HomeGestureDetector.lastGesture.collectAsState()
                Spacer(Modifier.height(6.dp))
                val status = when {
                    !a11yOn -> if (isEn) "⚠ Accessibility service is off — gestures can't work. Enable Odin Hub in Settings › Accessibility."
                               else "⚠ Serviço de acessibilidade desligado — os gestos não funcionam. Ative o Odin Hub em Definições › Acessibilidade."
                    last == null -> if (isEn) "Press Home to test — the last gesture shows here." else "Carregue no Home para testar — o último gesto aparece aqui."
                    else -> {
                        val g = last!!
                        val kind = when (g.taps) {
                            -1 -> if (isEn) "Long press" else "Toque longo"
                            1 -> if (isEn) "Single tap" else "Toque simples"
                            2 -> if (isEn) "Double tap" else "Toque duplo"
                            else -> if (isEn) "Triple tap" else "Toque triplo"
                        }
                        (if (isEn) "Last: " else "Último: ") + kind + " → " + g.action.label(isEn)
                    }
                }
                Text(status, color = if (!a11yOn) theme.primary else theme.text.copy(alpha = 0.7f), fontFamily = theme.fontFamily, fontSize = 11.sp)
            }
            Spacer(Modifier.height(8.dp))
            GestureRow(if (isEn) "Single tap" else "Toque simples", config.singleTap, config.enabled, theme, isEn) { onChange(config.copy(singleTap = it)) }
            GestureRow(if (isEn) "Double tap" else "Toque duplo", config.doubleTap, config.enabled, theme, isEn) { onChange(config.copy(doubleTap = it)) }
            GestureRow(if (isEn) "Triple tap" else "Toque triplo", config.tripleTap, config.enabled, theme, isEn) { onChange(config.copy(tripleTap = it)) }
            GestureRow(
                (if (isEn) "Long press" else "Toque longo") + " (${formatSeconds(config.longPressMs)})",
                config.longPress, config.enabled, theme, isEn,
            ) { onChange(config.copy(longPress = it)) }
            Spacer(Modifier.height(8.dp))
            Text(if (isEn) "Long press duration" else "Duração do toque longo", color = theme.text.copy(alpha = 0.7f), fontFamily = theme.fontFamily, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HomeGestureConfig.LONG_PRESS_OPTIONS_MS.forEach { ms ->
                    SmallChip(formatSeconds(ms), config.longPressMs == ms, config.enabled, theme, Modifier.weight(1f)) { onChange(config.copy(longPressMs = ms)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (isEn) "Time between taps (double / triple tap)" else "Tempo entre toques (duplo / triplo)",
                color = theme.text.copy(alpha = 0.7f), fontFamily = theme.fontFamily, fontSize = 11.sp,
            )
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HomeGestureConfig.MULTI_TAP_OPTIONS_MS.forEach { ms ->
                    SmallChip("$ms ms", config.multiTapWindowMs == ms, config.enabled, theme, Modifier.weight(1f)) { onChange(config.copy(multiTapWindowMs = ms)) }
                }
            }
            Text(
                if (isEn) "If double taps come out as two single taps, pick a longer time. Longer = the single tap fires a bit later."
                else "Se o toque duplo sai como dois simples, escolha um tempo maior. Maior = o toque simples demora um pouco mais.",
                color = theme.text.copy(alpha = 0.5f), fontFamily = theme.fontFamily, fontSize = 10.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (isEn) "Screen recording captures the whole screen (no app picker) and is saved to Movies/OdinHub. No audio."
                else "A gravação capta o ecrã inteiro (sem escolher app) e fica em Movies/OdinHub. Sem áudio.",
                color = theme.text.copy(alpha = 0.5f), fontFamily = theme.fontFamily, fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun GestureRow(label: String, action: ButtonAction, enabled: Boolean, theme: ConsoleTheme, isEn: Boolean, onSelect: (ButtonAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { expanded = true }.padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = theme.text.copy(alpha = if (enabled) 1f else 0.5f), fontFamily = theme.fontFamily, fontSize = 13.sp)
        Box {
            Text(action.label(isEn), color = theme.primary.copy(alpha = if (enabled) 1f else 0.5f), fontWeight = FontWeight.Bold, fontFamily = theme.fontFamily, fontSize = 12.sp)
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(theme.surface)) {
                ButtonAction.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label(isEn), color = if (option == action) theme.primary else theme.text, fontFamily = theme.fontFamily) },
                        onClick = { expanded = false; onSelect(option) },
                    )
                }
            }
        }
    }
    HorizontalDivider(color = theme.text.copy(alpha = 0.08f), thickness = 1.dp)
}

@Composable
private fun SmallChip(label: String, selected: Boolean, enabled: Boolean, theme: ConsoleTheme, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) theme.primary.copy(alpha = if (enabled) 1f else 0.5f) else theme.text.copy(alpha = 0.10f))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 8.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) theme.onPrimary else theme.text.copy(alpha = if (enabled) 1f else 0.5f), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

private fun formatSeconds(ms: Long): String {
    val s = ms / 1000f
    return if (s % 1f == 0f) "${s.toInt()} s" else "$s s"
}

/**
 * Editor de macro/combo de um botão traseiro. Cada passo = teclas premidas em simultâneo +
 * espera até ao passo seguinte.
 */
@Composable
fun MacroEditorDialog(
    buttonLabel: String,
    initialEnabled: Boolean,
    initialSteps: List<MacroStep>,
    theme: ConsoleTheme,
    isEn: Boolean,
    onCancel: () -> Unit,
    onSave: (Boolean, List<MacroStep>) -> Unit,
    /** false no editor por jogo: aí o modo (Global / Desligado / Próprio) é escolhido fora do diálogo. */
    showToggle: Boolean = true,
) {
    var enabled by remember { mutableStateOf(initialEnabled || initialSteps.isEmpty()) }
    val steps = remember { mutableStateListOf<MacroStep>().apply { addAll(initialSteps) } }

    Dialog(onDismissRequest = onCancel) {
        Surface(shape = RoundedCornerShape(16.dp), color = theme.surface) {
            Column(modifier = Modifier.padding(20.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                Text("$buttonLabel — Macro / Combo", color = theme.primary, fontWeight = FontWeight.Bold, fontSize = 18.sp, fontFamily = theme.fontFamily)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isEn) "Each step presses its keys together, then waits. Replaces the native remap while enabled. Runs through PServer, so each step takes ~0.1–0.3 s."
                    else "Cada passo prime as suas teclas em simultâneo e depois espera. Substitui o mapeamento nativo enquanto estiver ligado. Corre pelo PServer, por isso cada passo demora ~0,1–0,3 s.",
                    color = theme.text.copy(alpha = 0.6f), fontSize = 11.sp, fontFamily = theme.fontFamily,
                )
                if (showToggle) {
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (isEn) "Use macro on $buttonLabel" else "Usar macro no $buttonLabel", color = theme.text, fontFamily = theme.fontFamily)
                        ConsoleToggle(checked = enabled, theme = theme, onCheckedChange = { enabled = it })
                    }
                }

                Spacer(Modifier.height(12.dp))
                MacroStepsEditor(steps = steps, theme = theme, isEn = isEn)
                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onCancel, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = theme.background)) {
                        Text(if (isEn) "Cancel" else "Cancelar", color = theme.text, fontFamily = theme.fontFamily)
                    }
                    Button(onClick = { onSave(enabled, steps.toList()) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = theme.primary)) {
                        Text(if (isEn) "Save" else "Salvar", color = theme.onPrimary, fontFamily = theme.fontFamily)
                    }
                }
            }
        }
    }
}

/** Linha "Macro / Combo" por baixo de cada botão traseiro no cartão de mapeamento. */
@Composable
fun MacroEntryRow(buttonLabel: String, enabled: Boolean, stepCount: Int, theme: ConsoleTheme, isEn: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(start = 56.dp, end = 16.dp, top = 0.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (if (isEn) "$buttonLabel macro / combo" else "Macro / combo do $buttonLabel") +
                if (stepCount > 0) (if (isEn) " · $stepCount steps" else " · $stepCount passos") else "",
            color = theme.text.copy(alpha = 0.8f), fontFamily = theme.fontFamily, fontSize = 12.sp,
        )
        Text(
            if (enabled) (if (isEn) "ON" else "LIGADA") else (if (isEn) "Edit >" else "Editar >"),
            color = theme.primary, fontWeight = FontWeight.Bold, fontFamily = theme.fontFamily, fontSize = 12.sp,
        )
    }
}

/**
 * Lista de passos + construtor de passo novo. Partilhado pelo diálogo (app) e pelo editor em linha
 * do overlay (uma janela de overlay não pode abrir diálogos). [onChanged] corre depois de cada
 * alteração -- o overlay usa-o para gravar logo no Room.
 */
@Composable
fun MacroStepsEditor(
    steps: SnapshotStateList<MacroStep>,
    theme: ConsoleTheme,
    isEn: Boolean,
    compact: Boolean = false,
    onChanged: () -> Unit = {},
) {
    val pending = remember { mutableStateListOf<Int>() }
    var delayMs by remember { mutableIntStateOf(0) }
    val labelSize = if (compact) 9.sp else 10.sp

    Column {
        Text(if (isEn) "STEPS" else "PASSOS", color = theme.text.copy(alpha = 0.5f), fontSize = labelSize, fontWeight = FontWeight.Bold)
        if (steps.isEmpty()) {
            Text(if (isEn) "No steps yet" else "Ainda sem passos", color = theme.text.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
        }
        steps.forEachIndexed { index, step ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}.  ${ButtonMacros.describe(step)}", color = theme.text, fontSize = if (compact) 11.sp else 13.sp, fontFamily = theme.fontFamily, modifier = Modifier.weight(1f))
                Text("✕", color = Color(0xFFFF5252), fontWeight = FontWeight.Bold, modifier = Modifier.clickable { steps.removeAt(index); onChanged() }.padding(horizontal = 8.dp))
            }
        }

        Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
        HorizontalDivider(color = theme.text.copy(alpha = 0.1f), thickness = 1.dp)
        Spacer(Modifier.height(8.dp))
        Text(if (isEn) "NEW STEP — keys pressed together" else "NOVO PASSO — teclas premidas juntas", color = theme.text.copy(alpha = 0.5f), fontSize = labelSize, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        ButtonMacros.GAMEPAD_KEYS.chunked(4).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (code, label) ->
                    SmallChip(label, code in pending, true, theme, Modifier.weight(1f)) {
                        if (code in pending) pending.remove(code) else pending.add(code)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(if (isEn) "Wait after this step" else "Esperar depois deste passo", color = theme.text.copy(alpha = 0.7f), fontSize = 11.sp)
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ButtonMacros.DELAY_OPTIONS_MS.forEach { ms ->
                SmallChip("$ms ms", delayMs == ms, true, theme, Modifier.weight(1f)) { delayMs = ms }
            }
        }
        Spacer(Modifier.height(6.dp))
        val canAdd = pending.isNotEmpty() && steps.size < ButtonMacros.MAX_STEPS
        Button(
            onClick = { steps.add(MacroStep(pending.toList(), delayMs)); pending.clear(); delayMs = 0; onChanged() },
            enabled = canAdd,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
        ) { Text(if (isEn) "Add step" else "Adicionar passo", color = theme.onPrimary, fontFamily = theme.fontFamily) }
    }
}

/**
 * Modo da macro de um botão numa regra por jogo:
 *  - [MacroMode.GLOBAL]: segue a macro/mapeamento global (aba Controls);
 *  - [MacroMode.OFF]: neste jogo o botão fica com o mapeamento nativo, mesmo que haja macro global;
 *  - [MacroMode.CUSTOM]: macro própria deste jogo.
 */
@Composable
fun MacroModeChips(mode: MacroMode, theme: ConsoleTheme, isEn: Boolean, onSelect: (MacroMode) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        MacroMode.entries.forEach { option ->
            SmallChip(option.label(isEn), mode == option, true, theme, Modifier.weight(1f)) { onSelect(option) }
        }
    }
}
