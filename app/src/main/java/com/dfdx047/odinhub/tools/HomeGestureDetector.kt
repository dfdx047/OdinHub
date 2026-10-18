package com.dfdx047.odinhub.tools

import android.os.Handler
import android.view.KeyEvent
import com.dfdx047.odinhub.models.ButtonAction
import com.dfdx047.odinhub.models.HomeGestureConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
            report(-1, activeConfig.longPress)
            fire(activeConfig.longPress)
        }
    }

    private val tapWindowRunnable = Runnable {
        val taps = tapCount
        tapCount = 0
        val action = activeConfig.actionForTaps(taps)
        report(taps, action)
        fire(action)
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
     * Só o Home do sistema (KEYCODE_HOME = 3), que é o que o botão da consola envia em toda a
     * família AYN/Retroid. NÃO apanhar KEYCODE_BUTTON_MODE: é o botão "Guia" dos comandos, que os
     * emuladores usam como tecla de atalho (menu do RetroArch, etc.) -- consumi-lo partia-os.
     */
    private fun isHomeKey(code: Int): Boolean = code == KeyEvent.KEYCODE_HOME

    private fun report(taps: Int, action: ButtonAction) {
        _lastGesture.value = LastGesture(taps, action, System.currentTimeMillis())
    }

    /** Último gesto reconhecido ([taps] = -1 para toque longo); mostrado no cartão dos gestos. */
    data class LastGesture(val taps: Int, val action: ButtonAction, val atMs: Long)

    companion object {
        private val _lastGesture = MutableStateFlow<LastGesture?>(null)
        val lastGesture: StateFlow<LastGesture?> = _lastGesture
    }

    fun reset() {
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(tapWindowRunnable)
        tapCount = 0
        isDown = false
        longFired = false
    }
}
