package com.dfdx047.odinhub.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dfdx047.odinhub.data.SharedPrefsRepo
import com.dfdx047.odinhub.models.TdpProfiles
import com.dfdx047.odinhub.tools.hardware.PerformanceManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Testes no aparelho (só debug). Exemplos:
 *   adb shell am broadcast -a com.dfdx047.odinhub.debug.TEST -n com.dfdx047.odinhub/.debug.DebugTestReceiver --es op stock
 *   ... --es op cores --ei mask 240            (desliga 4-7; o cpu0 nunca)
 *   ... --es op clocks --ei eff 1075 --ei perf 1574 --ei prime 1862 --ei gpu 441 --ei pin 15
 *   ... --es op tdp --ef watts 4.5
 *   ... --es op state | probe | hud | overlay
 *   ... --es op profile --es id balanced        (perfil GLOBAL, como o chip do Settings)
 *   ... --es op rule --es pkg com.x --es id stock [--ef watts 12.5]   (regra por jogo: grava, relê
 *       do Room e aplica como o ForegroundAppWatcherService)
 *   ... --es op fan --es mode custom|silent|smart|sport|stock
 *   ... --es op fancurve --es cfg "20:10;30:30;50:70;110:100|cpu|C|6|ns|3"
 *   ... --es op fanstatus | fantest --ei percent 100
 * O resultado vai para o logcat com a etiqueta "OdinHubTest".
 */
@AndroidEntryPoint
class DebugTestReceiver : BroadcastReceiver() {

    @Inject lateinit var performanceManager: PerformanceManager
    @Inject lateinit var prefs: SharedPrefsRepo
    @Inject lateinit var rootLines: com.dfdx047.odinhub.tools.RootLines
    @Inject lateinit var executor: com.dfdx047.odinhub.tools.ShellExecutor
    @Inject lateinit var overrides: com.dfdx047.odinhub.data.AppOverrideRepository
    @Inject lateinit var fanCurve: com.dfdx047.odinhub.tools.hardware.FanCurveController

