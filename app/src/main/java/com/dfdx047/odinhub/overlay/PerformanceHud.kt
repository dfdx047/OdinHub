package com.dfdx047.odinhub.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import com.dfdx047.odinhub.models.HudStyle
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Janela do HUD de desempenho: pequena, sem foco (nunca rouba o comando ao jogo), por cima de
 * qualquer app.
 *  - Arrastar move-a. Usa as coordenadas ABSOLUTAS do ecrã (`rawX/rawY`): com as coordenadas da
 *    própria janela, que se mexe debaixo do dedo, o movimento tremia. As atualizações da janela
 *    são agrupadas por frame do ecrã (Choreographer), e a posição fica guardada ao largar.
 *  - Redimensionar em tempo real: pinça com dois dedos, ou arrastar a alça do canto inferior
 *    direito (aparece ao tocar no HUD). O tamanho fica guardado ao largar.
 *  - DOIS TOQUES esconde-a.
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

    /** Tamanho durante um gesto (null = o das definições). Estado Compose: redesenha ao vivo. */
    private val liveScale = mutableStateOf<Float?>(null)
    /** Alça de redimensionar visível (depois de um toque, durante uns segundos). */
    private val gripVisible = mutableStateOf(false)
    private val hideGrip = Runnable { gripVisible.value = false }

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
                    prefs.hudOffsetX = Int.MIN_VALUE
                    prefs.hudOffsetY = Int.MIN_VALUE
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
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    // Perto da borda o sistema estreitava a janela WRAP_CONTENT (e a barra mudava
                    // de forma a meio do arrasto). O arrasto já a mantém dentro do ecrã (moveTo).
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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
                val scale = liveScale.value ?: cfg.textScale
                // Área de toque mínima (invisível): num HUD pequenino a barra tem ~20 dp de altura
                // e, encostada a um canto, ficava quase impossível de voltar a apanhar.
                Box(modifier = Modifier.heightIn(min = MIN_TOUCH_DP.dp), contentAlignment = Alignment.CenterStart) {
                    HudContent(
                        config = if (scale == cfg.textScale) cfg else cfg.copy(textScale = scale),
                        snap = snap,
                        theme = theme,
                        isEn = isEn,
                        showGrip = gripVisible.value,
                        // BUG "acrescento uma métrica e ela fica cortada": a janela é WRAP_CONTENT,
                        // mas o ComposeView não volta a ser medido pela janela quando o conteúdo
                        // cresce. A cada mudança de tamanho (métricas ou redimensionar), nova medição.
                        // E a largura vai à janela em px exatos: com WRAP_CONTENT, o Android mede
                        // primeiro com a largura de um diálogo (~738 px no Odin 2 Mini) e o Compose
                        // aceita-a -- era isso que partia a barra em duas linhas. (Este tamanho é
                        // o do conteúdo inteiro, medido sem limite de largura dentro do HudContent.)
                        modifier = Modifier.onSizeChanged { resizeTo(it.width) },
                    )
                }
            }
            installTouch(newHost.composeView)
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
            liveScale.value = null
            gripVisible.value = false
            main.removeCallbacks(hideGrip)
        }
    }

    /** Janela com a largura exata do conteúdo (ver o comentário em show), sempre dentro do ecrã. */
    private fun resizeTo(contentWidthPx: Int) {
        main.post {
            val view = host?.composeView ?: return@post
            val lp = params ?: return@post
            val screenW = runCatching { windowManager?.currentWindowMetrics?.bounds?.width() }.getOrNull() ?: context.resources.displayMetrics.widthPixels
            val w = contentWidthPx.coerceIn(1, screenW)
            lp.width = w
            val centered = (lp.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL
            if (centered) lp.x = lp.x.coerceIn(-(screenW - w) / 2, (screenW - w) / 2)
            else lp.x = lp.x.coerceIn(0, (screenW - w).coerceAtLeast(0))
            view.requestLayout()
            runCatching { windowManager?.updateViewLayout(view, lp) }
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
        // Int.MIN_VALUE = sem posição arrastada (no centro o x pode ser negativo, por isso não -1).
        lp.x = prefs.hudOffsetX.takeIf { it != Int.MIN_VALUE } ?: if (position == HudPosition.TOP_CENTER) 0 else margin
        lp.y = prefs.hudOffsetY.takeIf { it != Int.MIN_VALUE } ?: margin
    }

    // ------------------------------------------------------------------ toque

    private enum class Mode { NONE, MOVE, RESIZE, PINCH }

    private var mode = Mode.NONE
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var startScale = 1f
    private var startW = 1
    private var startH = 1
    private var pinchScale = 1f
    private var moved = false
    private var targetX = 0
    private var targetY = 0
    private var framePending = false
    /** Limites do arrasto, calculados UMA vez no início do gesto (ver [moveTo]). */
    private var maxX = Int.MAX_VALUE
    private var maxY = Int.MAX_VALUE
    /** O segundo toque de um duplo toque: esconde ao LARGAR, e só se não houve arrasto. */
    private var secondTap = false

    private fun currentScale(): Float = liveScale.value ?: prefs.hudConfigFlow.value.textScale

    @SuppressLint("ClickableViewAccessibility")
    private fun installTouch(view: View) {
        val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            // BUG "ao mover, o HUD some": o onDoubleTap dispara no SEGUNDO DOWN. Tocar e logo a
            // seguir começar a arrastar escondia o HUD. Agora só esconde se esse toque largar parado.
            override fun onDoubleTapEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> secondTap = true
                    MotionEvent.ACTION_UP -> if (secondTap && !moved && mode == Mode.MOVE) onDoubleTap()
                }
                return true
            }
        })
        val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                mode = Mode.PINCH
                pinchScale = currentScale()
                return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinchScale = (pinchScale * detector.scaleFactor).coerceIn(HudConfig.MIN_SCALE, HudConfig.MAX_SCALE)
                liveScale.value = HudConfig.clampScale(pinchScale)
                return true
            }
        })
        view.setOnTouchListener { v, e ->
            pinch.onTouchEvent(e)
            if (mode != Mode.PINCH && mode != Mode.RESIZE) taps.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val lp = params
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = lp?.x ?: 0
                    startY = lp?.y ?: 0
                    targetX = startX
                    targetY = startY
                    startScale = currentScale()
                    startW = v.width.coerceAtLeast(1)
                    startH = v.height.coerceAtLeast(1)
                    moved = false
                    // secondTap NÃO se repõe aqui: o GestureDetector já viu este DOWN (acima).
                    computeBounds(v)
                    val grip = GRIP_DP * density
                    mode = if (gripVisible.value && e.x >= v.width - grip && e.y >= v.height - grip) Mode.RESIZE else Mode.MOVE
                    main.removeCallbacks(hideGrip)
                    gripVisible.value = true
                }
                MotionEvent.ACTION_POINTER_DOWN -> mode = Mode.PINCH
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    when (mode) {
                        Mode.MOVE -> if (moved || abs(dx) + abs(dy) > TOUCH_SLOP_DP * density) {
                            moved = true
                            moveTo(dx, dy)
                        }
                        Mode.RESIZE -> {
                            // O conteúdo cresce em proporção ao tamanho: largura e altura novas
                            // pedidas pelo dedo -> fator médio sobre o tamanho do início do gesto.
                            val factor = ((startW + dx) / startW + (startH + dy) / startH) / 2f
                            liveScale.value = HudConfig.clampScale(startScale * factor)
                        }
                        else -> Unit
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) params?.let { prefs.hudOffsetX = it.x; prefs.hudOffsetY = it.y }
                    liveScale.value?.let { s ->
                        val cfg = prefs.hudConfigFlow.value
                        if (s != cfg.textScale) prefs.hudConfig = cfg.copy(textScale = s)
                    }
                    liveScale.value = null
                    mode = Mode.NONE
                    secondTap = false
                    main.postDelayed(hideGrip, GRIP_VISIBLE_MS)
                }
            }
            true
        }
    }

    /** Arrastar: com gravidade END/BOTTOM os eixos crescem para dentro do ecrã, daí o sinal. */
    private fun moveTo(dx: Float, dy: Float) {
        val lp = params ?: return
        val view = host?.composeView ?: return
        val horizontalEnd = (lp.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.END ||
            (lp.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.RIGHT
        val bottom = (lp.gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM
        val centered = (lp.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL
        if (view.width <= 0) return
        targetX = if (centered) (startX + dx.roundToInt()).coerceIn(-maxX / 2, maxX / 2)
        else (startX + if (horizontalEnd) -dx.roundToInt() else dx.roundToInt()).coerceIn(0, maxX)
        targetY = (startY + if (bottom) -dy.roundToInt() else dy.roundToInt()).coerceIn(0, maxY)
        if (framePending) return
        framePending = true
        // Uma atualização da janela por frame do ecrã, com a posição mais recente do dedo.
        Choreographer.getInstance().postFrameCallback {
            framePending = false
            val p = params ?: return@postFrameCallback
            val v = host?.composeView ?: return@postFrameCallback
            if (p.x == targetX && p.y == targetY) return@postFrameCallback
            p.x = targetX
            p.y = targetY
            runCatching { windowManager?.updateViewLayout(v, p) }
        }
    }

    /**
     * BUG "ao mover, trava": o `currentWindowMetrics` era pedido a CADA evento de toque (~120 por
     * segundo no RP5). Ele calcula as insets com uma chamada síncrona ao WindowManager (binder),
     * na thread principal. Agora pede-se uma vez, no toque inicial.
     */
    private fun computeBounds(view: View) {
        val bounds = runCatching { windowManager?.currentWindowMetrics?.bounds }.getOrNull()
        val screenW = bounds?.width() ?: context.resources.displayMetrics.widthPixels
        val screenH = bounds?.height() ?: context.resources.displayMetrics.heightPixels
        maxX = (screenW - view.width).coerceAtLeast(0)
        maxY = (screenH - view.height).coerceAtLeast(0)
    }

    private companion object {
        /** Zona da alça de redimensionar (canto inferior direito). */
        const val GRIP_DP = 28f
        const val GRIP_VISIBLE_MS = 3_000L
        /** Abaixo disto um toque não é arrasto (os dois toques não mexem o HUD). */
        const val TOUCH_SLOP_DP = 4f
        /** Altura mínima da janela do HUD, para se conseguir sempre tocar-lhe. */
        const val MIN_TOUCH_DP = 40
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

/** Grupo de uma linha do HUD: cada estilo pode dar uma cor própria a cada grupo. */
private enum class Group { FPS, CPU, CORES, GPU, RAM, POWER, BATTERY, CLOCK, PROFILE }

/** Aparência resolvida de um [HudStyle]. */
private data class HudPalette(
    val background: Color,
    val border: Brush?,
    val text: Color,
    val label: (Group) -> Color,
    val graph: Color,
    val font: FontFamily?,
    val shadow: Shadow?,
    val cornerDp: Int,
)

private fun palette(style: HudStyle, theme: ConsoleTheme, opacity: Float): HudPalette = when (style) {
    HudStyle.APP -> HudPalette(
        // O fundo do tema com a opacidade escolhida; no modo vidro, vidro com contorno de luz.
        background = if (theme.glassMode) theme.glass().copy(alpha = (0.25f + 0.5f * opacity).coerceIn(0f, 1f)) else theme.background.copy(alpha = opacity),
        border = if (theme.glassMode) Brush.verticalGradient(listOf(theme.glassEdgeTop, theme.glassEdgeBottom)) else SolidColor(theme.primary.copy(alpha = 0.35f)),
        text = theme.text,
        label = { theme.primary },
        graph = theme.primary,
        font = theme.fontFamily,
        shadow = null,
        cornerDp = if (theme.glassMode) 14 else 8,
    )
    // Cores das predefinições do MangoHud (fps/engine, cpu, gpu, ram, bateria), fundo quase preto.
    HudStyle.MANGO -> HudPalette(
        background = Color(0xFF020202).copy(alpha = opacity * 0.7f),
        border = null,
        text = Color.White,
        label = { g ->
            when (g) {
                Group.FPS -> Color(0xFFEB5B5B)
                Group.CPU, Group.CORES -> Color(0xFF2E97CB)
                Group.GPU -> Color(0xFF2E9762)
                Group.RAM -> Color(0xFFC26693)
                Group.POWER, Group.BATTERY -> Color(0xFFFF9078)
                Group.CLOCK -> Color.White
                Group.PROFILE -> Color(0xFFE5C76B)
            }
        },
        graph = Color(0xFF00FF00),
        font = null,
        shadow = null,
        cornerDp = 4,
    )
    HudStyle.MINIMAL -> HudPalette(
        background = Color.Transparent,
        border = null,
        text = Color.White,
        label = { Color(0xFFFFE082) },
        graph = Color.White,
        font = null,
        shadow = Shadow(Color.Black, Offset(1.5f, 1.5f), blurRadius = 4f),
        cornerDp = 0,
    )
    HudStyle.NEON -> HudPalette(
        background = Color(0xFF0A0618).copy(alpha = opacity),
        border = Brush.horizontalGradient(listOf(Color(0xFFFF2BD6), Color(0xFF00F0FF))),
        text = Color(0xFF00F0FF),
        label = { Color(0xFFFF2BD6) },
        graph = Color(0xFF00F0FF),
        font = null,
        shadow = Shadow(Color(0xFF00F0FF).copy(alpha = 0.8f), Offset.Zero, blurRadius = 8f),
        cornerDp = 10,
    )
    HudStyle.TERMINAL -> HudPalette(
        background = Color.Black.copy(alpha = opacity),
        border = SolidColor(Color(0xFF33FF33).copy(alpha = 0.4f)),
        text = Color(0xFF33FF33),
        label = { Color(0xFF1FAA1F) },
        graph = Color(0xFF33FF33),
        font = FontFamily.Monospace,
        shadow = null,
        cornerDp = 0,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HudContent(config: HudConfig, snap: HudSnapshot, theme: ConsoleTheme, isEn: Boolean, modifier: Modifier = Modifier, showGrip: Boolean = false) {
    val scale = config.textScale
    val labelSize = (9f * scale).sp
    val valueSize = (12f * scale).sp
    val pal = palette(config.style, theme, config.backgroundOpacity)
    val shape = RoundedCornerShape(pal.cornerDp.dp)
    val items = buildItems(config, snap, isEn, pal.text)

    val gripColor = pal.label(Group.FPS)
    val base = modifier
        .then(if (pal.border != null) Modifier.border(1.dp, pal.border, shape) else Modifier)
        .background(pal.background, shape)
        // Alça de redimensionar: três riscos no canto inferior direito, só depois de tocar.
        .drawWithContent {
            drawContent()
            if (showGrip) {
                val step = 4.dp.toPx()
                for (i in 1..3) {
                    val d = step * i
                    drawLine(gripColor, Offset(size.width - d, size.height - 2f), Offset(size.width - 2f, size.height - d), strokeWidth = 1.5.dp.toPx())
                }
            }
        }
        .padding(horizontal = (8 * scale).dp, vertical = (5 * scale).dp)

    if (config.layout == HudLayout.HORIZONTAL) {
        // SEMPRE uma linha reta (como o MangoHud/RTSS em barra). Antes quebrava para uma segunda
        // linha quando passava da largura: perto da borda a janela ficava mais estreita, a barra
        // mudava de altura a meio do arrasto e o HUD "saltava". Com muitas métricas, encolhe-se
        // com a pinça ou o canto.
        Row(
            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).then(base),
            horizontalArrangement = Arrangement.spacedBy((10 * scale).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item -> HudItem(item, pal, labelSize, valueSize, inline = true) }
            if (config.has(HudMetric.FPS_GRAPH) && snap.frametimeHistory.size > 1) {
                FrametimeGraph(snap.frametimeHistory, pal.graph, theme, Modifier.width((90 * scale).dp).height((18 * scale).dp))
            }
        }
    } else {
        Column(modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).then(base).widthIn(min = (120 * scale).dp), verticalArrangement = Arrangement.spacedBy((2 * scale).dp)) {
            items.forEach { item -> HudItem(item, pal, labelSize, valueSize, inline = false) }
            if (config.has(HudMetric.FPS_GRAPH) && snap.frametimeHistory.size > 1) {
                FrametimeGraph(snap.frametimeHistory, pal.graph, theme, Modifier.width((150 * scale).dp).height((28 * scale).dp).padding(top = 2.dp))
            }
        }
    }
}

private data class HudItemData(val group: Group, val label: String, val values: List<Pair<String, Color>>)

private fun buildItems(cfg: HudConfig, s: HudSnapshot, isEn: Boolean, normal: Color): List<HudItemData> {
    val out = mutableListOf<HudItemData>()
    fun v(text: String, color: Color = normal) = text to color

    if (cfg.has(HudMetric.FPS) || cfg.has(HudMetric.FRAMETIME) || cfg.has(HudMetric.FPS_LOW)) {
        val values = mutableListOf<Pair<String, Color>>()
        if (cfg.has(HudMetric.FPS)) values += v(s.fps?.roundToInt()?.toString() ?: "--")
        if (cfg.has(HudMetric.FRAMETIME)) values += v(s.frametimeMs?.let { "%.1f ms".format(Locale.US, it) } ?: "-- ms")
        if (cfg.has(HudMetric.FPS_LOW)) values += v("1%: " + (s.fpsLow?.roundToInt()?.toString() ?: "--"))
        out += HudItemData(Group.FPS, "FPS", values)
    }
    if (cfg.has(HudMetric.CPU_USAGE) || cfg.has(HudMetric.CPU_TEMP) || cfg.has(HudMetric.CPU_CLUSTERS)) {
        val values = mutableListOf<Pair<String, Color>>()
        if (cfg.has(HudMetric.CPU_USAGE)) values += v(s.cpuUsagePercent?.let { "$it%" } ?: "--%", heatColor(s.cpuUsagePercent?.toFloat(), 80f, 95f, normal))
        if (cfg.has(HudMetric.CPU_TEMP)) values += v(s.cpuTempC?.let { "${it.roundToInt()}°C" } ?: "--°C", heatColor(s.cpuTempC, 80f, 92f, normal))
        if (cfg.has(HudMetric.CPU_CLUSTERS) && s.clusterMHz.isNotEmpty()) values += v(s.clusterMHz.joinToString("/"))
        out += HudItemData(Group.CPU, "CPU", values)
    }
    if (cfg.has(HudMetric.CPU_CORES) && s.coreMHz.isNotEmpty()) {
        out += HudItemData(Group.CORES, if (isEn) "CORES" else "NÚCLEOS", listOf(v(s.coreMHz.joinToString(" ") { if (it <= 0) "off" else it.toString() })))
    }
    if (cfg.has(HudMetric.GPU_LOAD) || cfg.has(HudMetric.GPU_TEMP) || cfg.has(HudMetric.GPU_FREQ)) {
        val values = mutableListOf<Pair<String, Color>>()
        if (cfg.has(HudMetric.GPU_LOAD)) values += v(s.gpuLoadPercent?.let { "$it%" } ?: "--%", heatColor(s.gpuLoadPercent?.toFloat(), 85f, 97f, normal))
        if (cfg.has(HudMetric.GPU_TEMP)) values += v(s.gpuTempC?.let { "${it.roundToInt()}°C" } ?: "--°C", heatColor(s.gpuTempC, 80f, 92f, normal))
        if (cfg.has(HudMetric.GPU_FREQ)) values += v(s.gpuMHz?.let { "$it MHz" } ?: "-- MHz")
        out += HudItemData(Group.GPU, "GPU", values)
    }
    if (cfg.has(HudMetric.RAM)) {
        val used = s.ramUsedMB
        val total = s.ramTotalMB
        out += HudItemData(Group.RAM, "RAM", listOf(v(if (used != null && total != null) "%.1f/%.0fG".format(Locale.US, used / 1024f, total / 1024f) else "--")))
    }
    if (cfg.has(HudMetric.POWER) || cfg.has(HudMetric.TDP)) {
        val values = mutableListOf<Pair<String, Color>>()
        if (cfg.has(HudMetric.POWER)) values += v(s.powerW?.let { (if (s.charging) "⚡" else "") + "%.1fW".format(Locale.US, it) } ?: "--W")
        if (cfg.has(HudMetric.TDP)) values += v(s.tdpTargetW?.let { "→" + TdpProfiles.formatWatts(it).replace(" ", "") } ?: "Stock")
        out += HudItemData(Group.POWER, if (isEn) "PWR" else "CONS", values)
    }
    if (cfg.has(HudMetric.BATTERY) || cfg.has(HudMetric.BATTERY_TEMP)) {
        val values = mutableListOf<Pair<String, Color>>()
        if (cfg.has(HudMetric.BATTERY)) values += v(s.batteryPercent?.let { "$it%" } ?: "--%", if ((s.batteryPercent ?: 100) <= 15) Color(0xFFFF5252) else normal)
        if (cfg.has(HudMetric.BATTERY_TEMP)) values += v(s.batteryTempC?.let { "${it.roundToInt()}°C" } ?: "--°C", heatColor(s.batteryTempC, 42f, 48f, normal))
        out += HudItemData(Group.BATTERY, "BAT", values)
    }
    if (cfg.has(HudMetric.PROFILE) && s.profileName != null) {
        val name = s.profileName.ifEmpty { if (s.profileFromGame) (if (isEn) "Game" else "Jogo") else "Manual" }
        out += HudItemData(Group.PROFILE, if (isEn) "PROFILE" else "PERFIL", listOf(v(name)))
    }
    if (cfg.has(HudMetric.CLOCK)) {
        out += HudItemData(Group.CLOCK, "", listOf(v(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))))
    }
    return out
}

@Composable
private fun HudItem(item: HudItemData, pal: HudPalette, labelSize: TextUnit, valueSize: TextUnit, inline: Boolean) {
    val labelStyle = TextStyle(color = pal.label(item.group), fontSize = labelSize, fontWeight = FontWeight.Black, fontFamily = pal.font, shadow = pal.shadow)
    val valueStyle = TextStyle(fontSize = valueSize, fontWeight = FontWeight.Bold, fontFamily = pal.font, shadow = pal.shadow)
    if (inline) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (item.label.isNotEmpty()) Text(item.label, style = labelStyle, maxLines = 1, softWrap = false)
            item.values.forEach { (text, color) -> Text(text, style = valueStyle.copy(color = color), maxLines = 1, softWrap = false) }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.label.isNotEmpty()) Text(item.label, style = labelStyle, modifier = Modifier.width((labelSize.value * 5).dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.values.forEach { (text, color) -> Text(text, style = valueStyle.copy(color = color), maxLines = 1) }
            }
        }
    }
}

/**
 * Gráfico de frame time (ms): com a camada do jogo encontrada, um ponto por FRAME (como o
 * MangoHud); senão, a média de cada segundo. Linha de referência a 16,7 ms; picos a vermelho.
 */
@Composable
private fun FrametimeGraph(history: List<Float>, lineColor: Color, theme: ConsoleTheme, modifier: Modifier) {
    val gridColor = (if (theme.isLight) Color.Black else Color.White).copy(alpha = 0.15f)
    Canvas(modifier = modifier) {
        // Escala estável: pelo menos 33 ms, para um jogo a 60 FPS não parecer um sismógrafo.
        val maxMs = (history.maxOrNull() ?: 33.4f).coerceIn(33.4f, 200f)
        val stepX = size.width / (history.size - 1).coerceAtLeast(1)
        val ref = size.height - (16.7f / maxMs) * size.height
        drawLine(gridColor, Offset(0f, ref), Offset(size.width, ref), 1f)
        val path = Path()
        history.forEachIndexed { i, ms ->
            val x = i * stepX
            val y = size.height - (ms.coerceAtMost(maxMs) / maxMs) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(width = 1.5.dp.toPx()))
        // Picos (frame > 2x a mediana): marcados a vermelho.
        val median = history.sorted()[history.size / 2]
        history.forEachIndexed { i, ms ->
            if (ms > median * 2f && ms > 20f) drawCircle(Color(0xFFFF5252), radius = 1.6.dp.toPx(), center = Offset(i * stepX, size.height - (ms.coerceAtMost(maxMs) / maxMs) * size.height))
        }
    }
}
