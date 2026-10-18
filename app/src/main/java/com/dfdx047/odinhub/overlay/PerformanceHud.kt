package com.dfdx047.odinhub.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.getSystemService
import com.dfdx047.odinhub.data.SharedPrefsRepo
import com.dfdx047.odinhub.models.HudConfig
import com.dfdx047.odinhub.models.HudLayout
import com.dfdx047.odinhub.models.HudMetric
import com.dfdx047.odinhub.models.HudPosition
import com.dfdx047.odinhub.models.HudSnapshot
import com.dfdx047.odinhub.models.TdpProfiles
import com.dfdx047.odinhub.ui.theme.AvailableThemes
import com.dfdx047.odinhub.ui.theme.ConsoleTheme
import com.dfdx047.odinhub.ui.theme.getResolvedTheme
import com.dfdx047.odinhub.ui.theme.glass
import com.dfdx047.odinhub.ui.theme.glassEdgeBottom
import com.dfdx047.odinhub.ui.theme.glassEdgeTop
import com.dfdx047.odinhub.ui.theme.isLight
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Janela do HUD de desempenho: pequena, sem foco (nunca rouba o comando ao jogo), por cima de
 * qualquer app. Arrastar move-a (a posição fica guardada); DOIS TOQUES esconde-a.
 */
