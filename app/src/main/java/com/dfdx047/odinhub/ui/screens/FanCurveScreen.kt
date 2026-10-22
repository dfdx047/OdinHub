package com.dfdx047.odinhub.ui.screens

import android.graphics.RuntimeShader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dfdx047.odinhub.models.FanCurveConfig
import com.dfdx047.odinhub.models.FanCurvePoint
import com.dfdx047.odinhub.models.FanReference
import com.dfdx047.odinhub.models.FanSpeedUnit
import com.dfdx047.odinhub.tools.hardware.CpuStressTest
import com.dfdx047.odinhub.tools.hardware.FanStatus
import com.dfdx047.odinhub.ui.theme.ConsoleTheme
import com.dfdx047.odinhub.ui.theme.glass
import com.dfdx047.odinhub.ui.theme.onPrimary
import com.dfdx047.odinhub.ui.theme.scrim
import com.dfdx047.odinhub.ui.theme.secondaryText
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Editor da curva da ventoinha, no espírito do ecrã da AYN: arrastar os pontos (ou escolher um e
 * afinar com −/+, para quem usa o D-pad), °C/°F, sensor de referência, estado atual, testes de
 * stress da CPU e da GPU, Guardar (liga o modo "Custom") e Repor.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FanCurveDialog(
    initial: FanCurveConfig,
    status: FanStatus,
    theme: ConsoleTheme,
    isEn: Boolean,
    onSave: (FanCurveConfig) -> Unit,
    onTest: (FanCurveConfig) -> Unit,
    onClose: () -> Unit,
) {
    var config by remember(initial) { mutableStateOf(initial) }
    var selected by remember { mutableIntStateOf(-1) }
    var gpuStress by remember { mutableStateOf(false) }
    val cpuStress by CpuStressTest.running.collectAsState()
    val dirty = config != initial

    DisposableEffect(Unit) { onDispose { CpuStressTest.stop() } }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(modifier = Modifier.fillMaxSize().background(theme.background)) {
            if (gpuStress) GpuStressBackground(onFinished = { gpuStress = false })
            BoxWithConstraints(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                val landscape = maxWidth > maxHeight
                Column(modifier = Modifier.fillMaxSize()) {
                    // ---- barra de topo
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (isEn) "Fan curve" else "Curva da ventoinha",
                            color = theme.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = theme.fontFamily,
                            modifier = Modifier.align(Alignment.CenterVertically).padding(end = 8.dp),
                        )
                        Pill("°C", !config.useFahrenheit, theme) { config = config.copy(useFahrenheit = false) }
                        Pill("°F", config.useFahrenheit, theme) { config = config.copy(useFahrenheit = true) }
                        Spacer(Modifier.width(8.dp))
                        Pill(if (cpuStress) (if (isEn) "Stop CPU test" else "Parar teste CPU") else (if (isEn) "CPU stress test" else "Teste de stress CPU"), cpuStress, theme) { CpuStressTest.toggle() }
                        Pill(if (gpuStress) (if (isEn) "Stop GPU test" else "Parar teste GPU") else (if (isEn) "GPU stress test" else "Teste de stress GPU"), gpuStress, theme) { gpuStress = !gpuStress }
                        Pill(if (isEn) "Save" else "Guardar", dirty, theme) { onSave(config) }
                        Pill(if (isEn) "Reset" else "Repor", false, theme) { config = config.copy(points = FanCurveConfig.DEFAULT_POINTS); selected = -1 }
                        Pill(if (isEn) "Close" else "Fechar", false, theme) { onClose() }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (landscape) {
                        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            CurveGraph(config, status, selected, theme, isEn, Modifier.weight(1f).fillMaxHeight(), onSelect = { selected = it }) { config = it }
                            SidePanel(config, status, selected, theme, isEn, Modifier.width(300.dp).fillMaxHeight(), { config = it }, { onTest(config) })
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            CurveGraph(config, status, selected, theme, isEn, Modifier.fillMaxWidth().height(320.dp), onSelect = { selected = it }) { config = it }
                            Spacer(Modifier.height(12.dp))
                            SidePanel(config, status, selected, theme, isEn, Modifier.fillMaxWidth(), { config = it }, { onTest(config) })
                        }
                    }
                }
            }
        }
    }
}

