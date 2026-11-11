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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dfdx047.odinhub.tools.hardware.ClockPin
import com.dfdx047.odinhub.tools.hardware.ClusterKind
import com.dfdx047.odinhub.tools.hardware.CoreState
import com.dfdx047.odinhub.tools.hardware.CpuCluster
import com.dfdx047.odinhub.tools.hardware.CpuHotplug
import com.dfdx047.odinhub.tools.hardware.GpuLiveInfo
import com.dfdx047.odinhub.tools.hardware.GpuOc
import com.dfdx047.odinhub.ui.theme.ConsoleTheme
import com.dfdx047.odinhub.ui.theme.danger
import com.dfdx047.odinhub.ui.theme.warning

/*
 * Componentes partilhados pelo ecrã de Desempenho, pelo editor por jogo e pelo overlay:
 *  - [CoreToggleGrid]: liga/desliga núcleos e clusters (com as regras do CpuHotplug);
 *  - [ClockPinRow]: fixo / livre por cluster e GPU;
 *  - [GpuStatusLine]: frequência atual, teto, máximo do kernel e temperatura da GPU.
 */

private val OnlineGreen = Color(0xFF4CD964)

/**
 * Aviso curto dentro de um cartão: barra lateral + texto na cor de aviso do tema ([warning]),
 * legível em temas claros e escuros. Ocupa a largura toda e quebra linha normalmente.
 */
@Composable
fun WarningNote(text: String, theme: ConsoleTheme, modifier: Modifier = Modifier, color: Color = theme.warning) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.10f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(28.dp).background(color))
        Text(
            text,
            color = color, fontFamily = theme.fontFamily, fontSize = 11.sp, lineHeight = 15.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

fun clusterLabel(kind: ClusterKind, isEn: Boolean): String = when (kind) {
    ClusterKind.EFFICIENCY -> if (isEn) "Efficiency" else "Eficiência"
    ClusterKind.PERF -> "Performance"
    ClusterKind.PRIME -> "Prime"
}

@Composable
private fun SmallChip(
    label: String,
    selected: Boolean,
    theme: ConsoleTheme,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) theme.primary else theme.text.copy(alpha = if (enabled) 0.10f else 0.05f))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 8.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) theme.background else theme.text.copy(alpha = if (enabled) 1f else 0.4f),
            fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        )
    }
}

/**
 * Grelha de núcleos por cluster. Cada núcleo é um botão (preenchido = ligado no pedido); um ponto
 * verde/cinzento mostra o estado REAL lido do sysfs ([realStates]). O cpu0 aparece sempre ligado e
 * não clicável. [mask] = núcleos desligados pedidos (bit N = cpuN).
 */
@Composable
fun CoreToggleGrid(
    clusters: List<CpuCluster>,
    possible: List<Int>,
    mask: Int,
    realStates: List<CoreState>,
    theme: ConsoleTheme,
    isEn: Boolean,
    enabled: Boolean = true,
    onMaskChange: (Int) -> Unit,
) {
    val cpus = possible.ifEmpty { (0..7).toList() }
    val groups = clusters.ifEmpty { listOf(CpuCluster(ClusterKind.PERF, cpus)) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        groups.forEach { cluster ->
            val clusterOff = CpuHotplug.isClusterOffline(mask, cluster)
            val canSwitchCluster = cluster.cpus.any { it != CpuHotplug.BOOT_CPU }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${clusterLabel(cluster.kind, isEn)} · ${cluster.cpus.first()}-${cluster.cpus.last()}",
                    color = theme.text, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f),
                )
                SmallChip(
                    label = if (clusterOff) (if (isEn) "Turn on" else "Ligar") else (if (isEn) "Turn off" else "Desligar"),
                    selected = clusterOff, theme = theme, enabled = enabled && canSwitchCluster,
                ) { onMaskChange(CpuHotplug.toggleCluster(mask, cluster, !clusterOff, cpus)) }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                cluster.cpus.forEach { cpu ->
                    val wantOn = !CpuHotplug.isOffline(mask, cpu)
                    val real = realStates.firstOrNull { it.cpu == cpu }?.online
                    val locked = cpu == CpuHotplug.BOOT_CPU
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (wantOn) theme.primary.copy(alpha = 0.85f) else theme.text.copy(alpha = 0.08f))
                            .clickable(enabled = enabled && !locked) { onMaskChange(CpuHotplug.toggleCpu(mask, cpu, wantOn, cpus)) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(6.dp).clip(CircleShape)
                                    .background(
                                        when (real) {
                                            true -> OnlineGreen
                                            false -> theme.text.copy(alpha = 0.3f)
                                            null -> Color.Transparent
                                        },
                                    ),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "CPU$cpu" + if (locked) " 🔒" else "",
                                color = if (wantOn) theme.background else theme.text.copy(alpha = 0.6f),
                                fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        Text(
            if (isEn) "● green = core really online (read from the kernel). CPU0 never turns off; all cores come back on in Stock, when you leave the game and at boot."
            else "● verde = núcleo ligado de verdade (lido do kernel). O CPU0 nunca desliga; tudo volta a ligar no Stock, ao sair do jogo e no arranque.",
            color = theme.text.copy(alpha = 0.6f), fontSize = 10.sp,
        )
    }
}