    override fun onReceive(context: Context, intent: Intent) {
        val op = intent.getStringExtra("op") ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (op) {
                    "stock" -> {
                        prefs.offlineCpuMask = 0
                        prefs.clockPinMask = 0
                        prefs.activeLimitMode = "TDP"
                        prefs.tdpProfileId = "stock"
                        performanceManager.applyCoreMask(0)
                        performanceManager.applyStockPerformance()
                    }
                    "cores" -> performanceManager.applyCoreMask(intent.getIntExtra("mask", 0))
                    "clocks" -> performanceManager.applyAbsoluteClocks(
                        intent.getIntExtra("perf", 0) * 1000L,
                        intent.getIntExtra("prime", 0) * 1000L,
                        intent.getIntExtra("gpu", 0) * 1_000_000L,
                        intent.getIntExtra("eff", 0) * 1000L,
                        intent.getIntExtra("pin", 0),
                    )
                    "tdp" -> performanceManager.applyDynamicTdp(intent.getFloatExtra("watts", 4.5f))
                    // O mesmo que MainViewModel.selectTdpProfile (com o modo TDP ativo).
                    "profile" -> {
                        val profile = TdpProfiles.byId(intent.getStringExtra("id"))
                        if (profile == null) Log.w(TAG, "profile: id desconhecido") else {
                            prefs.activeLimitMode = "TDP"
                            prefs.tdpProfileId = profile.id
                            profile.watts?.let { prefs.tdpValue = it }
                            performanceManager.applyTdpSelection(profile.watts)
                            Log.i(TAG, "profile ${profile.id} -> watts=${profile.watts} prefs.id=${prefs.tdpProfileId} prefs.value=${prefs.tdpValue} resolved=${TdpProfiles.resolveWatts(prefs.tdpProfileId, prefs.tdpValue)}")
                        }
                    }
                    // O mesmo que AppOverridesViewModel.saveOverride + ForegroundAppWatcherService.applyProfile.
                    "rule" -> {
                        val pkg = intent.getStringExtra("pkg").orEmpty()
                        val id = intent.getStringExtra("id") ?: TdpProfiles.ID_CUSTOM
                        val slider = intent.getFloatExtra("watts", prefs.tdpValue)
                        val encoded = TdpProfiles.encodeWatts(TdpProfiles.resolveWatts(id, slider))
                        kotlinx.coroutines.runBlocking {
                            overrides.upsert(
                                com.dfdx047.odinhub.data.AppOverrideEntity(
                                    packageName = pkg, limitMode = "TDP", tdpWatts = encoded,
                                    tdpProfile = com.dfdx047.odinhub.models.AppOverrideLabels.tdpLabel(encoded),
                                    clockProfile = "Stock", fanProfile = "Stock",
                                ),
                            )
                            // Espera que o cache do repositório (Flow do Room) veja a gravação.
                            var tries = 0
                            while (overrides.get(pkg)?.tdpWatts != encoded && tries++ < 20) kotlinx.coroutines.delay(100)
                        }
                        val back = kotlinx.coroutines.runBlocking { overrides.getWhenLoaded(pkg) }
                        Log.i(TAG, "rule $pkg id=$id slider=$slider -> stored=${back?.tdpWatts} label=${back?.tdpProfile} reloadedId=${back?.tdpWatts?.let { TdpProfiles.idForWatts(it) }}")
                        performanceManager.applyTdpSelection(back?.tdpWatts ?: TdpProfiles.resolveWatts(prefs.tdpProfileId, prefs.tdpValue))
                    }
                    // O mesmo que MainViewModel.updateFanMode.
                    "fan" -> {
                        val mode = com.dfdx047.odinhub.models.FanMode.fromString(intent.getStringExtra("mode").orEmpty())
                        prefs.fanMode = mode.settingsValue
                        fanCurve.start()
                        performanceManager.applyFanMode(mode)
                        Log.i(TAG, "fan mode=${mode.shortLabel} prefs.fanMode=${prefs.fanMode} backup=${prefs.fanModeBackup}")
                    }
                    "fancurve" -> {
                        prefs.fanCurveConfig = com.dfdx047.odinhub.models.FanCurveConfig.decode(intent.getStringExtra("cfg"))
                        Log.i(TAG, "fancurve -> ${prefs.fanCurveConfig.encode()}")
                    }
                    "fantest" -> fanCurve.testSpeed(prefs.fanCurveConfig, intent.getIntExtra("percent", 100))
                    "fanstatus" -> Unit
                    "delrule" -> {
                        val pkg = intent.getStringExtra("pkg").orEmpty()
                        kotlinx.coroutines.runBlocking { overrides.delete(pkg) }
                        Log.i(TAG, "delrule $pkg -> has=${overrides.has(pkg)}")
                    }
                    // Abre/fecha o painel rápido (o mesmo caminho do atalho/gesto "abrir overlay").
                    "overlay" -> com.dfdx047.odinhub.service.GamingOverlayService.toggleOverlayFlow.tryEmit(Unit)
                    "hud" -> {
                        val on = intent.getBooleanExtra("on", true)
                        prefs.hudConfig = prefs.hudConfig.copy(enabled = on)
                        if (on) {
                            com.dfdx047.odinhub.service.PerformanceHudService.hiddenFlow.value = false
                            com.dfdx047.odinhub.service.PerformanceHudService.startIfEnabled(context.applicationContext, prefs)
                        }
                        Log.i(TAG, "hud enabled=$on config=${prefs.hudConfig}")
                    }
                    // Um comando simples pelo PServer (sem ; && | &), para diagnóstico.
                    "root" -> {
                        val cmd = intent.getStringExtra("cmd").orEmpty()
                        if (cmd.isBlank() || Regex("[;&|`$<>]").containsMatchIn(cmd)) {
                            Log.w(TAG, "root: comando recusado (vazio ou composto): $cmd")
                        } else {
                            val r = executor.executeAsRoot(cmd)
                            Log.i(TAG, "root '$cmd' -> " + (r.getOrNull() ?: r.exceptionOrNull()))
                        }
                    }
                    // Como "root", mas pelo RootLines (saída de várias linhas completa).
                    "lines" -> {
                        val cmd = intent.getStringExtra("cmd").orEmpty()
                        if (cmd.isBlank() || Regex("[;&|`$<>]").containsMatchIn(cmd)) Log.w(TAG, "lines: comando recusado: $cmd")
                        else rootLines.run(cmd).orEmpty().lines().let { l -> Log.i(TAG, "lines '$cmd' -> ${l.size} linhas"); l.take(40).forEach { Log.i(TAG, "  | $it") } }
                    }
                    "probe" -> {
                        Log.i(TAG, "probe pserver=${executor.pServerAvailable} capture=${executor.outputCaptureWorks}")
                        Log.i(TAG, "probe id=" + executor.executeAsRoot("id").getOrNull())
                        Log.i(TAG, "probe ls /data/adb=" + executor.executeAsRoot("ls /data/adb").let { it.getOrNull() ?: it.exceptionOrNull() })
                        val vk = executor.executeAsRoot("cmd gpu vkjson").getOrNull().orEmpty()
                        Log.i(TAG, "probe vkjson len=${vk.length} api=" + Regex("\"apiVersion\"\\s*:\\s*(\\d+)").find(vk)?.groupValues?.get(1))
                    }
                    "state" -> {
                        performanceManager.refreshCoreStates()
                        performanceManager.refreshGpuLive()
                    }
                }
                Thread.sleep(2500)
                performanceManager.refreshCoreStates()
                performanceManager.refreshGpuLive()
                Log.i(TAG, "op=$op cores=${performanceManager.coreStates.value.joinToString { "${it.cpu}:${if (it.online) "on" else "off"}" }}")
                Log.i(TAG, "op=$op gpu=${performanceManager.gpuLive.value} failures=${performanceManager.lastApplyStatus.value.joinToString { "${it.label}=${it.appliedValue}" }}")
                Log.i(TAG, "op=$op autoTdp=${performanceManager.autoTdpStatus.value}")
                Log.i(TAG, "op=$op fan=${fanCurve.readStatus()} curveActive=${com.dfdx047.odinhub.tools.hardware.FanCurveState.active.value}")
            } catch (t: Throwable) {
                Log.e(TAG, "op=$op falhou", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "OdinHubTest"
    }
}