// ====================================================================== gráfico

private const val T_MIN = 20f
private const val T_MAX = 110f

@Composable
private fun CurveGraph(
    config: FanCurveConfig,
    status: FanStatus,
    selected: Int,
    theme: ConsoleTheme,
    isEn: Boolean,
    modifier: Modifier,
    onSelect: (Int) -> Unit,
    onChange: (FanCurveConfig) -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val current by rememberUpdatedState(config)
    val labelStyle = TextStyle(color = theme.secondaryText, fontSize = 11.sp, fontFamily = theme.fontFamily)
    val bubbleStyle = TextStyle(color = theme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = theme.fontFamily)
    val refTemp = when (config.reference) {
        FanReference.CPU -> status.cpuC ?: status.gpuC
        FanReference.GPU -> status.gpuC ?: status.cpuC
        FanReference.MAX -> listOfNotNull(status.cpuC, status.gpuC).maxOrNull()
    }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(theme.glass())
            .border(1.dp, theme.text.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .padding(8.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        val idx = nearestPoint(current, pos, size.width.toFloat(), size.height.toFloat(), density)
                        onSelect(idx)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            dragIndex = nearestPoint(current, pos, size.width.toFloat(), size.height.toFloat(), density)
                            dragPos = pos
                            if (dragIndex >= 0) onSelect(dragIndex)
                        },
                        onDragEnd = { dragIndex = -1 },
                        onDragCancel = { dragIndex = -1 },
                    ) { change, delta ->
                        if (dragIndex < 0) return@detectDragGestures
                        change.consume()
                        dragPos += delta
                        val g = GraphGeom(size.width.toFloat(), size.height.toFloat(), density)
                        onChange(movePoint(current, dragIndex, g.tempAt(dragPos.x), g.percentAt(dragPos.y)))
                    }
                },
        ) {
            val g = GraphGeom(size.width, size.height, density)
            val axis = theme.text.copy(alpha = 0.55f)
            val grid = theme.text.copy(alpha = 0.08f)
            // grelha e eixos
            for (p in 0..100 step 20) {
                val y = g.y(p.toFloat())
                drawLine(grid, Offset(g.left, y), Offset(g.right, y), 1f)
                val t = measurer.measure("$p%", labelStyle)
                drawText(t, topLeft = Offset(g.left - t.size.width - 6.dp.toPx(), y - t.size.height / 2))
            }
            var tick = 20
            while (tick <= 110) {
                val x = g.x(tick.toFloat())
                drawLine(grid, Offset(x, g.top), Offset(x, g.bottom), 1f)
                val label = if (config.useFahrenheit) "${FanCurveConfig.cToF(tick.toFloat()).roundToInt()}°F" else "$tick°C"
                val t = measurer.measure(label, labelStyle)
                drawText(t, topLeft = Offset(x - t.size.width / 2, g.bottom + 4.dp.toPx()))
                tick += 10
            }
            drawLine(axis, Offset(g.left, g.bottom), Offset(g.right, g.bottom), 2.dp.toPx())
            drawLine(axis, Offset(g.left, g.bottom), Offset(g.left, g.top), 2.dp.toPx())
            // zona crítica (>= 90 °C: sempre 100 %)
            drawRect(
                Color(0xFFFF5252).copy(alpha = 0.08f),
                topLeft = Offset(g.x(FanCurveConfig.CRITICAL_C.toFloat()), g.top),
                size = androidx.compose.ui.geometry.Size(g.right - g.x(FanCurveConfig.CRITICAL_C.toFloat()), g.bottom - g.top),
            )
            // curva
            val pts = config.sortedPoints
            val path = Path()
            pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(g.x(p.tempC.toFloat()), g.y(p.percent.toFloat())) else path.lineTo(g.x(p.tempC.toFloat()), g.y(p.percent.toFloat())) }
            pts.lastOrNull()?.let { path.lineTo(g.right, g.y(it.percent.toFloat())) }
            pts.firstOrNull()?.let { drawLine(theme.primary.copy(alpha = 0.6f), Offset(g.x(it.tempC.toFloat()), g.bottom), Offset(g.x(it.tempC.toFloat()), g.y(it.percent.toFloat())), 2.dp.toPx()) }
            drawPath(path, theme.primary, style = Stroke(width = 3.dp.toPx()))
            // pontos + balões com a %
            pts.forEachIndexed { i, p ->
                val c = Offset(g.x(p.tempC.toFloat()), g.y(p.percent.toFloat()))
                val r = if (i == selected) 14.dp.toPx() else 11.dp.toPx()
                drawCircle(theme.primary, r, c)
                if (i == selected) drawCircle(theme.text, r, c, style = Stroke(2.dp.toPx()))
                val t = measurer.measure("${p.percent}%", bubbleStyle)
                val pad = 4.dp.toPx()
                val tl = Offset(c.x - t.size.width / 2 - pad, c.y - r - t.size.height - 2 * pad - 4.dp.toPx())
                drawRoundRect(theme.primary, topLeft = tl, size = androidx.compose.ui.geometry.Size(t.size.width + 2 * pad, t.size.height + 2 * pad), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
                drawText(t, topLeft = Offset(tl.x + pad, tl.y + pad))
            }
            // temperatura atual sobre a curva (ponto laranja, como no ecrã da AYN)
            refTemp?.let { t ->
                val tc = t.coerceIn(T_MIN, T_MAX)
                drawCircle(Color(0xFFFFA726), 7.dp.toPx(), Offset(g.x(tc), g.y(config.percentFor(t).toFloat())))
            }
        }
        Text(
            if (isEn) "Fan speed" else "Velocidade",
            color = theme.secondaryText, fontSize = 11.sp, fontFamily = theme.fontFamily,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 4.dp),
        )
    }
}

