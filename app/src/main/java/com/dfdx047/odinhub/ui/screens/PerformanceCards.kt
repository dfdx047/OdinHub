package com.dfdx047.odinhub.ui.screens

import com.dfdx047.odinhub.ui.theme.onPrimary
import androidx.compose.foundation.background
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dfdx047.odinhub.data.CustomProfile
import com.dfdx047.odinhub.models.DeviceProfile
import com.dfdx047.odinhub.models.SocFamily
import com.dfdx047.odinhub.models.TdpProfiles
import com.dfdx047.odinhub.tools.hardware.AutoTdpPause
import com.dfdx047.odinhub.tools.hardware.AutoTdpTelemetry
import com.dfdx047.odinhub.tools.hardware.ClockTableCuration
import com.dfdx047.odinhub.tools.hardware.ClockTables
import com.dfdx047.odinhub.tools.hardware.ThermalLimitModes
import com.dfdx047.odinhub.tools.hardware.ThermalManager
import com.dfdx047.odinhub.tools.hardware.ThermalStatus
import com.dfdx047.odinhub.ui.theme.ConsoleTheme
import com.dfdx047.odinhub.ui.theme.danger
import com.dfdx047.odinhub.ui.theme.warning

/**
 * Cartões da aba Performance (TDP, clocks manuais e limite térmico), separados de
 * `SettingsScreens.kt` para o `PerformancePanel` ficar legível.
 *
 * Modelo mental (do utilizador): o perfil de TDP é o MESTRE -- é ele que conduz CPU e GPU. Os
 * clocks são manuais, só para quem quer fixar valores específicos, e os dois modos nunca
 * interferem um com o outro: o cartão do modo inativo fica visível mas desativado.
 */

/** Uma linha de aviso dentro de um cartão desativado. */
@Composable
private fun DisabledModeHint(text: String, theme: ConsoleTheme) {
    Text(text, color = theme.primary.copy(alpha = 0.9f), fontFamily = theme.fontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
}

/**
 * Chip retangular reutilizado por todos os cartões desta aba. Selecionado = preenchido na cor do
 * tema com borda clara (e "✓" nos perfis, [check]); num cartão desativado o escolhido fica só
 * contornado: vê-se qual é, sem parecer que está a ser aplicado.
 */
@Composable
private fun PerfChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    theme: ConsoleTheme,
    modifier: Modifier = Modifier,
    textColor: Color? = null,
    check: Boolean = false,
    onClick: () -> Unit,
) {
    val filled = selected && enabled
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (filled) theme.primary else theme.surface.copy(alpha = 0.6f))
            .border(
                if (selected) 2.dp else 0.dp,
                when {
                    filled -> theme.text.copy(alpha = 0.85f)
                    selected -> theme.primary.copy(alpha = 0.8f)
                    else -> Color.Transparent
                },
                RoundedCornerShape(6.dp),
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (selected && check) "✓ $label" else label,
            color = textColor ?: (if (filled) theme.onPrimary else theme.text),
            fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1,
        )
    }
}

/**
 * Cartão do aparelho detetado: nome, SoC, GPU e de onde vêm as tabelas. É o "Odin 2 detetado"
 * permanente -- as tabelas de frequência, as zonas térmicas e os watts dos perfis seguem isto.
 */
