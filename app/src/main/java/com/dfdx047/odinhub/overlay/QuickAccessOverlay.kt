package com.dfdx047.odinhub.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.getSystemService
import com.dfdx047.odinhub.data.AppOverrideRepository
import com.dfdx047.odinhub.data.SharedPrefsRepo
import com.dfdx047.odinhub.tools.ForegroundAppTracker
import com.dfdx047.odinhub.tools.hardware.LosslessManager
import com.dfdx047.odinhub.tools.hardware.DisplayManager
import com.dfdx047.odinhub.tools.hardware.PerformanceManager
import com.dfdx047.odinhub.tools.hardware.ThermalManager
import com.dfdx047.odinhub.ui.theme.AvailableThemes
import com.dfdx047.odinhub.ui.theme.getResolvedTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

class QuickAccessOverlay(
    private val context: Context,
    private val prefs: SharedPrefsRepo,
    // Injetado a partir do GamingOverlayService (Hilt), para partilhar o MESMO daemon de
    // hardware que o resto da app usa, em vez de o overlay criar o seu próprio motor paralelo.
    private val performanceManager: PerformanceManager,
    // Mesma fonte única de verdade (Room) usada pelo ForegroundAppWatcherService e pela aba
    // Performance -> Per-App Overrides, para que uma alteração feita aqui no overlay já
    // reflita em ambos, e vice-versa.
    private val overrideRepository: AppOverrideRepository,
    // Diz em que jogo estamos (e se o puxador deve estar visível). É o que permite à composição
    // deste overlay re-chavear por jogo -- ver ForegroundAppTracker.
    private val foregroundTracker: ForegroundAppTracker,
    private val thermalManager: ThermalManager,
    private val displayManager: DisplayManager,
    private val buttonActions: com.dfdx047.odinhub.tools.ButtonActionHandler,
) {
    private val windowManager = context.getSystemService<WindowManager>()
    private val main = Handler(Looper.getMainLooper())

    private val expandedFlow = MutableStateFlow(false)
    private var host: OverlayViewHost? = null
    private var params: WindowManager.LayoutParams? = null

    private var initialY = 0
    private var initialTouchY = 0f
    private var initialTouchX = 0f
    private var isDragging = false

    private val density: Float get() = context.resources.displayMetrics.density

    /** Posição vertical do puxador antes de expandir (o painel expandido usa y = 0). */
    private var collapsedY = 0

    /**
     * Atalho / gesto "abrir overlay". Só abre se o puxador estiver visível (dentro de um jogo):
     * antes, carregar no atalho no launcher marcava o painel como aberto sem nada aparecer, e ele
     * surgia expandido -- e com o foco do comando -- por cima do jogo seguinte.
     */
    fun toggle() {
        main.post {
            if (expandedFlow.value) {
                setExpanded(false)
            } else if (host?.composeView?.visibility == View.VISIBLE) {
                setExpanded(true)
            }
        }
    }

    fun show() {
        main.post {
            if (host != null || !Settings.canDrawOverlays(context)) return@post
            val wm = windowManager ?: return@post
            val newHost = OverlayViewHost(context)
            val lp = newParams(expanded = false)

            // Só com o motor gráfico: era I/O de disco a cada recomposição do overlay, à toa.
            val dllImported = com.dfdx047.odinhub.models.FeatureFlags.GRAPHICS_ENGINE_AVAILABLE && LosslessManager(context).isDllImported

            newHost.setContent {
                val expanded by expandedFlow.collectAsState()
                // Relê a aparência quando ela muda na app (tema, AMOLED, vidro).
                val appearance by prefs.appearanceVersion.collectAsState()
                val rawTheme = remember(appearance) { AvailableThemes.getOrElse(prefs.selectedThemeIndex) { AvailableThemes[0] } }
                val currentTheme = getResolvedTheme(rawTheme, prefs.useAmoledBlack, prefs.useGlass)
                val isDllReady = dllImported && prefs.globalLsfgEnabled

                // O pacote em primeiro plano e o mapa de regras vêm de StateFlows observados aqui
                // dentro: é isto que faz esta composição re-chavear sozinha quando o utilizador
                // troca de jogo (antes era uma leitura única de SharedPreferences, e por isso o
                // overlay gravava as regras do jogo sob o pacote errado -- ver
                // ForegroundAppTracker).
                val currentPackage by foregroundTracker.currentPackage.collectAsState()
                val overridesByPackage by overrideRepository.overridesByPackage.collectAsState()

                // O Blur foi 100% removido do QuickAccessContent nas edições anteriores.
                // Agora o Compose desenhará limpo e sem borrar a si mesmo.
                QuickAccessContent(
                    isExpanded = expanded,
                    theme = currentTheme,
                    prefs = prefs,
                    isDllReady = isDllReady,
                    performanceManager = performanceManager,
                    thermalManager = thermalManager,
                    displayManager = displayManager,
                    buttonActions = buttonActions,
                    overrideRepository = overrideRepository,
                    currentPackage = currentPackage,
                    overridesByPackage = overridesByPackage,
                    onExpand = { setExpanded(true) },
                    onClose = { setExpanded(false) }
                )
            }

            // O painel expandido é focável (recebe o comando), por isso Voltar ou B têm de o
            // fechar -- antes não havia maneira de sair dele sem tocar no ecrã.
            newHost.composeView.isFocusableInTouchMode = true
            newHost.setKeyInterceptor { event ->
                val keyCode = event.keyCode
                val closeKey = keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B ||
                    keyCode == KeyEvent.KEYCODE_ESCAPE
                if (expandedFlow.value && closeKey) {
                    if (event.action == KeyEvent.ACTION_UP) setExpanded(false)
                    true
                } else {
                    false
                }
            }

            newHost.composeView.setOnTouchListener { _, event ->
                if (expandedFlow.value) return@setOnTouchListener false
                val currentParams = params ?: return@setOnTouchListener false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { initialY = currentParams.y; initialTouchY = event.rawY; initialTouchX = event.rawX; isDragging = false; true }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = event.rawY - initialTouchY
                        val dx = initialTouchX - event.rawX
                        if (abs(dy) > 10 || isDragging) {
                            isDragging = true
                            currentParams.y = initialY + dy.toInt()
                            wm.updateViewLayout(newHost.composeView, currentParams)
                        }
                        if (dx > 40 && !isDragging) setExpanded(true)
                        true
                    }
                    MotionEvent.ACTION_UP -> { val dx = initialTouchX - event.rawX; if (!isDragging && abs(dx) < 20) setExpanded(true); true }
                    else -> false
                }
            }

            // A view nasce já com a visibilidade correta. Sem isto havia uma corrida real: o
            // serviço chama `show()` no `onStartCommand` e `setHandleVisible(false)` na primeira
            // emissão do tracker -- se a emissão chegasse primeiro, `host` ainda era null, o
            // pedido para esconder perdia-se, e a barrinha nascia visível na home (o sintoma
            // relatado).
            newHost.composeView.visibility =
                if (foregroundTracker.isGameForeground.value) View.VISIBLE else View.GONE

            try {
                wm.addView(newHost.composeView, lp)
                newHost.onResumed()
                host = newHost
                params = lp
            } catch (e: Exception) { newHost.onDestroyed() }
        }
    }

    /**
     * Mostra/esconde o puxador lateral consoante haja ou não um jogo/app em primeiro plano (ver
     * `ForegroundAppTracker.isGameForeground`). Não destrói a view -- só a esconde -- para
     * reaparecer instantaneamente quando o jogo volta ao foreground, sem recriar toda a janela.
     * Ao esconder enquanto o painel está expandido, fecha-o primeiro (não faz sentido deixar o
     * painel aberto sobre a home/launcher).
     */
    fun setHandleVisible(visible: Boolean) {
        main.post {
            if (expandedFlow.value && (!visible || host?.composeView?.visibility != View.VISIBLE)) setExpanded(false)
            host?.composeView?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    fun hide() {
        main.post {
            host?.let { h -> runCatching { windowManager?.removeView(h.composeView) }; h.onDestroyed() }
            host = null
            params = null
            expandedFlow.value = false
        }
    }

    private fun setExpanded(expanded: Boolean) {
        main.post {
            val lp = params ?: return@post
            if (expanded && !expandedFlow.value) collapsedY = lp.y
            expandedFlow.value = expanded
            applyGeometry(lp, expanded)
            // A posição arrastada do puxador voltava sempre ao centro depois de abrir/fechar.
            if (!expanded) lp.y = collapsedY
            runCatching { windowManager?.updateViewLayout(host?.composeView, lp) }
            if (expanded) host?.composeView?.requestFocus()
        }
    }

    private fun newParams(expanded: Boolean): WindowManager.LayoutParams {
        val widthDp = if (expanded) 390 else prefs.overlayHandleWidth.coerceIn(16, 36)
        return WindowManager.LayoutParams(
            (widthDp * density).toInt(),
            if (expanded) WindowManager.LayoutParams.MATCH_PARENT else (70 * density).toInt(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            if (expanded) WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).also { applyGeometry(it, expanded) }
    }

    private fun applyGeometry(lp: WindowManager.LayoutParams, expanded: Boolean) {
        if (expanded) {
            lp.flags = WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            lp.dimAmount = 0.4f
            // Vidro fosco real: desfoca o jogo por trás do painel (Android 12+, quando o sistema
            // tem o blur entre janelas ativo -- caso contrário fica só o escurecimento normal).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && windowManager?.isCrossWindowBlurEnabled == true) {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                lp.blurBehindRadius = (24 * density).toInt()
            }
            lp.gravity = Gravity.TOP or Gravity.END
            lp.width = (390 * density).toInt()
            lp.height = WindowManager.LayoutParams.MATCH_PARENT
            lp.x = 0; lp.y = 0
        } else {
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            lp.dimAmount = 0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) lp.blurBehindRadius = 0
            lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            lp.width = (prefs.overlayHandleWidth.coerceIn(16, 36) * density).toInt()
            lp.height = (70 * density).toInt()
            lp.x = 0
        }
    }
}