/** Geometria do gráfico (margens para os rótulos dos eixos). */
private class GraphGeom(w: Float, h: Float, density: Float) {
    val left = 44f * density
    val right = w - 16f * density
    val top = 34f * density
    val bottom = h - 22f * density
    fun x(tempC: Float) = left + (tempC - T_MIN) / (T_MAX - T_MIN) * (right - left)
    fun y(percent: Float) = bottom - percent / 100f * (bottom - top)
    fun tempAt(px: Float) = T_MIN + (px - left) / (right - left) * (T_MAX - T_MIN)
    fun percentAt(py: Float) = (bottom - py) / (bottom - top) * 100f
}

private fun nearestPoint(cfg: FanCurveConfig, pos: Offset, w: Float, h: Float, density: Float): Int {
    val g = GraphGeom(w, h, density)
    val pts = cfg.sortedPoints
    var best = -1
    var bestD = 40f * density
    pts.forEachIndexed { i, p ->
        val d = hypot(g.x(p.tempC.toFloat()) - pos.x, g.y(p.percent.toFloat()) - pos.y)
        if (d < bestD) { bestD = d; best = i }
    }
    return best
}

/** Move o ponto [index] (da lista ORDENADA) sem passar os vizinhos. */
private fun movePoint(cfg: FanCurveConfig, index: Int, tempC: Float, percent: Float): FanCurveConfig {
    val pts = cfg.sortedPoints.toMutableList()
    if (index !in pts.indices) return cfg
    val lo = if (index > 0) pts[index - 1].tempC + 1 else FanCurveConfig.MIN_TEMP_C
    val hi = if (index < pts.lastIndex) pts[index + 1].tempC - 1 else FanCurveConfig.MAX_TEMP_C
    pts[index] = FanCurvePoint(tempC.roundToInt().coerceIn(lo, hi), percent.roundToInt().coerceIn(0, 100))
    return cfg.copy(points = pts)
}