/**
 * Fixo / livre por cluster e GPU. Fixo = piso = teto (frequência constante); livre = o
 * governador decide abaixo do teto escolhido. [kinds] = clusters que existem neste aparelho.
 */
@Composable
fun ClockPinRow(
    pinMask: Int,
    kinds: List<ClusterKind>,
    hasGpu: Boolean,
    theme: ConsoleTheme,
    isEn: Boolean,
    enabled: Boolean = true,
    onChange: (Int) -> Unit,
) {
    val entries = buildList {
        if (ClusterKind.EFFICIENCY in kinds) add(ClockPin.EFF to "Eff")
        if (ClusterKind.PERF in kinds) add(ClockPin.PERF to "Perf")
        if (ClusterKind.PRIME in kinds) add(ClockPin.PRIME to "Prime")
        if (hasGpu) add(ClockPin.GPU to "GPU")
    }
    Column {
        Text(
            if (isEn) "Clock lock (on = fixed at the chosen clock, off = governor decides below it)"
            else "Fixar clock (ligado = fixo no clock escolhido, desligado = o governador decide abaixo dele)",
            color = theme.text.copy(alpha = 0.7f), fontSize = 11.sp,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            entries.forEach { (bit, label) ->
                val pinned = ClockPin.has(pinMask, bit)
                SmallChip(
                    label = "$label: " + if (pinned) (if (isEn) "Fixed" else "Fixo") else (if (isEn) "Free" else "Livre"),
                    selected = pinned, theme = theme, enabled = enabled, modifier = Modifier.weight(1f),
                ) { onChange(ClockPin.set(pinMask, bit, !pinned)) }
            }
        }
    }
}

/** Linha de estado da GPU: atual / teto / máximo do kernel e temperatura com aviso. */
@Composable
fun GpuStatusLine(live: GpuLiveInfo, stockMaxMHz: Int?, theme: ConsoleTheme, isEn: Boolean) {
    // Com histerese: perto do limite a leitura oscila 1 °C e o aviso aparecia e sumia.
    var lastHeat by remember { mutableStateOf(GpuOc.Heat.UNKNOWN) }
    val heat = GpuOc.heat(live.tempC, lastHeat).also { lastHeat = it }
    val tempColor = when (heat) {
        GpuOc.Heat.HOT -> theme.danger
        GpuOc.Heat.WARM -> theme.warning
        else -> theme.text.copy(alpha = 0.7f)
    }
    Column {
        val parts = buildList {
            live.curMHz?.let { add((if (isEn) "now " else "agora ") + "$it MHz") }
            live.capMHz?.let { add((if (isEn) "cap " else "teto ") + "$it MHz") }
            live.floorMHz?.let { add((if (isEn) "floor " else "piso ") + "$it MHz") }
            live.kernelMaxMHz?.let { k ->
                val oc = stockMaxMHz != null && k > stockMaxMHz
                add((if (isEn) "kernel max " else "máx. do kernel ") + "$k MHz" + if (oc) " (OC)" else "")
            }
        }
        if (parts.isNotEmpty()) Text("GPU: " + parts.joinToString(" · "), color = theme.text.copy(alpha = 0.7f), fontFamily = theme.fontFamily, fontSize = 11.sp)
        live.tempC?.let { t ->
            Text(
                "GPU %.0f °C".format(t) + when (heat) {
                    GpuOc.Heat.HOT -> if (isEn) " · too hot: lower the GPU clock or raise the fan" else " · muito quente: baixe o clock da GPU ou suba a ventoinha"
                    GpuOc.Heat.WARM -> if (isEn) " · warm" else " · quente"
                    else -> ""
                },
                color = tempColor, fontFamily = theme.fontFamily, fontSize = 11.sp, fontWeight = if (heat == GpuOc.Heat.HOT) FontWeight.Bold else FontWeight.Normal,
            )
        }
        if (GpuOc.floorIsOc(live.floorMHz, stockMaxMHz)) {
            WarningNote(
                if (isEn) "GPU floor stuck at ${live.floorMHz} MHz, even when idle: the Retroid firmware sets it by step index and with the OC table that step is above stock. The \"GPU sem piso fixo\" Magisk module (modules/rp5-gpu-floor) frees it."
                else "Piso da GPU preso em ${live.floorMHz} MHz, mesmo parada: o firmware da Retroid fixa-o pelo índice do degrau e, com a tabela OC, esse degrau fica acima do stock. O módulo Magisk \"GPU sem piso fixo\" (modules/rp5-gpu-floor) solta-o.",
                theme,
            )
        }
        if (stockMaxMHz != null && live.kernelMaxMHz != null && live.kernelMaxMHz <= stockMaxMHz) {
            Text(
                if (isEn) "This kernel has no GPU overclock steps (max $stockMaxMHz MHz = stock). OC steps only appear with a kernel that exposes them."
                else "Este kernel não tem degraus de overclock da GPU (máx. $stockMaxMHz MHz = stock). Os degraus OC só aparecem com um kernel que os exponha.",
                color = theme.text.copy(alpha = 0.5f), fontFamily = theme.fontFamily, fontSize = 10.sp,
            )
        }
    }
}