class PerformanceHud(
    private val context: Context,
    private val prefs: SharedPrefsRepo,
    private val snapshot: StateFlow<HudSnapshot>,
    private val onDoubleTap: () -> Unit,
) {
    private val windowManager = context.getSystemService<WindowManager>()
    private val main = Handler(Looper.getMainLooper())
    private var host: OverlayViewHost? = null
    private var params: WindowManager.LayoutParams? = null
    private var shownConfig: HudConfig? = null

    private val density: Float get() = context.resources.displayMetrics.density

    /** Mostra (ou reposiciona) o HUD com esta configuração. */
    fun show(config: HudConfig) {
        main.post {
            if (!Settings.canDrawOverlays(context)) return@post
            val wm = windowManager ?: return@post
            val existing = host
            if (existing != null) {
                // Mudou só o canto/disposição: recalcula a posição sem recriar a janela.
                if (shownConfig?.position != config.position) {
                    prefs.hudOffsetX = -1
                    prefs.hudOffsetY = -1
                }
                shownConfig = config
                params?.let { lp ->
                    applyPosition(lp, config.position)
                    runCatching { wm.updateViewLayout(existing.composeView, lp) }
                }
                return@post
            }
            val newHost = OverlayViewHost(context)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).also { applyPosition(it, config.position) }

            newHost.setContent {
                val cfg by prefs.hudConfigFlow.collectAsState()
                val snap by snapshot.collectAsState()
                val appearance by prefs.appearanceVersion.collectAsState()
                val isEn by prefs.isEnglishFlow.collectAsState()
                // Relê o tema quando a aparência muda na app (tema, AMOLED, vidro).
                val raw = remember(appearance) { AvailableThemes.getOrElse(prefs.selectedThemeIndex) { AvailableThemes[0] } }
                val theme = getResolvedTheme(raw, prefs.useAmoledBlack, prefs.useGlass)
                HudContent(
                    config = cfg,
                    snap = snap,
                    theme = theme,
                    isEn = isEn,
                    modifier = Modifier
                        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDoubleTap() }) }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragEnd = { params?.let { prefs.hudOffsetX = it.x; prefs.hudOffsetY = it.y } },
                            ) { change, drag ->
                                change.consume()
                                moveBy(drag.x, drag.y)
                            }
                        },
                )
            }
            try {
                wm.addView(newHost.composeView, lp)
                newHost.onResumed()
                host = newHost
                params = lp
                shownConfig = config
            } catch (e: Exception) {
                newHost.onDestroyed()
            }
        }
    }

    fun hide() {
        main.post {
            host?.let { h -> runCatching { windowManager?.removeView(h.composeView) }; h.onDestroyed() }
            host = null
            params = null
            shownConfig = null
        }
    }

    private fun applyPosition(lp: WindowManager.LayoutParams, position: HudPosition) {
        val margin = (8 * density).roundToInt()
        lp.gravity = when (position) {
            HudPosition.TOP_LEFT -> Gravity.TOP or Gravity.START
            HudPosition.TOP_CENTER -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            HudPosition.TOP_RIGHT -> Gravity.TOP or Gravity.END
            HudPosition.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
            HudPosition.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
        }
        lp.x = prefs.hudOffsetX.takeIf { it >= 0 } ?: if (position == HudPosition.TOP_CENTER) 0 else margin
        lp.y = prefs.hudOffsetY.takeIf { it >= 0 } ?: margin
    }

    /** Arrastar: com gravidade END/BOTTOM os eixos crescem para dentro do ecrã, daí o sinal. */
    private fun moveBy(dx: Float, dy: Float) {
        val lp = params ?: return
        val view = host?.composeView ?: return
        val horizontalEnd = (lp.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.END ||
            (lp.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.RIGHT
        val bottom = (lp.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM
        val centered = (lp.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL
        lp.x = if (centered) lp.x + dx.roundToInt() else (lp.x + if (horizontalEnd) -dx.roundToInt() else dx.roundToInt()).coerceAtLeast(0)
        lp.y = (lp.y + if (bottom) -dy.roundToInt() else dy.roundToInt()).coerceAtLeast(0)
        runCatching { windowManager?.updateViewLayout(view, lp) }
    }
}

// ====================================================================== conteúdo

/** Cor de um valor de temperatura/ocupação: normal, quente, crítico. */
private fun heatColor(value: Float?, warm: Float, hot: Float, normal: Color): Color = when {
    value == null -> normal
    value >= hot -> Color(0xFFFF5252)
    value >= warm -> Color(0xFFFFB74D)
    else -> normal
}

@Composable
fun HudContent(config: HudConfig, snap: HudSnapshot, theme: ConsoleTheme, isEn: Boolean, modifier: Modifier = Modifier) {
    val scale = config.textScale
    val labelSize = (9f * scale).sp
    val valueSize = (12f * scale).sp
    val shape = RoundedCornerShape(if (theme.glassMode) 14.dp else 8.dp)

    // Fundo: o do tema com a opacidade escolhida; no modo vidro, vidro com contorno de luz. O
    // texto usa sempre a cor de texto do tema, com o véu necessário para se ler por cima do jogo.
    val bg = if (theme.glassMode) theme.glass().copy(alpha = (0.25f + 0.5f * config.backgroundOpacity).coerceIn(0f, 1f))
    else theme.background.copy(alpha = config.backgroundOpacity)
    val border = if (theme.glassMode) Modifier.border(1.dp, Brush.verticalGradient(listOf(theme.glassEdgeTop, theme.glassEdgeBottom)), shape)
    else Modifier.border(1.dp, theme.primary.copy(alpha = 0.35f), shape)
    val textColor = theme.text
    val labelColor = theme.primary

    val items = buildItems(config, snap, isEn, textColor)

    val base = modifier
        .then(border)
        .background(bg, shape)
        .padding(horizontal = (8 * scale).dp, vertical = (5 * scale).dp)

    if (config.layout == HudLayout.HORIZONTAL) {
        Row(modifier = base, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((10 * scale).dp)) {
            items.forEach { item -> HudItem(item, labelColor, labelSize, valueSize, theme, inline = true) }
            if (config.has(HudMetric.FPS_GRAPH) && snap.frametimeHistory.size > 1) {
                FrametimeGraph(snap.frametimeHistory, theme, Modifier.width((70 * scale).dp).height((18 * scale).dp))
            }
        }
    } else {
        Column(modifier = base.widthIn(min = (120 * scale).dp), verticalArrangement = Arrangement.spacedBy((2 * scale).dp)) {
            items.forEach { item -> HudItem(item, labelColor, labelSize, valueSize, theme, inline = false) }
            if (config.has(HudMetric.FPS_GRAPH) && snap.frametimeHistory.size > 1) {
                FrametimeGraph(snap.frametimeHistory, theme, Modifier.width((140 * scale).dp).height((26 * scale).dp).padding(top = 2.dp))
            }
        }
    }
}

private data class HudItemData(val label: String, val values: List<Pair<String, Color>>)

private fun buildItems(cfg: HudConfig, s: HudSnapshot, isEn: Boolean, normal: Color): List<HudItemData> {
    val out = mutableListOf<HudItemData>()
    fun v(text: String, color: Color = normal) = text to color

    if (cfg.has(HudMetric.FPS) || cfg.has(HudMetric.FRAMETIME)) {
        val values = mutableListOf<Pair<String, Color>>()
        if (cfg.has(HudMetric.FPS)) values += v(s.fps?.roundToInt()?.toString() ?: "--")
        if (cfg.has(HudMetric.FRAMETIME)) values += v(s.frametimeMs?.let { "%.1f ms".format(Locale.US, it) } ?: "-- ms")
        out += HudItemData("FPS", values)
    }
    if (cfg.has(HudMetric.CPU_USAGE) || cfg.has(HudMetric.CPU_TEMP) || cfg.has(HudMetric.CPU_CLUSTERS)) {
        val values = mutableListOf<Pair<String, Color>>()
        if (cfg.has(HudMetric.CPU_USAGE)) values += v(s.cpuUsagePercent?.let { "$it%" } ?: "--%", heatColor(s.cpuUsagePercent?.toFloat(), 80f, 95f, normal))
        if (cfg.has(HudMetric.CPU_TEMP)) values += v(s.cpuTempC?.let { "${it.roundToInt()}°C" } ?: "--°C", heatColor(s.cpuTempC, 80f, 92f, normal))
        if (cfg.has(HudMetric.CPU_CLUSTERS) && s.clusterMHz.isNotEmpty()) values += v(s.clusterMHz.joinToString("/"))
        out += HudItemData("CPU", values)
    }
    if (cfg.has(HudMetric.CPU_CORES) && s.coreMHz.isNotEmpty()) {
        out += HudItemData(if (isEn) "CORES" else "NÚCLEOS", listOf(v(s.coreMHz.joinToString(" ") { if (it <= 0) "off" else it.toString() })))
    }
    if (cfg.has(HudMetric.GPU_LOAD) || cfg.has(HudMetric.GPU_TEMP) || cfg.has(HudMetric.GPU_FREQ)) {
        val values = mutableListOf<Pair<String, Color>>()
        if (cfg.has(HudMetric.GPU_LOAD)) values += v(s.gpuLoadPercent?.let { "$it%" } ?: "--%", heatColor(s.gpuLoadPercent?.toFloat(), 85f, 97f, normal))
        if (cfg.has(HudMetric.GPU_TEMP)) values += v(s.gpuTempC?.let { "${it.roundToInt()}°C" } ?: "--°C", heatColor(s.gpuTempC, 80f, 92f, normal))
        if (cfg.has(HudMetric.GPU_FREQ)) values += v(s.gpuMHz?.let { "$it MHz" } ?: "-- MHz")
        out += HudItemData("GPU", values)
    }
    if (cfg.has(HudMetric.RAM)) {
        val used = s.ramUsedMB
        val total = s.ramTotalMB
        out += HudItemData("RAM", listOf(v(if (used != null && total != null) "%.1f/%.0fG".format(Locale.US, used / 1024f, total / 1024f) else "--")))
    }
    if (cfg.has(HudMetric.POWER) || cfg.has(HudMetric.TDP)) {
        val values = mutableListOf<Pair<String, Color>>()
        if (cfg.has(HudMetric.POWER)) values += v(s.powerW?.let { (if (s.charging) "⚡" else "") + "%.1fW".format(Locale.US, it) } ?: "--W")
        if (cfg.has(HudMetric.TDP)) values += v(s.tdpTargetW?.let { "→" + TdpProfiles.formatWatts(it).replace(" ", "") } ?: "Stock")
        out += HudItemData(if (isEn) "PWR" else "CONS", values)
    }
    if (cfg.has(HudMetric.BATTERY) || cfg.has(HudMetric.BATTERY_TEMP)) {
        val values = mutableListOf<Pair<String, Color>>()
        if (cfg.has(HudMetric.BATTERY)) values += v(s.batteryPercent?.let { "$it%" } ?: "--%", if ((s.batteryPercent ?: 100) <= 15) Color(0xFFFF5252) else normal)
        if (cfg.has(HudMetric.BATTERY_TEMP)) values += v(s.batteryTempC?.let { "${it.roundToInt()}°C" } ?: "--°C", heatColor(s.batteryTempC, 42f, 48f, normal))
        out += HudItemData(if (isEn) "BAT" else "BAT", values)
    }
    if (cfg.has(HudMetric.CLOCK)) {
        out += HudItemData("", listOf(v(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))))
    }
    return out
}

@Composable
private fun HudItem(item: HudItemData, labelColor: Color, labelSize: TextUnit, valueSize: TextUnit, theme: ConsoleTheme, inline: Boolean) {
    if (inline) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (item.label.isNotEmpty()) Text(item.label, color = labelColor, fontSize = labelSize, fontWeight = FontWeight.Black, fontFamily = theme.fontFamily)
            item.values.forEach { (text, color) -> Text(text, color = color, fontSize = valueSize, fontWeight = FontWeight.Bold, fontFamily = theme.fontFamily, maxLines = 1) }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.label.isNotEmpty()) {
                Text(item.label, color = labelColor, fontSize = labelSize, fontWeight = FontWeight.Black, fontFamily = theme.fontFamily, modifier = Modifier.width((labelSize.value * 5).dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.values.forEach { (text, color) -> Text(text, color = color, fontSize = valueSize, fontWeight = FontWeight.Bold, fontFamily = theme.fontFamily, maxLines = 1) }
            }
        }
    }
}

/** Gráfico de frame time (ms) dos últimos segundos: linha na cor do tema, picos a vermelho. */
@Composable
private fun FrametimeGraph(history: List<Float>, theme: ConsoleTheme, modifier: Modifier) {
    val lineColor = theme.primary
    val gridColor = (if (theme.isLight) Color.Black else Color.White).copy(alpha = 0.12f)
    Canvas(modifier = modifier) {
        val maxMs = (history.maxOrNull() ?: 33f).coerceAtLeast(20f)
        val stepX = size.width / (history.size - 1).coerceAtLeast(1)
        drawLine(gridColor, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f)
        val path = Path()
        history.forEachIndexed { i, ms ->
            val x = i * stepX
            val y = size.height - (ms / maxMs) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 1.5.dp.toPx()))
        // Último ponto destacado: vermelho quando o último segundo foi mais lento que 30 FPS.
        val last = history.last()
        drawCircle(if (last > 33.4f) Color(0xFFFF5252) else lineColor, radius = 2.dp.toPx(), center = Offset(size.width, size.height - (last / maxMs) * size.height))
    }
}
