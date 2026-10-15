package com.dfdx047.odinhub.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import com.dfdx047.odinhub.data.SharedPrefsRepo
import com.dfdx047.odinhub.service.GamingOverlayService
import com.dfdx047.odinhub.tools.hardware.DisplayManager
import com.dfdx047.odinhub.tools.hardware.ThermalManager
import com.dfdx047.odinhub.tools.hardware.VulkanNativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * BUG PRÉ-EXISTENTE encontrado ao investigar o "fica preso no logo" (não é desta ronda de
 * alterações -- já estava assim no repositório original, antes de qualquer parte desta
 * auditoria): o `AndroidManifest.xml` já declarava este receiver
 * (`.tools.BootReceiver`, para `BOOT_COMPLETED` e `MY_PACKAGE_REPLACED`) há muito tempo, mas a
 * classe em si NUNCA chegou a ser escrita -- ficou só a referência no manifesto, apontando para
 * uma classe inexistente.
 *
 * `MY_PACKAGE_REPLACED` dispara automaticamente em TODA reinstalação/atualização da app -- ou
 * seja, sempre que fazes um build novo e instalas por cima para testar. O Android tentava
 * instanciar `com.dfdx047.odinhub.tools.BootReceiver`, apanhava um `ClassNotFoundException`
 * (visível no logcat como `RuntimeException: Unable to instantiate receiver ...`), e isso
 * derrubava o processo inteiro -- exatamente enquanto a `MainActivity` estava a arrancar depois
 * da instalação. Daí "aparece a logo e não sai mais": o processo morre no meio do arranque, o
 * launcher tenta de novo, e o ciclo repete (ver `ActivityTaskManager: Activity pause/stop
 * timeout` no logcat que mandaste, que é o sistema a lidar com o processo que acabou de morrer).
 *
 * Esta classe agora existe de verdade e faz algo útil com o gancho: reaplica as configurações
 * root que não sobrevivem a um reboot (sysfs é sempre reposto a valores de fábrica ao reiniciar)
 * -- TDP/clocks globais não são reaplicados aqui de propósito (dependem de qual app está em
 * primeiro plano, isso é o `ForegroundAppWatcherService`), mas a calibração de cor e o overlay
 * (se estava ativado) sim.
 *
 * `goAsync()` + coroutine em `Dispatchers.IO`: um `BroadcastReceiver.onReceive` corre na thread
 * principal e tem um limite curto (~10s) antes do sistema o considerar poços -- fazer `exec` root
 * aqui de forma síncrona seria o mesmo erro que causou o bug da Parte 5 (ver `MainViewModel`).
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settings: SettingsRepo
    @Inject lateinit var prefs: SharedPrefsRepo
    @Inject lateinit var displayManager: DisplayManager
    @Inject lateinit var thermalManager: ThermalManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                settings.applyRequiredSettings()

                if (intent.action == Intent.ACTION_BOOT_COMPLETED) displayManager.onDeviceBoot()
                displayManager.applyColor(prefs.saturationOverride, prefs.temperatureOverride)

                // Motor gráfico: só quando existir (FeatureFlags) -- evita carregar a biblioteca nativa à toa.
                if (com.dfdx047.odinhub.models.FeatureFlags.GRAPHICS_ENGINE_AVAILABLE) {
                    VulkanNativeBridge.applyLsfg(prefs.globalLsfgEnabled, prefs.lsfgMultiplier, prefs.lsfgFramePacing)
                    VulkanNativeBridge.applySgsr(prefs.globalSgsrEnabled, prefs.sgsrMode)
                    VulkanNativeBridge.applyReshade(prefs.reshadeProfile, prefs.saturationOverride, prefs.temperatureOverride)
                }

                // Limite térmico (root): os trip points voltam aos valores de fábrica a cada
                // reboot; se o utilizador escolheu outra coisa que não Stock, reaplica. O próprio
                // ThermalManager já o faz no seu `init` -- esta chamada é só a garantia de que
                // acontece mesmo que o singleton já existisse.
                runCatching { thermalManager.reapplyPersisted() }
                    .onFailure { Log.w(TAG, "Limite térmico não pôde ser reaplicado no boot", it) }

                if (prefs.overlayEnabled) {
                    // A partir do Android O, arrancar um serviço a partir de um processo em
                    // segundo plano (que é o caso aqui, no arranque do sistema) lança
                    // IllegalStateException. Como isto corre dentro do `goAsync` de um receiver,
                    // uma exceção não apanhada derrubava o processo -- o mesmo tipo de falha que
                    // já causou o "fica preso no logo" desta app. O overlay não é crítico ao
                    // arranque: volta a ficar disponível assim que o utilizador abrir a app (ver
                    // MainViewModel), por isso registamos e seguimos.
                    runCatching {
                        context.startService(Intent(context, GamingOverlayService::class.java))
                    }.onFailure {
                        Log.w(TAG, "Overlay não pôde arrancar no boot (será iniciado ao abrir a app)", it)
                    }
                }
            } catch (t: Throwable) {
                // Uma exceção inesperada aqui (dentro do goAsync, no arranque do sistema) derrubava
                // o processo inteiro durante o boot. Regista e segue.
                Log.e(TAG, "onReceive(${intent.action}) falhou", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