@Composable
fun DeviceInfoCard(device: DeviceProfile, tables: ClockTables, theme: ConsoleTheme, isEn: Boolean) {
    val title = if (device.official) {
        (if (isEn) "Detected: " else "Detectado: ") + device.name
    } else {
        device.name
    }
    val subtitle = listOf(device.soc.label, device.soc.gpuName).filter { it.isNotBlank() }.distinct().joinToString(" · ")
    ConsoleCard(title, subtitle, theme, playClick = null) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                if (device.official) {
                    if (isEn) "Frequency tables, thermal zones, GPU control and TDP profiles adjusted for this device."
                    else "Tabelas de frequência, zonas térmicas, controlo da GPU e perfis de TDP ajustados a este aparelho."
                } else {
                    if (isEn) "No dedicated profile for this device: using the kernel's live tables and generic limits."
                    else "Sem perfil próprio para este aparelho: usando as tabelas do kernel e limites genéricos."
                },
                color = theme.text.copy(alpha = 0.7f), fontFamily = theme.fontFamily, fontSize = 11.sp,
            )
            val p = device.power
            Text(
                (if (isEn) "TDP: " else "TDP: ") +
                    "${TdpProfiles.formatWatts(p.powerSave)} / ${TdpProfiles.formatWatts(p.balanced)} / ${TdpProfiles.formatWatts(p.tripleA)} · " +
                    (if (isEn) "max " else "máx. ") + TdpProfiles.formatWatts(p.maxWatts) +
                    (if (tables.isEmpty) "" else " · CPU ${tables.primeMHz.lastOrNull() ?: 0} MHz · GPU ${tables.gpuMHz.lastOrNull() ?: 0} MHz"),
                color = theme.text.copy(alpha = 0.5f), fontFamily = theme.fontFamily, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** "a medir 9.8 W · CPU 72% · GPU 85%" (ou o motivo da pausa) para o cartão de TDP e o overlay. */
fun autoTdpLine(t: AutoTdpTelemetry, isEn: Boolean): String? {
    if (!t.active) return null
    return when (t.paused) {
        AutoTdpPause.CHARGING -> if (isEn) "AutoTDP on hold: plugged in (the battery can't measure power)" else "AutoTDP em pausa: ligado ao carregador (a bateria não mede o consumo)"
        AutoTdpPause.SCREEN_OFF -> if (isEn) "AutoTDP paused (screen off)" else "AutoTDP em pausa (tela desligada)"
        AutoTdpPause.NO_SENSOR -> if (isEn) "AutoTDP: no valid battery reading" else "AutoTDP: sem leitura válida da bateria"
        null -> {
            val watts = t.measuredWatts?.let { TdpProfiles.formatWatts(it) } ?: "--"
            (if (isEn) "Measuring $watts" else "Medindo $watts") + " · CPU ${t.cpuPercent}% · GPU ${t.gpuPercent}%"
        }
    }
}

/**
 * Cartão "Perfis Globais de TDP": chips dos perfis fixos ([TdpProfiles.fixed]), presets do
 * utilizador, slider 1..máximo do aparelho e gravação de preset. Os perfis são independentes do slider.
 */
@Composable
fun TdpProfileCard(
    enabled: Boolean,
    tdpProfileId: String,
    tdpValue: Float,
    customProfiles: List<CustomProfile>,
    autoTdp: AutoTdpTelemetry,
    theme: ConsoleTheme,
    isEn: Boolean,
    onSelectProfile: (String) -> Unit,
    onSelectCustomProfile: (Float) -> Unit,
    onSliderChange: (Float) -> Unit,
    /** `null` = sem gravação aqui (os perfis vivem no cartão "Perfis guardados"). */
    onSavePreset: ((String, Float) -> Unit)? = null,
) {
    var presetName by remember { mutableStateOf("") }
    val selectedFixed = TdpProfiles.byId(tdpProfileId)

    ConsoleCard(
        if (isEn) "Global TDP Profiles" else "Perfis Globais de TDP",
        if (isEn) "The profile drives CPU and GPU together" else "O perfil conduz CPU e GPU em conjunto",
        theme,
        enabled = enabled,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (!enabled) {
                DisabledModeHint(if (isEn) "Disabled while Clock mode is active" else "Desativado enquanto o modo Clocks está ativo", theme)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (profile in TdpProfiles.fixed) {
                    PerfChip(
                        label = profile.label(isEn),
                        selected = tdpProfileId == profile.id,
                        enabled = enabled,
                        theme = theme,
                        modifier = Modifier.weight(1f),
                        check = true,
                    ) { onSelectProfile(profile.id) }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                when {
                    selectedFixed != null -> selectedFixed.watts?.let { "${TdpProfiles.formatWatts(it)} -- ${selectedFixed.description(isEn)}" } ?: selectedFixed.description(isEn)
                    else -> if (isEn) "Custom value from the slider (no profile selected)" else "Valor livre do slider (nenhum perfil selecionado)"
                },
                color = theme.text.copy(alpha = 0.6f),
                fontFamily = theme.fontFamily,
                fontSize = 10.sp,
            )

            val userTdpProfiles = customProfiles.filter { it.type == "TDP" }
            if (userTdpProfiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (prof in userTdpProfiles) {
                        PerfChip(
                            label = "${prof.name} (${TdpProfiles.formatWatts(prof.v1)})",
                            selected = tdpProfileId == TdpProfiles.ID_CUSTOM && tdpValue == prof.v1,
                            enabled = enabled,
                            theme = theme,
                            check = true,
                        ) { onSelectCustomProfile(prof.v1) }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                if (tdpProfileId == TdpProfiles.ID_STOCK) {
                    if (isEn) "TDP Limit: none (Stock)" else "Limite de TDP: nenhum (Stock)"
                } else {
                    (if (isEn) "TDP Limit: " else "Limite de TDP: ") + TdpProfiles.formatWatts(tdpValue)
                },
                color = theme.text,
                fontFamily = theme.fontFamily,
                fontSize = 12.sp,
            )
            Slider(
                value = tdpValue.coerceIn(TdpProfiles.TDP_MIN_WATTS, TdpProfiles.TDP_MAX_WATTS),
                onValueChange = onSliderChange,
                valueRange = TdpProfiles.TDP_MIN_WATTS..TdpProfiles.TDP_MAX_WATTS,
                enabled = enabled,
                colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary),
            )
            if (enabled) {
                autoTdpLine(autoTdp, isEn)?.let { line ->
                    Text(line, color = theme.primary.copy(alpha = 0.85f), fontFamily = theme.fontFamily, fontSize = 10.sp)
                }
            }
            if (onSavePreset != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = presetName, onValueChange = { presetName = it }, label = { Text(if (isEn) "Preset Name" else "Nome do Preset TDP", fontSize = 10.sp) }, modifier = Modifier.weight(1f).height(50.dp), textStyle = TextStyle(fontSize = 12.sp, color = theme.text), enabled = enabled)
                Button(
                    onClick = { if (presetName.isNotBlank() && enabled) { onSavePreset(presetName, tdpValue); presetName = "" } },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
                    modifier = Modifier.height(50.dp),
                    enabled = enabled,
                ) { Text(if (isEn) "Save" else "Salvar", fontSize = 11.sp, color = theme.onPrimary) }
            }
            }
        }
    }
}

