package com.dfdx047.odinhub.tools

import android.os.Handler
import android.view.KeyEvent
import com.dfdx047.odinhub.models.ButtonAction
import com.dfdx047.odinhub.models.HomeGestureConfig

/**
 * Deteta toque simples / duplo / triplo / longo no botão Home, dentro do serviço de
 * acessibilidade. Com os gestos ligados, o Home é CONSUMIDO (o sistema não o vê) e a ação do
 * toque simples -- por omissão "Início" -- é disparada por nós através da global action.
 *
 * Corre na thread principal do serviço (onde chegam os onKeyEvent); o [handler] é dessa thread.
 */
class HomeGestureDetector(
    private val handler: Handler,
    private val config: () -> HomeGestureConfig,
    private val fire: (ButtonAction) -> Unit,
) {
    private var tapCount = 0
    private var isDown = false
    private var longFired = false
    private var activeConfig = HomeGestureConfig()

    private val longPressRunnable = Runnable {
        if (isDown) {
            longFired = true
            tapCount = 0
            handler.removeCallbacks(tapWindowRunnable)
            fire(activeConfig.longPress)
        }
    }

    private val tapWindowRunnable = Runnable {
        val taps = tapCount
        tapCount = 0
        fire(activeConfig.actionForTaps(taps))
    }

    /** Devolve `true` se o evento foi consumido (gestos ligados e é o Home). */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isHomeKey(event.keyCode)) return false
        val cfg = config()
        if (!cfg.enabled) {
            reset()
            return false
        }
        activeConfig = cfg
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return true
                isDown = true
                longFired = false
                handler.removeCallbacks(tapWindowRunnable)
                handler.removeCallbacks(longPressRunnable)
                if (cfg.longPress != ButtonAction.NONE) handler.postDelayed(longPressRunnable, cfg.longPressMs)
            }
            KeyEvent.ACTION_UP -> {
                isDown = false
                handler.removeCallbacks(longPressRunnable)
                if (longFired) {
                    longFired = false
                    return true
                }
                if (event.isCanceled) return true
                tapCount++
                handler.removeCallbacks(tapWindowRunnable)
                if (tapCount >= cfg.maxTaps) {
                    tapWindowRunnable.run()
                } else {
                    handler.postDelayed(tapWindowRunnable, HomeGestureConfig.MULTI_TAP_WINDOW_MS)
                }
            }
        }
        return true
    }

    /**
     * O Home do sistema (KEYCODE_HOME) e o botão "Home/Guia" do comando (KEYCODE_BUTTON_MODE):
     * conforme o firmware, o botão Home da consola chega como um ou como o outro -- e o detetor
     * só ouvia o primeiro.
     */
    private fun isHomeKey(code: Int): Boolean = code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_BUTTON_MODE

    fun reset() {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(tapWindowRunnable)
        tapCount = 0
        isDown = false
        longFired = false
    }
}
