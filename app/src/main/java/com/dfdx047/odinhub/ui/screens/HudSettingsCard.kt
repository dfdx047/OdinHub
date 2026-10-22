package com.dfdx047.odinhub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dfdx047.odinhub.models.HudConfig
import com.dfdx047.odinhub.models.HudLayout
import com.dfdx047.odinhub.models.HudMetric
import com.dfdx047.odinhub.models.HudPosition
import com.dfdx047.odinhub.models.HudSnapshot
import com.dfdx047.odinhub.overlay.HudContent
import com.dfdx047.odinhub.ui.theme.ConsoleTheme
import com.dfdx047.odinhub.ui.theme.onPrimary
import com.dfdx047.odinhub.ui.theme.secondaryText

/**
 * Definições do HUD de desempenho: ligar, barra horizontal / painel vertical, canto, métricas
 * (cada uma liga e desliga), tamanho do texto e opacidade -- com pré-visualização ao vivo no tema
 * atual (incluindo o modo vidro).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HudSettingsCard(
    config: HudConfig,
    theme: ConsoleTheme,
    isEn: Boolean,
    /** Lida no momento do toque (não na composição): ao voltar das definições já está atualizada. */
    hasOverlayPermission: () -> Boolean,
    onRequestPermission: () -> Unit,
    onChange: (HudConfig) -> Unit,
    onShowAgain: () -> Unit,
    /** Diagnóstico dos sensores: null = fechado, "" = a gerar, texto = relatório. */
    sensorReport: String? = null,
    onRunDiagnostics: () -> Unit = {},
    onCloseDiagnostics: () -> Unit = {},
) {
    if (sensorReport != null) SensorReportDialog(sensorReport, theme, isEn, onCloseDiagnostics)
    ConsoleCard(
        if (isEn) "Performance HUD" else "HUD de Desempenho",
        if (isEn) "FPS, temperatures, clocks and more over any app" else "FPS, temperaturas, frequências e mais sobre qualquer app",
        theme,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (isEn) "Enable HUD" else "Ativar HUD", color = theme.text, fontFamily = theme.fontFamily)
                    Text(
                        if (isEn) "Drag to move · double-tap to hide" else "Arraste para mover · dois toques para esconder",
                        color = theme.secondaryText, fontFamily = theme.fontFamily, fontSize = 10.sp,
                    )
                }
                ConsoleToggle(checked = config.enabled, theme = theme, onCheckedChange = {
                    if (it && !hasOverlayPermission()) onRequestPermission() else onChange(config.copy(enabled = it))
                })
            }

            if (config.enabled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (isEn) "Show again (if hidden with a double tap)" else "Mostrar de novo (se foi escondido com dois toques)",
                    color = theme.primary, fontFamily = theme.fontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onShowAgain() }.padding(vertical = 4.dp),
                )
                Text(
                    if (isEn) "Sensor diagnostics (temperature missing?)" else "Diagnóstico dos sensores (falta alguma temperatura?)",
                    color = theme.primary, fontFamily = theme.fontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onRunDiagnostics() }.padding(vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            SectionLabel(if (isEn) "LAYOUT" else "DISPOSIÇÃO", theme)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudLayout.entries.forEach { layout ->
                    HudChip(layout.label(isEn), config.layout == layout, theme, Modifier.weight(1f)) { onChange(config.copy(layout = layout)) }
                }
            }

            Spacer(Modifier.height(10.dp))
            SectionLabel(if (isEn) "POSITION" else "POSIÇÃO", theme)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HudPosition.entries.forEach { pos ->
                    HudChip(pos.label(isEn), config.position == pos, theme) { onChange(config.copy(position = pos)) }
                }
            }

            Spacer(Modifier.height(10.dp))
            SectionLabel(if (isEn) "WHAT TO SHOW" else "O QUE MOSTRAR", theme)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HudMetric.entries.forEach { metric ->
                    val on = config.has(metric)
                    HudChip(metric.label(isEn), on, theme) {
                        onChange(config.copy(metrics = if (on) config.metrics - metric else config.metrics + metric))
                    }
                }
            }
            Text(
                if (isEn) "FPS / frame time and CPU load are read through PServerBinder once per second; everything else is read directly."
                else "FPS / frame time e uso da CPU são lidos pelo PServerBinder uma vez por segundo; o resto é leitura direta.",
                color = theme.secondaryText, fontFamily = theme.fontFamily, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(10.dp))
            SectionLabel(if (isEn) "TEXT SIZE" else "TAMANHO DO TEXTO", theme)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HudConfig.TEXT_SCALES.forEachIndexed { i, scale ->
                    val label = when (i) { 0 -> if (isEn) "Small" else "Pequeno"; 1 -> if (isEn) "Medium" else "Médio"; else -> if (isEn) "Large" else "Grande" }
                    HudChip(label, config.textScale == scale, theme, Modifier.weight(1f)) { onChange(config.copy(textScale = scale)) }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text((if (isEn) "Background opacity: " else "Opacidade do fundo: ") + "${(config.backgroundOpacity * 100).toInt()}%", color = theme.text, fontFamily = theme.fontFamily, fontSize = 12.sp)
            Slider(
                value = config.backgroundOpacity,
                onValueChange = { onChange(config.copy(backgroundOpacity = it)) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary),
            )

            // Pré-visualização com valores de exemplo, no tema atual.
            SectionLabel(if (isEn) "PREVIEW" else "PRÉ-VISUALIZAÇÃO", theme)
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.background.copy(alpha = 0.6f)).padding(8.dp)) {
                HudContent(config = config, snap = PREVIEW, theme = theme, isEn = isEn)
            }
        }
    }
}

