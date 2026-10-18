package com.dfdx047.odinhub.tools.hardware

import android.content.Context
import android.os.PowerManager
import com.dfdx047.odinhub.models.FpsTracker
import com.dfdx047.odinhub.models.HudConfig
import com.dfdx047.odinhub.models.HudMath
import com.dfdx047.odinhub.models.HudMetric
import com.dfdx047.odinhub.models.HudSnapshot
import com.dfdx047.odinhub.tools.ForegroundAppTracker
import com.dfdx047.odinhub.tools.ShellExecutor
import com.dfdx047.odinhub.tools.shellQuote
import kotlinx.coroutines.currentCoroutineContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Recolhe as métricas do HUD de desempenho, uma vez por segundo, SÓ enquanto o HUD está visível
 * e o ecrã ligado.
 *
 * Quase tudo é leitura direta de sysfs/proc (sem root, barato): temperaturas das zonas térmicas,
 * frequência de cada núcleo e da GPU, ocupação da GPU, RAM e bateria. Só duas métricas precisam
 * do PServer, e vão as duas numa ÚNICA transação por segundo:
 *  - FPS / frame time / 1% low: os tempos REAIS de apresentação dos frames da camada do jogo
 *    (`dumpsys SurfaceFlinger --latency <camada>`), que funciona no Android 13, 14 e 15. O antigo
 *    contador `service call SurfaceFlinger 1013` foi desativado no Android 14+ (o Odin 3 está no
 *    15); fica só como recurso quando não se encontra a camada do jogo.
 *  - Uso da CPU: `/proc/stat` (as apps não o podem ler desde o Android 8).
 */
