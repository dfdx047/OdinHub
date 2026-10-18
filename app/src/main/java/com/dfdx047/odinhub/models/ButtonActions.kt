package com.dfdx047.odinhub.models

/**
 * Ações de sistema que um gesto do botão Home pode disparar (ver HomeGestureDetector /
 * ButtonActionHandler). O `id` é o que fica gravado em SharedPrefs -- nunca mudar um id existente.
 */
enum class ButtonAction(val id: String, val labelEn: String, val labelPt: String) {
    NONE("none", "Nothing", "Nada"),
    HOME("home", "Home", "Início"),
    BACK("back", "Back", "Voltar"),
    RECENTS("recents", "Recent apps", "Apps recentes"),
    SCREENSHOT("screenshot", "Screenshot", "Captura de ecrã"),
    SCREEN_RECORD("screen_record", "Start/stop screen recording", "Iniciar/parar gravação de ecrã"),
    TOGGLE_OVERLAY("toggle_overlay", "Open/close Odin Hub overlay", "Abrir/fechar overlay do Odin Hub"),
    TOGGLE_HUD("toggle_hud", "Show/hide performance HUD", "Mostrar/esconder HUD de desempenho"),
    NOTIFICATIONS("notifications", "Notifications", "Notificações"),
    QUICK_SETTINGS("quick_settings", "Quick settings", "Definições rápidas"),
    POWER_MENU("power_menu", "Power menu", "Menu de energia"),
    LOCK_SCREEN("lock_screen", "Lock screen", "Bloquear ecrã"),
    CLOSE_APP("close_app", "Close current game", "Fechar o jogo atual");

    fun label(isEn: Boolean): String = if (isEn) labelEn else labelPt

    companion object {
        fun fromId(id: String?): ButtonAction = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * Configuração dos gestos do botão Home. Com [enabled] = false o botão Home fica 100% nativo
 * (o serviço de acessibilidade nem olha para ele).
 */
data class HomeGestureConfig(
    val enabled: Boolean = false,
    val singleTap: ButtonAction = ButtonAction.HOME,
    val doubleTap: ButtonAction = ButtonAction.TOGGLE_OVERLAY,
    val tripleTap: ButtonAction = ButtonAction.SCREEN_RECORD,
    val longPress: ButtonAction = ButtonAction.SCREENSHOT,
    val longPressMs: Long = DEFAULT_LONG_PRESS_MS,
) {
    /**
     * Quantos toques vale a pena esperar. Se o triplo e o duplo estiverem em "Nada", o toque
     * simples dispara no instante em que se solta o botão (sem o atraso da janela de multi-toque).
     */
    val maxTaps: Int
        get() = when {
            tripleTap != ButtonAction.NONE -> 3
            doubleTap != ButtonAction.NONE -> 2
            else -> 1
        }

    fun actionForTaps(taps: Int): ButtonAction = when {
        taps <= 0 -> ButtonAction.NONE
        taps == 1 -> singleTap
        taps == 2 -> doubleTap
        else -> tripleTap
    }

    companion object {
        const val DEFAULT_LONG_PRESS_MS = 2000L
        /** Janela entre toques para contarem como duplo/triplo. */
        const val MULTI_TAP_WINDOW_MS = 300L
        val LONG_PRESS_OPTIONS_MS = listOf(1000L, 1500L, 2000L, 3000L)
    }
}