private val PREVIEW = HudSnapshot(
    fps = 60f, frametimeMs = 16.7f,
    frametimeHistory = listOf(16.7f, 16.6f, 17.1f, 16.7f, 22.4f, 16.7f, 16.5f, 16.8f, 16.7f, 16.7f),
    cpuUsagePercent = 38, cpuTempC = 61f, clusterMHz = listOf(1459, 2054, 2476), coreMHz = listOf(1459, 1459, 1459, 2054, 2054, 2054, 2054, 2476),
    gpuLoadPercent = 87, gpuTempC = 66f, gpuMHz = 615, ramUsedMB = 5120, ramTotalMB = 11800,
    powerW = 8.4f, batteryPercent = 76, batteryTempC = 36f, tdpTargetW = 9f,
)

@Composable
private fun SectionLabel(text: String, theme: ConsoleTheme) {
    Text(text, color = theme.secondaryText, fontFamily = theme.fontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun HudChip(label: String, selected: Boolean, theme: ConsoleTheme, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) theme.primary else theme.text.copy(alpha = 0.10f))
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) theme.onPrimary else theme.text, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, fontFamily = theme.fontFamily)
    }
}

/**
 * Lista todas as zonas térmicas do aparelho (tipo, leitura direta e por root) e quais o HUD usa,
 * com botões para copiar ou partilhar -- para descobrir num aparelho novo porque falta uma
 * temperatura sem precisar de ADB.
 */
@Composable
private fun SensorReportDialog(report: String, theme: ConsoleTheme, isEn: Boolean, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        containerColor = theme.surface,
        title = { Text(if (isEn) "Sensor diagnostics" else "Diagnóstico dos sensores", color = theme.text, fontFamily = theme.fontFamily) },
        text = {
            if (report.isEmpty()) {
                Text(if (isEn) "Reading sensors…" else "A ler os sensores…", color = theme.text)
            } else {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        report,
                        color = theme.text,
                        fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (report.isNotEmpty()) {
                    Text(
                        if (isEn) "COPY" else "COPIAR", color = theme.primary, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("Odin Hub sensors", report))
                            android.widget.Toast.makeText(context, if (isEn) "Copied" else "Copiado", android.widget.Toast.LENGTH_SHORT).show()
                        }.padding(8.dp),
                    )
                    Text(
                        if (isEn) "SHARE" else "PARTILHAR", color = theme.primary, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, report)
                            }
                            context.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.padding(8.dp),
                    )
                }
                Text(if (isEn) "CLOSE" else "FECHAR", color = theme.text, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onClose() }.padding(8.dp))
            }
        },
    )
}