@Singleton
class HudSampler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val executor: ShellExecutor,
    private val performanceManager: PerformanceManager,
    private val foregroundTracker: ForegroundAppTracker,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    private val _snapshot = MutableStateFlow(HudSnapshot())
    val snapshot: StateFlow<HudSnapshot> = _snapshot.asStateFlow()

    @Volatile private var config = HudConfig()

    private val powerManager: PowerManager? = context.getSystemService(PowerManager::class.java)

    // Descoberto uma vez (os nós não mudam com o aparelho ligado).
    private val cpuTempNodes: List<String> by lazy { zonesMatching(CPU_ZONE) }
    private val gpuTempNodes: List<String> by lazy { zonesMatching(GPU_ZONE) }
    private val policyCurNodes: List<String> by lazy {
        File(CPUFREQ).listFiles()?.filter { it.name.startsWith("policy") }
            ?.sortedBy { readLong("${it.absolutePath}/cpuinfo_max_freq") ?: 0L }
            ?.map { "${it.absolutePath}/scaling_cur_freq" }.orEmpty()
    }
    private val coreCount: Int by lazy {
        File("/sys/devices/system/cpu").list()?.count { it.matches(Regex("cpu\\d+")) } ?: 0
    }

    // ---- estado só da coroutine de recolha (é reposto DENTRO dela, ver loop) ----
    private var lastFlips: Long? = null
    private var lastFlipsAt = 0L
    private var lastCpu: Pair<Long, Long>? = null
    private val history = ArrayDeque<Float>()
    private val fpsTracker = FpsTracker()
    private var layerPackage: String? = null
    private var layerCandidates: List<String> = emptyList()
    private var layerIndex = 0
    private var staleTicks = 0

    /** Liga/desliga a recolha. Chamado pelo serviço do HUD quando a configuração muda. */
    fun update(newConfig: HudConfig, visible: Boolean) {
        config = newConfig
        val shouldRun = newConfig.enabled && visible
        if (shouldRun && job?.isActive != true) {
            job = scope.launch { loop() }
        } else if (!shouldRun) {
            job?.cancel()
            job = null
        }
    }

    private suspend fun loop() {
        // Recomeça do zero AQUI (e não em update(), na thread principal): uma recolha ainda a
        // meio na thread de IO não pode ver as estruturas a ser limpas por outra thread.
        resetState()
        while (currentCoroutineContext().isActive) {
            if (powerManager?.isInteractive == false) {
                resetState()
                delay(2_000)
                continue
            }
            runCatching { _snapshot.value = sample(config) }
            delay(INTERVAL_MS)
        }
    }

    private fun resetState() {
        lastFlips = null
        lastCpu = null
        history.clear()
        fpsTracker.clear()
        layerPackage = null
        layerCandidates = emptyList()
        layerIndex = 0
        staleTicks = 0
    }

    private fun sample(cfg: HudConfig): HudSnapshot {
        var fps: Float? = null
        var frametime: Float? = null
        var fpsLow: Float? = null
        var cpuUsage: Int? = null

        if (cfg.needsRoot && executor.outputCaptureWorks) {
            val wantFps = cfg.metrics.any { it.isFps }
            val wantCpu = cfg.has(HudMetric.CPU_USAGE)
            val pkg = foregroundTracker.currentPackage.value.takeIf { it.isNotBlank() && it != ForegroundAppTracker.GLOBAL && it.matches(PACKAGE) }
            if (wantFps && pkg != layerPackage) resolveLayer(pkg)
            val layer = layerCandidates.getOrNull(layerIndex)

            // Uma única transação root por segundo: frames da camada do jogo (ou o contador
            // antigo, se não houver camada) + /proc/stat.
            val fpsCmd = when {
                !wantFps -> null
                layer != null -> "dumpsys SurfaceFlinger --latency ${shellQuote(layer)}"
                else -> "service call SurfaceFlinger 1013"
            }
            val cmd = listOfNotNull(fpsCmd, if (wantCpu) "echo $STAT_MARK; head -n 1 /proc/stat" else null).joinToString("; ")
            val out = executor.executeAsRoot(cmd).getOrNull().orEmpty()
            val fpsPart = out.substringBefore(STAT_MARK)

            if (wantFps && layer != null) {
                val before = fpsTracker.newest
                fpsTracker.add(HudMath.parseLatencyPresents(fpsPart))
                if (fpsTracker.newest == before) {
                    // Sem frames novos: jogo pausado -- ou a camada errada. Ao fim de 3 s tenta a
                    // candidata seguinte (e, no fim da lista, volta a procurar as camadas).
                    staleTicks++
                    if (staleTicks >= 3) {
                        staleTicks = 0
                        layerIndex++
                        if (layerIndex >= layerCandidates.size) resolveLayer(pkg)
                        fpsTracker.clear()
                    }
                } else {
                    staleTicks = 0
                    fps = fpsTracker.fps()
                    frametime = fpsTracker.frametimeMs()
                    fpsLow = fpsTracker.onePercentLow()
                }
            } else if (wantFps) {
                // Recurso (Android 13): FPS do ecrã pelo contador de page-flips. Com um jogo à
                // frente, volta a procurar a camada dele de 5 em 5 s (pode ainda não existir).
                if (pkg != null && ++staleTicks >= 5) resolveLayer(pkg)
                val now = System.currentTimeMillis()
                val flips = HudMath.parsePageFlips(fpsPart)
                val previous = lastFlips
                if (flips != null && previous != null) fps = HudMath.fps(previous, flips, now - lastFlipsAt)
                lastFlips = flips
                lastFlipsAt = now
                frametime = fps?.takeIf { it >= 1f }?.let { 1000f / it }
            }
            if (wantCpu) {
                val stat = HudMath.parseCpuStat(out.substringAfter(STAT_MARK, "").lines().firstOrNull { it.startsWith("cpu") })
                val previous = lastCpu
                if (stat != null && previous != null) cpuUsage = HudMath.cpuUsage(previous, stat)
                lastCpu = stat
            }
        }

        if (frametime != null) {
            history.addLast(frametime)
            while (history.size > HISTORY) history.removeFirst()
        }

        val ram = if (cfg.has(HudMetric.RAM)) HudMath.parseMeminfo(readText("/proc/meminfo")) else null
        val battery = "/sys/class/power_supply/battery"
        val status = readText("$battery/status")?.lowercase()
        val power = if (cfg.has(HudMetric.POWER)) {
            val c = readLong("$battery/current_now")
            val v = readLong("$battery/voltage_now")
            if (c != null && v != null) AutoTdpController.batteryWatts(c, v) else null
        } else null

        return HudSnapshot(
            fps = fps,
            frametimeMs = frametime,
            fpsLow = fpsLow,
            frametimeHistory = history.toList(),
            cpuUsagePercent = cpuUsage,
            cpuTempC = if (cfg.has(HudMetric.CPU_TEMP)) maxTemp(cpuTempNodes) else null,
            clusterMHz = if (cfg.has(HudMetric.CPU_CLUSTERS)) policyCurNodes.map { (readLong(it) ?: 0L).toInt() / 1000 } else emptyList(),
            coreMHz = if (cfg.has(HudMetric.CPU_CORES)) (0 until coreCount).map { (readLong("/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq") ?: 0L).toInt() / 1000 } else emptyList(),
            gpuLoadPercent = if (cfg.has(HudMetric.GPU_LOAD)) gpuLoad() else null,
            gpuTempC = if (cfg.has(HudMetric.GPU_TEMP)) maxTemp(gpuTempNodes) else null,
            gpuMHz = if (cfg.has(HudMetric.GPU_FREQ)) gpuMHz() else null,
            ramUsedMB = ram?.first,
            ramTotalMB = ram?.second,
            powerW = power,
            charging = status == "charging" || status == "full",
            batteryPercent = if (cfg.has(HudMetric.BATTERY)) readLong("$battery/capacity")?.toInt() else null,
            batteryTempC = if (cfg.has(HudMetric.BATTERY_TEMP)) HudMath.parseTempC(readText("$battery/temp")) else null,
            tdpTargetW = if (cfg.has(HudMetric.TDP)) performanceManager.autoTdpStatus.value.takeIf { it.active }?.targetWatts else null,
        )
    }

    /** Procura as camadas do jogo em primeiro plano (só quando o jogo muda ou a camada falha). */
    private fun resolveLayer(pkg: String?) {
        layerPackage = pkg
        layerIndex = 0
        staleTicks = 0
        fpsTracker.clear()
        layerCandidates = if (pkg == null) emptyList()
        else HudMath.layerCandidates(executor.executeAsRoot("dumpsys SurfaceFlinger --list").getOrNull(), pkg).take(4)
    }

    private fun gpuLoad(): Int? =
        readText("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")?.let { AutoTdpController.parseGpuBusy(it) }
            ?: readText("/sys/kernel/ged/hal/gpu_utilization")?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

    private fun gpuMHz(): Int? {
        val hz = readLong("/sys/class/kgsl/kgsl-3d0/gpuclk")
            ?: readLong("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq")
        if (hz != null && hz > 0) return (hz / 1_000_000L).toInt()
        // MediaTek (GED): "current_freqency" (sic) em kHz, ex: "0 836000".
        return readText("/sys/kernel/ged/hal/current_freqency")?.let { raw ->
            Regex("\\d+").findAll(raw).mapNotNull { it.value.toLongOrNull() }.lastOrNull()?.let { (it / 1000).toInt() }
        }
    }

    private fun maxTemp(nodes: List<String>): Float? =
        nodes.mapNotNull { HudMath.parseTempC(readText(it)) }.filter { it in 1f..150f }.maxOrNull()

    private fun zonesMatching(pattern: Regex): List<String> =
        File("/sys/class/thermal").listFiles()
            ?.filter { it.name.startsWith("thermal_zone") }
            ?.filter { zone -> readText("${zone.absolutePath}/type")?.let { pattern.containsMatchIn(it) } == true }
            ?.map { "${it.absolutePath}/temp" }
            ?.filter { readText(it) != null } // só as legíveis sem root
            .orEmpty()

    private fun readText(path: String): String? = runCatching { File(path).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    private fun readLong(path: String): Long? = readText(path)?.toLongOrNull()

    private companion object {
        const val INTERVAL_MS = 1_000L
        const val HISTORY = 60
        const val CPUFREQ = "/sys/devices/system/cpu/cpufreq"
        const val STAT_MARK = "__odinhub_stat__"
        val PACKAGE = Regex("[A-Za-z0-9_.]+")

        /** Zonas de CPU (Qualcomm: cpu-*, cpuss-*; MediaTek: mtktscpu, cpu_big/little, soc_max). */
        val CPU_ZONE = Regex("^(cpu|cpuss|mtktscpu|cpu_big|cpu_little|soc_max|apc)", RegexOption.IGNORE_CASE)
        val GPU_ZONE = Regex("^(gpu|gpuss|mtktsgpu|gpu_)", RegexOption.IGNORE_CASE)
    }
}