// ====================================================================== painel lateral

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SidePanel(
    config: FanCurveConfig,
    status: FanStatus,
    selected: Int,
    theme: ConsoleTheme,
    isEn: Boolean,
    modifier: Modifier,
    onChange: (FanCurveConfig) -> Unit,
    onTest: () -> Unit,
) {
    fun temp(c: Float?): String = c?.let { if (config.useFahrenheit) "${FanCurveConfig.cToF(it).roundToInt()} °F" else "${it.roundToInt()} °C" } ?: "--"
    Column(modifier = modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Section(if (isEn) "Current status" else "Estado atual", theme) {
            Line(if (isEn) "CPU temperature" else "Temperatura da CPU", temp(status.cpuC), theme)
            Line(if (isEn) "GPU temperature" else "Temperatura da GPU", temp(status.gpuC), theme)
            val pct = status.targetPercent ?: status.fanSpeedRaw?.let { FanCurveConfig.percentFromFanSpeed(it, config.unit) }
            Line(if (isEn) "Fan speed" else "Velocidade", pct?.let { "$it% (${status.fanSpeedRaw ?: "?"})" } ?: "--", theme)
            Line(
                if (isEn) "Custom curve" else "Curva personalizada",
                if (status.curveActive) (if (isEn) "ON" else "LIGADA") else (if (isEn) "off — Save to turn it on" else "desligada — Guardar liga-a"),
                theme,
            )
        }

        if (selected in config.sortedPoints.indices) {
            val p = config.sortedPoints[selected]
            Section(if (isEn) "Selected point" else "Ponto selecionado", theme) {
                Stepper(if (config.useFahrenheit) "${FanCurveConfig.cToF(p.tempC.toFloat()).roundToInt()} °F" else "${p.tempC} °C", theme,
                    onMinus = { onChange(movePoint(config, selected, p.tempC - 1f, p.percent.toFloat())) },
                    onPlus = { onChange(movePoint(config, selected, p.tempC + 1f, p.percent.toFloat())) })
                Stepper("${p.percent} %", theme,
                    onMinus = { onChange(movePoint(config, selected, p.tempC.toFloat(), p.percent - 5f)) },
                    onPlus = { onChange(movePoint(config, selected, p.tempC.toFloat(), p.percent + 5f)) })
            }
        } else {
            Text(
                if (isEn) "Drag a point, or tap it to fine-tune with − / +." else "Arraste um ponto, ou toque nele para afinar com − / +.",
                color = theme.secondaryText, fontSize = 11.sp, fontFamily = theme.fontFamily,
            )
        }

        Section(if (isEn) "Thermal reference" else "Sensor de referência", theme) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FanReference.entries.forEach { ref -> Pill(ref.label(isEn), config.reference == ref, theme) { onChange(config.copy(reference = ref)) } }
            }
        }

        Section(if (isEn) "Smoothing" else "Suavização", theme) {
            Text(
                if (isEn) "Degrees the temperature must drop before the fan slows down." else "Graus que a temperatura tem de descer antes de a ventoinha abrandar.",
                color = theme.secondaryText, fontSize = 10.sp, fontFamily = theme.fontFamily,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 2, 3, 5).forEach { h -> Pill("$h °C", config.hysteresisC == h, theme) { onChange(config.copy(hysteresisC = h)) } }
            }
        }

        Section(if (isEn) "Advanced (firmware)" else "Avançado (firmware)", theme) {
            Text(
                if (isEn) "Odin Hub puts the fan in the firmware's manual-speed mode and adjusts it. Use Test: the fan must speed up for 5 s. If it doesn't, try another mode / unit."
                else "O Odin Hub põe a ventoinha no modo de velocidade manual do firmware e vai ajustando. Use Testar: a ventoinha tem de acelerar durante 5 s. Se não acelerar, experimente outro modo / unidade.",
                color = theme.secondaryText, fontSize = 10.sp, fontFamily = theme.fontFamily,
            )
            Text(if (isEn) "Manual mode (fan_mode)" else "Modo manual (fan_mode)", color = theme.text, fontSize = 11.sp, fontFamily = theme.fontFamily)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FanCurveConfig.MANUAL_MODE_OPTIONS.forEach { m -> Pill("$m", config.manualMode == m, theme) { onChange(config.copy(manualMode = m)) } }
            }
            Text(if (isEn) "Speed unit (fan_speed)" else "Unidade (fan_speed)", color = theme.text, fontSize = 11.sp, fontFamily = theme.fontFamily)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("ns (0–50000)", config.unit == FanSpeedUnit.NANOSECONDS, theme) { onChange(config.copy(unit = FanSpeedUnit.NANOSECONDS)) }
                Pill("% (0–100)", config.unit == FanSpeedUnit.PERCENT, theme) { onChange(config.copy(unit = FanSpeedUnit.PERCENT)) }
            }
            Pill(if (isEn) "Test: 100 % for 5 s" else "Testar: 100 % durante 5 s", false, theme) { onTest() }
            Text(
                if (isEn) "Safety: at ${FanCurveConfig.CRITICAL_C} °C or more the fan always runs at 100 %." else "Segurança: a partir de ${FanCurveConfig.CRITICAL_C} °C a ventoinha vai sempre a 100 %.",
                color = theme.secondaryText, fontSize = 10.sp, fontFamily = theme.fontFamily,
            )
        }
    }
}