/**
 * Uma linha de chips numéricos ("3532 MHz") para um cluster/GPU, com o subconjunto curado por
 * omissão e um botão "Todos" que revela a tabela inteira (scroll horizontal).
 */
@Composable
fun ClockChipRow(
    title: String,
    currentMHz: Float,
    tableMHz: List<Int>,
    enabled: Boolean,
    theme: ConsoleTheme,
    isEn: Boolean,
    /** Máximo de fábrica: chips acima dele são OPPs de overclock do kernel e levam "OC". */
    ocAboveMHz: Int? = null,
    onSelect: (Int) -> Unit,
) {
    var showAll by remember { mutableStateOf(false) }
    val curated = remember(tableMHz) { ClockTableCuration.curate(tableMHz) }
    val visible = if (showAll) tableMHz else curated
    // O chip destacado é o valor da tabela mais próximo do guardado: valores de versões antigas
    // da app (ex: 3530) continuam a acender o chip certo (3532).
    val highlighted = remember(tableMHz, currentMHz) { ClockTableCuration.nearest(currentMHz.toInt(), tableMHz) }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(title, color = theme.text, fontFamily = theme.fontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("${currentMHz.toInt()} MHz", color = theme.text.copy(alpha = 0.6f), fontFamily = theme.fontFamily, fontSize = 10.sp)
        }
        if (tableMHz.size > curated.size) {
            Text(
                if (showAll) (if (isEn) "Fewer" else "Menos") else (if (isEn) "All (${tableMHz.size})" else "Todos (${tableMHz.size})"),
                color = theme.primary,
                fontFamily = theme.fontFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(enabled = enabled) { showAll = !showAll }.padding(4.dp),
            )
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    // A linha rola sozinha até ao chip escolhido: num painel estreito (overlay) o 3072 MHz ficava
    // fora de vista e parecia que nenhum clock estava selecionado.
    val scroll = rememberScrollState()
    var selectedX by remember { mutableStateOf<Int?>(null) }
    val lead = with(LocalDensity.current) { 24.dp.roundToPx() }
    LaunchedEffect(selectedX, showAll) { selectedX?.let { scroll.animateScrollTo((it - lead).coerceAtLeast(0)) } }
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (mhz in visible) {
            val isOc = ocAboveMHz != null && mhz > ocAboveMHz
            PerfChip(
                label = if (isOc) "$mhz MHz OC" else "$mhz MHz",
                selected = highlighted == mhz,
                enabled = enabled,
                theme = theme,
                textColor = if (isOc && highlighted != mhz) theme.warning else null,
                modifier = if (highlighted == mhz) Modifier.onGloballyPositioned { selectedX = it.positionInParent().x.toInt() } else Modifier,
            ) { onSelect(mhz) }
        }
    }
}

