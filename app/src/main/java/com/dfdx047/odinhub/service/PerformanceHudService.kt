package com.dfdx047.odinhub.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.widget.Toast
import com.dfdx047.odinhub.data.SharedPrefsRepo
import com.dfdx047.odinhub.overlay.PerformanceHud
import com.dfdx047.odinhub.tools.hardware.HudSampler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Mantém o HUD de desempenho no ecrã, por cima de qualquer app, enquanto estiver ligado nas
 * definições. Dois toques no HUD escondem-no ([hiddenFlow]); volta com o gesto do Home "HUD de
 * desempenho", ou ao voltar a ligá-lo nas definições.
 */
@AndroidEntryPoint
class PerformanceHudService : Service() {

    @Inject lateinit var prefs: SharedPrefsRepo
    @Inject lateinit var sampler: HudSampler

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var hud: PerformanceHud? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        hud = PerformanceHud(this, prefs, sampler.snapshot) {
            hiddenFlow.value = true
            Toast.makeText(
                this,
                if (prefs.isEnglish) "HUD hidden — turn it on again in Odin Hub or with the Home gesture"
                else "HUD escondido — reative no Odin Hub ou com o gesto do Home",
                Toast.LENGTH_SHORT,
            ).show()
        }
        scope.launch {
            combine(prefs.hudConfigFlow, hiddenFlow) { config, hidden -> config to hidden }.collect { (config, hidden) ->
                val visible = config.enabled && !hidden
                sampler.update(config, visible)
                if (visible) hud?.show(config) else hud?.hide()
                if (!config.enabled) stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        sampler.update(prefs.hudConfig, visible = false)
        hud?.hide()
        hud = null
    }

    companion object {
        /** `true` depois dos dois toques no HUD; só em memória (volta a aparecer ao reabrir/religar). */
        val hiddenFlow = MutableStateFlow(false)

        /** Arranca o serviço se o HUD estiver ligado (MainViewModel, BootReceiver). */
        fun startIfEnabled(context: Context, prefs: SharedPrefsRepo) {
            if (!prefs.hudConfig.enabled) return
            runCatching { context.startService(Intent(context, PerformanceHudService::class.java)) }
        }

        /**
         * Gesto "HUD de desempenho": mostra/esconde. Se o HUD estiver desligado nas definições,
         * liga-o.
         */
        fun toggle(context: Context, prefs: SharedPrefsRepo) {
            val config = prefs.hudConfig
            if (!config.enabled) {
                hiddenFlow.value = false
                prefs.hudConfig = config.copy(enabled = true)
                startIfEnabled(context, prefs)
            } else {
                hiddenFlow.value = !hiddenFlow.value
                if (!hiddenFlow.value) startIfEnabled(context, prefs)
            }
        }
    }
}