@Composable
private fun Section(title: String, theme: ConsoleTheme, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(theme.glass())
            .border(1.dp, theme.text.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = theme.text, fontWeight = FontWeight.Bold, fontSize = 13.sp, fontFamily = theme.fontFamily)
        content()
    }
}

@Composable
private fun Line(label: String, value: String, theme: ConsoleTheme) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = theme.secondaryText, fontSize = 12.sp, fontFamily = theme.fontFamily)
        Text(value, color = theme.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = theme.fontFamily)
    }
}

@Composable
private fun Stepper(value: String, theme: ConsoleTheme, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        RoundButton("−", theme, onMinus)
        Text(value, color = theme.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = theme.fontFamily, modifier = Modifier.width(80.dp))
        RoundButton("+", theme, onPlus)
    }
}

@Composable
private fun RoundButton(label: String, theme: ConsoleTheme, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(36.dp).clip(CircleShape).background(theme.primary.copy(alpha = 0.85f)).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = theme.onPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Pill(label: String, selected: Boolean, theme: ConsoleTheme, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) theme.primary else theme.scrim)
            .border(1.dp, if (selected) theme.primary else theme.text.copy(alpha = 0.15f), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) theme.onPrimary else theme.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = theme.fontFamily)
    }
}

// ====================================================================== teste de stress da GPU

private const val GPU_STRESS_SHADER = """
uniform float2 res;
uniform float t;
half4 main(float2 p) {
    float2 uv = p / res;
    float v = 0.0;
    for (int i = 0; i < 128; i++) {
        float fi = float(i);
        v += sin(uv.x * fi * 0.37 + t + fi) * cos(uv.y * fi * 0.21 - t * 1.3);
    }
    float c = 0.5 + 0.5 * sin(v * 0.2 + t);
    return half4(c * 0.18, c * 0.10, c * 0.28, 1.0);
}
"""

/**
 * Fundo a ecrã inteiro com um shader AGSL pesado (128 iterações por píxel, a cada frame) -- põe a
 * GPU a 100 % durante 60 s, com o editor por cima para ver a curva a reagir.
 */
@Composable
private fun GpuStressBackground(onFinished: () -> Unit) {
    val shader = remember { RuntimeShader(GPU_STRESS_SHADER) }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            time = (now - start) / 1e9f
            if (time > 60f) { onFinished(); break }
        }
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        shader.setFloatUniform("res", size.width, size.height)
        shader.setFloatUniform("t", time)
        drawRect(ShaderBrush(shader))
    }
}
