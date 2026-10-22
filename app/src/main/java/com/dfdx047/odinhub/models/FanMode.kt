package com.dfdx047.odinhub.models

import com.dfdx047.odinhub.tools.ShellExecutor

sealed class FanMode(
    val id: String,
    val shortLabel: String,
    val settingsValue: Int,
) {
    // Mapeamento dos valores de sistema originais para os nossos novos perfis da UI
    data object Silent : FanMode("Silent (Silencioso)", "Silent", 1)
    data object Smart : FanMode("Smart (Balanceado)", "Smart", 4)
    data object Sport : FanMode("Sport (Desempenho Máximo)", "Sport", 5)
    /**
     * Curva própria do Odin Hub (ecrã "Curva da ventoinha"). [settingsValue] é só um marcador da
     * app (100) -- NUNCA é escrito no `fan_mode`: quem comanda a ventoinha é o FanCurveController.
     */
    data object Custom : FanMode("Custom curve (Curva personalizada)", "Custom", CUSTOM_VALUE)
    data object Stock : FanMode("Stock (Padrão)", "Stock", -1) // -1 indica que devolveremos o controle ao sistema padrão
    data object Unknown : FanMode("unknown", "unknown", -1)

    fun enable(executor: ShellExecutor) {
        // Envia o valor exato para o sistema (se não for Stock/Unknown)
        if (this != Unknown && this != Stock && this != Custom) {
            executor.setIntSystemSetting(FAN_MODE, settingsValue)
        }
    }

    companion object {
        const val FAN_MODE = "fan_mode"
        const val FAN_SPEED = "fan_speed"
        const val CUSTOM_VALUE = 100

        /**
         * Modos selecionáveis pelo utilizador, na ordem em que devem aparecer na UI. Usar esta
         * lista em vez de índices "0, 1, 2" escritos à mão em cada ecrã evita a divergência que
         * foi encontrada na auditoria: o Settings, o overlay e o [ForegroundAppWatcherService]
         * usavam CADA UM o seu próprio mapeamento de números para modos de ventoinha, pelo que um
         * modo escolhido num ecrã podia ser reinterpretado como outro (ou como "Stock") assim que
         * o serviço de acessibilidade reaplicava os perfis ao trocar de app.
         */
        // Getter (e não um valor guardado): se o primeiro acesso à classe for por um dos objetos
        // (ex: FanMode.Custom), a inicialização do companion corria a meio da desse objeto e a
        // lista ficava com um `null` lá dentro -> NullPointerException em fromSettingsValue.
        val selectable: List<FanMode> get() = listOf(Silent, Smart, Sport, Custom)

        fun fromSettingsValue(value: Int): FanMode = selectable.find { it.settingsValue == value } ?: Stock

        // Função para converter o texto clicado na tela de volta para um comando do hardware
        fun fromString(profileName: String): FanMode {
            return when {
                profileName.contains("Silent", ignoreCase = true) -> Silent
                profileName.contains("Smart", ignoreCase = true) -> Smart
                profileName.contains("Sport", ignoreCase = true) -> Sport
                profileName.contains("Custom", ignoreCase = true) -> Custom
                profileName.contains("Stock", ignoreCase = true) -> Stock
                else -> Unknown
            }
        }
    }
}