/**
 * Cartão "Clocks manuais": Perf, Prime e GPU com chips das frequências REAIS do SoC (tabela do
 * kernel, ou a estática por SoC quando `source == "fallback"`), presets guardados pelo
 * utilizador e gravação. Sem perfis combinados: os únicos perfis que existem são os de TDP.
 */
@Composable
fun ManualClockCard(
    soc: SocFamily,
    enabled: Boolean,
    tables: ClockTables,
    perfMHz: Float,
    primeMHz: Float,
    gpuMHz: Float,
    effMHz: Float,
    customProfiles: List<CustomProfile>,
    theme: ConsoleTheme,
    isEn: Boolean,
    /** (perf, prime, gpu, eficiência) em MHz. */
    onClocks: (Float, Float, Float, Float) -> Unit,
    /** `null` = sem gravação aqui (os perfis vivem no cartão "Perfis guardados"). */
    onSavePreset: ((String, Float, Float, Float, Float) -> Unit)? = null,
    /** Fixo/livre por cluster e GPU (ver ClockPin); `null` = não mostrar. */
    pinMask: Int? = null,
    clusterKinds: List<com.dfdx047.odinhub.tools.hardware.ClusterKind> = emptyList(),
    onPinChange: (Int) -> Unit = {},
    gpuLive: com.dfdx047.odinhub.tools.hardware.GpuLiveInfo? = null,
    stockGpuMaxMHz: Int? = null,
    /** Pisos do modo de desempenho do firmware (MHz por cluster): o teto não desce abaixo deles. */
    firmwareFloorsMHz: Map<com.dfdx047.odinhub.tools.hardware.ClusterKind, Int> = emptyMap(),
) {
    var presetName by remember { mutableStateOf("") }
    val sourceHint = when {
        tables.isEmpty -> if (isEn) "Reading the SoC frequency tables…" else "A ler as tabelas de frequências do SoC…"
        tables.source == ClockTables.SOURCE_FALLBACK -> if (isEn) "Static table (kernel OPP table not readable)" else "Tabela estática (tabela OPP do kernel ilegível)"
        else -> if (isEn) "Real values from the kernel OPP table" else "Valores reais da tabela OPP do kernel"
    }

    ConsoleCard(
        if (isEn) "Manual Clocks" else "Clocks Manuais",
        if (isEn) "Cap or pin exact frequencies -- GPU is independent" else "Limitar ou fixar frequências exatas -- a GPU é independente",
        theme,
        enabled = enabled,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (!enabled) {
                DisabledModeHint(if (isEn) "Disabled while TDP mode is active" else "Desativado enquanto o modo TDP está ativo", theme)
            }
            Text(sourceHint, color = theme.text.copy(alpha = 0.5f), fontFamily = theme.fontFamily, fontSize = 10.sp)
            if (firmwareFloorsMHz.isNotEmpty()) {
                val floors = firmwareFloorsMHz.entries.sortedBy { it.key.ordinal }
                // Aviso só quando o piso está mesmo a impedir o teto escolhido; senão, informação.
                val blocked = floors.filter { (k, v) ->
                    val cap = when (k) {
                        com.dfdx047.odinhub.tools.hardware.ClusterKind.EFFICIENCY -> effMHz
                        com.dfdx047.odinhub.tools.hardware.ClusterKind.PERF -> perfMHz
                        com.dfdx047.odinhub.tools.hardware.ClusterKind.PRIME -> primeMHz
                    }
                    cap > 0f && cap.toInt() < v
                }
                Text(
                    (if (isEn) "Firmware performance mode floors: " else "Pisos do modo de desempenho do firmware: ") +
                        floors.joinToString(" · ") { (k, v) -> "${clusterLabel(k, isEn)} $v MHz" },
                    color = theme.text.copy(alpha = 0.5f), fontFamily = theme.fontFamily, fontSize = 10.sp,
                )
                if (blocked.isNotEmpty()) {
                    WarningNote(
                        blocked.joinToString("\n") { (k, v) ->
                            if (isEn) "${clusterLabel(k, isEn)}: the cap is below the firmware floor, so it stays at $v MHz."
                            else "${clusterLabel(k, isEn)}: o teto está abaixo do piso do firmware, por isso fica em $v MHz."
                        },
                        theme,
                    )
                }
            }

            val userClockProfiles = customProfiles.filter { it.type == "CLOCK" }
            if (userClockProfiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (prof in userClockProfiles) {
                        PerfChip(
                            label = prof.name,
                            selected = perfMHz == prof.v2 && primeMHz == prof.v3 && gpuMHz == prof.v4 && (prof.v5 <= 0f || effMHz == prof.v5),
                            enabled = enabled,
                            theme = theme,
                        ) { onClocks(prof.v2, prof.v3, prof.v4, if (prof.v5 > 0f) prof.v5 else effMHz) }
                    }
                }
            }

            // Cluster de eficiência: só nos SoCs com três clusters (8 Gen 2, 865).
            if (tables.hasEfficiencyCluster) {
                Spacer(modifier = Modifier.height(16.dp))
                ClockChipRow(
                    title = (if (isEn) "Efficiency Cluster" else "Cluster Eficiência") + soc.effCores.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(),
                    currentMHz = if (effMHz > 0f) effMHz else (tables.effMHz.lastOrNull()?.toFloat() ?: 0f),
                    tableMHz = tables.effMHz,
                    enabled = enabled,
                    theme = theme,
                    isEn = isEn,
                ) { onClocks(perfMHz, primeMHz, gpuMHz, it.toFloat()) }
            }

            Spacer(modifier = Modifier.height(16.dp))
            ClockChipRow(
                title = (if (isEn) "Perf Cluster" else "Cluster Perf") + soc.perfCores.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(),
                currentMHz = perfMHz,
                tableMHz = tables.perfMHz,
                enabled = enabled,
                theme = theme,
                isEn = isEn,
            ) { onClocks(it.toFloat(), primeMHz, gpuMHz, effMHz) }

            Spacer(modifier = Modifier.height(16.dp))
            ClockChipRow(
                title = (if (isEn) "Prime Cluster" else "Cluster Prime") + soc.primeCores.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(),
                currentMHz = primeMHz,
                tableMHz = tables.primeMHz,
                enabled = enabled,
                theme = theme,
                isEn = isEn,
            ) { onClocks(perfMHz, it.toFloat(), gpuMHz, effMHz) }

            Spacer(modifier = Modifier.height(16.dp))
            ClockChipRow(
                title = soc.gpuName + (if (isEn) " (independent)" else " (independente)"),
                currentMHz = gpuMHz,
                tableMHz = tables.gpuMHz,
                enabled = enabled,
                theme = theme,
                isEn = isEn,
                ocAboveMHz = stockGpuMaxMHz,
            ) { onClocks(perfMHz, primeMHz, it.toFloat(), effMHz) }
            if (gpuLive != null) {
                Spacer(modifier = Modifier.height(6.dp))
                GpuStatusLine(gpuLive, stockGpuMaxMHz, theme, isEn)
            }

            if (pinMask != null) {
                Spacer(modifier = Modifier.height(16.dp))
                ClockPinRow(pinMask, clusterKinds, hasGpu = true, theme = theme, isEn = isEn, enabled = enabled, onChange = onPinChange)
            }

            if (onSavePreset != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = presetName, onValueChange = { presetName = it }, label = { Text(if (isEn) "Preset Name" else "Nome do Preset Clocks", fontSize = 10.sp) }, modifier = Modifier.weight(1f).height(50.dp), textStyle = TextStyle(fontSize = 12.sp, color = theme.text), enabled = enabled)
                Button(
                    onClick = { if (presetName.isNotBlank() && enabled) { onSavePreset(presetName, perfMHz, primeMHz, gpuMHz, effMHz); presetName = "" } },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
                    modifier = Modifier.height(50.dp),
                    enabled = enabled,
                ) { Text(if (isEn) "Save" else "Salvar", fontSize = 11.sp, color = theme.onPrimary) }
            }
            }
        }
    }
}

