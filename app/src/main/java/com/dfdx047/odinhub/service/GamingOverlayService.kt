package com.dfdx047.odinhub.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import com.dfdx047.odinhub.data.AppOverrideRepository
import com.dfdx047.odinhub.data.SharedPrefsRepo
import com.dfdx047.odinhub.overlay.QuickAccessOverlay
import com.dfdx047.odinhub.tools.ForegroundAppTracker
import com.dfdx047.odinhub.tools.hardware.DisplayManager
import com.dfdx047.odinhub.tools.hardware.PerformanceManager
import com.dfdx047.odinhub.tools.hardware.ThermalManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import javax.inject.Inject

@AndroidEntryPoint
class GamingOverlayService : Service() {

    @Inject
    lateinit var prefs: SharedPrefsRepo

    // Mesmo singleton usado pelo MainViewModel e pelo ForegroundAppWatcherService: o overlay
    // deixa de instanciar o seu próprio PerformanceManager (ver QuickAccessContent) e passa a
    // partilhar este, para que não existam dois daemons de hardware a competir entre si.
    @Inject
    lateinit var performanceManager: PerformanceManager

    // Mesma fonte única de verdade (Room) usada pelo ForegroundAppWatcherService.
    @Inject
    lateinit var overrideRepository: AppOverrideRepository

    // Quem diz ao overlay em que jogo estamos (e se deve sequer estar visível). Ver
    // ForegroundAppTracker: é isto que faz a composição do overlay re-chavear por jogo, em vez
    // de ficar presa ao pacote que estivesse gravado quando este serviço arrancou.
    @Inject
    lateinit var foregroundTracker: ForegroundAppTracker

    // Limite térmico e cor do ecrã por jogo, ajustáveis a partir do overlay.
    @Inject
    lateinit var thermalManager: ThermalManager
    @Inject
    lateinit var displayManager: DisplayManager
    @Inject
    lateinit var buttonActions: com.dfdx047.odinhub.tools.ButtonActionHandler

    private var overlay: QuickAccessOverlay? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        // Canal de comunicação invisível com o ForegroundAppWatcherService
        val toggleOverlayFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        overlay = QuickAccessOverlay(this, prefs, performanceManager, overrideRepository, foregroundTracker, thermalManager, displayManager, buttonActions)

        scope.launch {
            toggleOverlayFlow.collect {
                overlay?.toggle()
            }
        }

        // Garante que existe deteção de primeiro plano mesmo que o serviço de acessibilidade não
        // esteja ligado -- sem isto, o puxador nunca aparecia em jogo nenhum (ver
        // ForegroundAppTracker.startFallbackDetection).
        foregroundTracker.startFallbackDetection()

        // Bug reportado: a barrinha aparecia em todo o sistema (home incluída), como se fosse um
        // overlay global. Agora segue o tracker -- que arranca em `false` e só fica `true` dentro
        // de um app de utilizador, igual aos "modos game" dos telemóveis.
        scope.launch {
            foregroundTracker.isGameForeground.collect { active ->
                overlay?.setHandleVisible(active)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        overlay?.show() // Mostra o puxador lateral invisível
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // A sondagem vive no singleton do tracker: sem isto continuava a correr (UsageStats a
        // cada 1.5 s) depois de o utilizador desligar o overlay.
        foregroundTracker.stopFallbackDetection()
        scope.cancel()
        overlay?.hide()
        overlay = null
    }
}
