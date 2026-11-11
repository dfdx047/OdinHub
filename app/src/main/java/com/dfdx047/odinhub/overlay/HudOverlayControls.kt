package com.dfdx047.odinhub.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dfdx047.odinhub.data.SharedPrefsRepo
import com.dfdx047.odinhub.models.HudConfig
import com.dfdx047.odinhub.models.HudLayout
import com.dfdx047.odinhub.models.HudMetric
import com.dfdx047.odinhub.models.HudPosition
import com.dfdx047.odinhub.models.HudPresets
import com.dfdx047.odinhub.models.HudStyle
import com.dfdx047.odinhub.service.PerformanceHudService
import com.dfdx047.odinhub.ui.theme.ConsoleTheme
import com.dfdx047.odinhub.ui.theme.onPrimary

/**
 * Separador "HUD" do painel rápido: liga/desliga o HUD e escolhe, em tempo real, o que ele mostra
 * (cada métrica, predefinições, layout, estilo, posição, tamanho e fundo). Cada toque grava em
 * `prefs.hudConfig`; o [PerformanceHudService] observa o `hudConfigFlow` e redesenha na hora.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HudOverlayControls(theme: ConsoleTheme, isEn: Boolean, prefs: SharedPrefsRepo) {
    val context = LocalContext.current
    val cfg by prefs.hudConfigFlow.collectAsState()
    val hidden by PerformanceHudService.hiddenFlow.collectAsState()
    val hudOn = cfg.enabled && !hidden
    fun update(transform: (HudConfig) -> HudConfig) { prefs.hudConfig = transform(prefs.hudConfigFlow.value) }

    // ---- Ligar / desligar ----
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (hudOn) theme.primary.copy(alpha = 0.16f) else theme.surface.copy(alpha = 0.5f))
            .border(1.dp, if (hudOn) theme.primary.copy(alpha = 0.5f) else theme.text.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (hudOn) theme.primary else theme.text.copy(alpha = 0.3f)))
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(if (isEn) "Performance HUD" else "HUD de desempenho", color = theme.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                when {
                    hudOn -> (if (isEn) "On screen · " else "Na tela · ") + cfg.metrics.size + (if (isEn) " metrics" else " métricas")
                    cfg.enabled -> if (isEn) "Hidden (double-tapped)" else "Escondido (dois toques)"
                    else -> if (isEn) "Off" else "Desligado"
                },
                color = theme.text.copy(alpha = 0.6f), fontSize = 10.sp,
            )
        }
        Switch(
            checked = hudOn,
            onCheckedChange = { on ->
                if (on) {
                    // Liga (ou mostra de novo, se estava escondido com dois toques).
                    if (!cfg.enabled || hidden) PerformanceHudService.toggle(context, prefs)
                } else {
                    // Desligar de verdade: o serviço para e a amostragem (root) também.
                    PerformanceHudService.hiddenFlow.value = false
                    update { it.copy(enabled = false) }
                }
            },
            colors = SwitchDefaults.colors(checkedThumbColor = theme.primary, checkedTrackColor = theme.primary.copy(alpha = 0.4f)),
        )
    }

    // ---- Predefinições ----
    SectionLabel(if (isEn) "PRESETS" else "PREDEFINIÇÕES", theme)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        HudPresets.all.forEach { preset ->
            val selected = cfg.metrics == preset.metrics
            HudChip(preset.label(isEn), selected, theme, Modifier.weight(1f)) { update { it.copy(metrics = preset.metrics) } }
        }
    }

    // ---- Métricas, por grupo ----
    SectionLabel(if (isEn) "WHAT TO SHOW" else "O QUE MOSTRAR", theme)
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.surface.copy(alpha = 0.5f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HudPresets.groups.forEach { group ->
            Text(group.label(isEn), color = theme.primary.copy(alpha = 0.85f), fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                group.metrics.forEach { metric ->
                    val on = metric in cfg.metrics
                    HudChip(HudPresets.shortLabel(metric, isEn), on, theme) {
                        update { c -> c.copy(metrics = HudPresets.toggle(c.metrics, metric)) }
                    }
                }
            }
        }
    }

    // ---- Aparência ----
    SectionLabel(if (isEn) "LOOK" else "APARÊNCIA", theme)
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.surface.copy(alpha = 0.5f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HudLayout.entries.forEach { l -> HudChip(l.label(isEn), cfg.layout == l, theme, Modifier.weight(1f)) { update { it.copy(layout = l) } } }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HudStyle.entries.forEach { s -> HudChip(s.label(isEn), cfg.style == s, theme) { update { it.copy(style = s) } } }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HudPosition.entries.forEach { p -> HudChip(p.label(isEn), cfg.position == p, theme) { update { it.copy(position = p) } } }
        }
        Text((if (isEn) "Size: " else "Tamanho: ") + "${(cfg.textScale * 100).toInt()}%", color = theme.text, fontSize = 11.sp)
        Slider(
            value = cfg.textScale,
            onValueChange = { v -> update { it.copy(textScale = HudConfig.clampScale(v)) } },
            valueRange = HudConfig.MIN_SCALE..HudConfig.MAX_SCALE,
            colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary),
        )
        Text((if (isEn) "Background: " else "Fundo: ") + "${(cfg.backgroundOpacity * 100).toInt()}%", color = theme.text, fontSize = 11.sp)
        Slider(
            value = cfg.backgroundOpacity,
            onValueChange = { v -> update { it.copy(backgroundOpacity = (Math.round(v * 20f) / 20f).coerceIn(0f, 1f)) } },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary),
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        if (isEn) "Tip: drag the HUD to move it, pinch or drag its corner to resize, double-tap to hide."
        else "Dica: arraste o HUD para mover, use pinça ou o canto para redimensionar, dois toques para esconder.",
        color = theme.text.copy(alpha = 0.45f), fontSize = 9.sp,
    )
}

@Composable
private fun SectionLabel(text: String, theme: ConsoleTheme) {
    Spacer(modifier = Modifier.height(14.dp))
    Text(text, color = theme.text.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun HudChip(label: String, selected: Boolean, theme: ConsoleTheme, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(7.dp))
            .background(if (selected) theme.primary else Color.Transparent)
            .border(1.dp, if (selected) theme.primary else theme.text.copy(alpha = 0.15f), RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) theme.onPrimary else theme.text.copy(alpha = 0.85f), fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}