/**
 * Cartão "Limite Térmico" (escrito via PServerBinder, que já corre como root): Stock / 85 / 90 / 95 °C / Unthrottled, com confirmação para o
 * Unthrottled, toggle de desativar a política das zonas, e a lista das zonas detetadas.
 */
@Composable
fun ThermalLimitCard(
    status: ThermalStatus,
    theme: ConsoleTheme,
    isEn: Boolean,
    onSelectMode: (String) -> Unit,
    onDisableZoneMode: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    val enabled = status.pServerAvailable && status.supported
    var showUnthrottledDialog by remember { mutableStateOf(false) }
    var showZones by remember { mutableStateOf(false) }
    var showZoneModeDialog by remember { mutableStateOf(false) }

    if (showZoneModeDialog) {
        AlertDialog(
            onDismissRequest = { showZoneModeDialog = false },
            containerColor = theme.surface,
            title = { Text(if (isEn) "Disable zone throttling?" else "Desativar o throttling das zonas?", color = theme.text, fontFamily = theme.fontFamily, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (isEn) "The kernel stops reacting to these zones' temperature entirely. Only the hardware cut-off remains. High risk -- keep the fan at maximum."
                    else "O kernel deixa de reagir à temperatura destas zonas. Só resta o corte de hardware. Risco elevado -- mantenha a ventoinha no máximo.",
                    color = theme.text.copy(alpha = 0.8f), fontFamily = theme.fontFamily, fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { showZoneModeDialog = false; onDisableZoneMode(true) }) {
                    Text(if (isEn) "I understand, apply" else "Entendi, aplicar", color = Color(0xFFFF5252), fontFamily = theme.fontFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { showZoneModeDialog = false }) {
                    Text(if (isEn) "Cancel" else "Cancelar", color = theme.primary, fontFamily = theme.fontFamily)
                }
            },
        )
    }

    if (showUnthrottledDialog) {
        AlertDialog(
            onDismissRequest = { showUnthrottledDialog = false },
            containerColor = theme.surface,
            title = { Text(if (isEn) "Disable thermal throttling?" else "Desativar o throttling térmico?", color = theme.text, fontFamily = theme.fontFamily, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (isEn) {
                        "This raises the first-stage passive trip points to 115 °C (emergency trips are never touched). The SoC will keep full clocks until the hardware " +
                            "cut-off, which can cause overheating, battery wear or permanent damage. Only use it with the fan at maximum and at your own risk."
                    } else {
                        "Isto sobe os trip points passivos de primeira linha para 115 °C (os de emergência nunca são tocados). O SoC mantém os clocks a fundo até ao corte de " +
                            "hardware, o que pode causar sobreaquecimento, desgaste da bateria ou danos permanentes. Use só com a ventoinha no máximo e por sua conta e risco."
                    },
                    color = theme.text.copy(alpha = 0.8f),
                    fontFamily = theme.fontFamily,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { showUnthrottledDialog = false; onSelectMode(ThermalLimitModes.UNTHROTTLED) }) {
                    Text(if (isEn) "I understand, apply" else "Entendi, aplicar", color = Color(0xFFFF5252), fontFamily = theme.fontFamily)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnthrottledDialog = false }) {
                    Text(if (isEn) "Cancel" else "Cancelar", color = theme.primary, fontFamily = theme.fontFamily)
                }
            },
        )
    }

    ConsoleCard(
        if (isEn) "Thermal Limit" else "Limite Térmico",
        if (isEn) "Temperature at which the kernel starts throttling CPU/GPU" else "Temperatura a partir da qual o kernel estrangula CPU/GPU",
        theme,
        enabled = enabled,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (!status.supported) {
                DisabledModeHint(
                    if (isEn) "Not available on this device: MediaTek throttling is done by the vendor thermal service, not by kernel trip points"
                    else "Indisponível neste aparelho: no MediaTek o throttling é feito pelo serviço térmico do fabricante, não pelos trip points do kernel",
                    theme,
                )
            } else if (!enabled) {
                DisabledModeHint(if (isEn) "Requires PServerBinder (stock Retroid firmware)" else "Requer o PServerBinder (firmware Retroid de fábrica)", theme)
            }
            if (status.fromAppOverride) {
                val active = when (status.activeMode) {
                    ThermalLimitModes.STOCK -> "Stock"
                    ThermalLimitModes.UNTHROTTLED -> if (isEn) "Unthrottled" else "Sem limite"
                    else -> "${status.activeMode} °C"
                }
                Text(
                    (if (isEn) "Per-game rule active: " else "Regra do jogo ativa: ") + active,
                    color = theme.primary, fontFamily = theme.fontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Text(
                if (isEn) "Changes the kernel thresholds only; the vendor thermal service can still limit clocks on its own."
                else "Altera só os limiares do kernel; o serviço térmico do fabricante pode continuar a limitar os clocks por conta própria.",
                color = theme.text.copy(alpha = 0.55f), fontFamily = theme.fontFamily, fontSize = 10.sp, modifier = Modifier.padding(bottom = 8.dp),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (mode in ThermalLimitModes.all) {
                    val label = when (mode) {
                        ThermalLimitModes.STOCK -> "Stock"
                        ThermalLimitModes.UNTHROTTLED -> if (isEn) "Unthrottled" else "Sem limite"
                        else -> "$mode °C"
                    }
                    PerfChip(
                        label = label,
                        selected = status.mode == mode,
                        enabled = enabled,
                        theme = theme,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (mode == ThermalLimitModes.UNTHROTTLED) showUnthrottledDialog = true else onSelectMode(mode)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (isEn) "Also disable zone throttling policy" else "Também desativar a política de throttling das zonas", color = theme.text, fontFamily = theme.fontFamily, fontSize = 12.sp)
                    Text(if (isEn) "Writes \"disabled\" to each zone's mode node" else "Escreve \"disabled\" no nó mode de cada zona", color = theme.text.copy(alpha = 0.5f), fontFamily = theme.fontFamily, fontSize = 10.sp)
                }
                ConsoleToggle(checked = status.disableZoneMode, theme = theme, onCheckedChange = { if (enabled) { if (it) showZoneModeDialog = true else onDisableZoneMode(false) } })
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (isEn) "Detected zones: " else "Zonas detetadas: ") + "${status.zoneCount}" +
                        (if (isEn) " (${status.tripCount} passive trips)" else " (${status.tripCount} trips passivos)"),
                    color = theme.text.copy(alpha = 0.7f),
                    fontFamily = theme.fontFamily,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f).clickable { showZones = !showZones },
                )
                Text(if (isEn) "Refresh" else "Atualizar", color = theme.primary, fontFamily = theme.fontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onRefresh() }.padding(4.dp))
            }
            if (showZones) {
                Text(
                    if (status.zoneNames.isEmpty()) {
                        if (isEn) "No CPU/GPU thermal zones found" else "Nenhuma zona térmica de CPU/GPU encontrada"
                    } else {
                        status.zoneNames.joinToString(", ")
                    },
                    color = theme.text.copy(alpha = 0.5f),
                    fontFamily = theme.fontFamily,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            status.lastError?.let { code ->
                val message = when (code) {
                    ThermalManager.ERR_WRITES_DISABLED -> if (isEn) "Thermal writes are disabled in this build" else "As escritas térmicas estão desativadas nesta build"
                    ThermalManager.ERR_NO_PSERVER -> if (isEn) "PServerBinder not available" else "PServerBinder indisponível"
                    ThermalManager.ERR_NO_ZONES -> if (isEn) "No CPU/GPU thermal zones found" else "Nenhuma zona térmica de CPU/GPU encontrada"
                    ThermalManager.ERR_WRITE_FAILED -> if (isEn) "Could not apply to: " else "Não foi possível aplicar em: "
                    ThermalManager.ERR_DISCOVER_FAILED -> if (isEn) "Zone discovery failed: " else "Falha na deteção de zonas: "
                    else -> code
                }
                Text(message + status.lastErrorDetail.orEmpty(), color = theme.danger, fontFamily = theme.fontFamily, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
