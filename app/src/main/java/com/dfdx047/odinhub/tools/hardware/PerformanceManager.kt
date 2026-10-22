package com.dfdx047.odinhub.tools.hardware

import android.content.Context
import android.os.PowerManager
import android.system.Os
import android.util.Log
import com.dfdx047.odinhub.data.SharedPrefsRepo
import com.dfdx047.odinhub.models.DeviceProfile
import com.dfdx047.odinhub.models.FanMode
import com.dfdx047.odinhub.models.GpuBackend
import com.dfdx047.odinhub.models.TdpProfiles
import com.dfdx047.odinhub.tools.DeviceRegistry
import com.dfdx047.odinhub.tools.ShellExecutor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** Resultado da última tentativa de aplicar um [HardwareWriteOp] individual, para diagnóstico na UI. */
data class HardwareNodeStatus(
    val label: String,
    val path: String,
    val requestedValue: String,
    val appliedValue: String?,
    val ok: Boolean,
)

/** O que o utilizador pediu. O daemon é o ÚNICO a escrever em sysfs, a partir disto. */
enum class PerfMode { STOCK, TDP, CLOCKS }

data class PerfTargets(
    val mode: PerfMode = PerfMode.STOCK,
    /** Só em [PerfMode.CLOCKS]. */
    val perfRatio: Float = 1f,
    val primeRatio: Float = 1f,
    val gpuHz: Long = Long.MAX_VALUE,
    /** Só em [PerfMode.TDP]. */
    val watts: Float = 0f,
    /** Muda a cada troca de MODO (não a cada watt): o AutoTDP só recomeça do zero nessa altura. */
    val generation: Long = 0,
    /** Só em [PerfMode.CLOCKS]: cluster de eficiência; `null` = segue o Perf. */
    val effRatio: Float? = null,
)

/** Porque é que o AutoTDP está em pausa (mantém os tetos atuais sem os mexer). */
enum class AutoTdpPause { CHARGING, SCREEN_OFF, NO_SENSOR }

/** Estado do AutoTDP para a UI ("a medir 9.8 W"). */
data class AutoTdpTelemetry(
    val active: Boolean = false,
    val targetWatts: Float = 0f,
    val measuredWatts: Float? = null,
    val cpuPercent: Int = 100,
    val gpuPercent: Int = 100,
    val paused: AutoTdpPause? = null,
)

/**
 * Motor de clocks/TDP. Toda a escrita em sysfs acontece num ÚNICO daemon:
 *  - os `apply*` públicos só mudam o alvo ([PerfTargets]) e acordam o daemon -- acabaram as
 *    corridas entre o passo do AutoTDP e os clocks manuais escritos por outra thread;
 *  - o daemon só escreve quando o nó NÃO tem já o valor pedido (leitura direta do ficheiro, sem
 *    root). A versão anterior reescrevia todos os nós a cada segundo, mesmo em Stock e com o ecrã
 *    desligado: 6-8 transações binder e ~15-20 processos root por segundo, para sempre;
 *  - em Stock escreve os máximos UMA vez, devolve as permissões originais aos nós (o perf HAL do
 *    fabricante volta a poder geri-los) e fica parado;
 *  - com o ecrã desligado, pausa.
 *
 * Multi-aparelho: as tabelas, a GPU (Adreno/kgsl ou Mali/GED) e o envelope de potência vêm do
 * perfil do aparelho detetado ([DeviceRegistry]). As políticas de cpufreq continuam a ser lidas
 * do kernel em tempo real (a de maior frequência é o "Prime", a seguinte o "Perf").
 */
@Singleton
class PerformanceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val executor: ShellExecutor,
    private val prefs: SharedPrefsRepo,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val device: DeviceProfile = DeviceRegistry.current

    private val targets = AtomicReference(PerfTargets())
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _clockTables = MutableStateFlow(ClockTables.EMPTY)

    /** Tabelas de frequências reais do SoC (OPPs do kernel), em MHz, para os chips da UI. */
    val clockTables: StateFlow<ClockTables> = _clockTables.asStateFlow()

    private val _lastApplyStatus = MutableStateFlow<List<HardwareNodeStatus>>(emptyList())

    /** Nós que falharam na última passada (a UI mostra um aviso). Só falhas reais, não "já estava". */
    val lastApplyStatus: StateFlow<List<HardwareNodeStatus>> = _lastApplyStatus.asStateFlow()

    private val _autoTdp = MutableStateFlow(AutoTdpTelemetry())
    val autoTdpStatus: StateFlow<AutoTdpTelemetry> = _autoTdp.asStateFlow()

    // ---- estado só do daemon (uma única coroutine) ----
    @Volatile private var policies: List<CpuPolicyNode> = emptyList()
    @Volatile private var gpu: GpuControl = GpuControl.None
    @Volatile private var ppmAvailable = false
    private var autoState: AutoTdpState? = null
    private var lastTargetWatts: Float? = null
    /** Geração já aplicada. Arranca em 0 = os alvos iniciais: nada é escrito no arranque. */
    @Volatile private var appliedGeneration = 0L
    private var appliedTargets: PerfTargets? = null
    private val lastResults = HashMap<String, Pair<HardwareNodeStatus, Long>>()
    private val backoffUntil = HashMap<String, Long>()
    private val lastPpm = HashMap<String, String>()

    private val powerManager: PowerManager? = context.getSystemService(PowerManager::class.java)

    init {
        scope.launch {
            loadHardware()
            runDaemon()
        }
    }

    // ------------------------------------------------------------------ API pública

    /** `null` = Stock; qualquer outro valor = TDP dinâmico nesses watts. */
    fun applyTdpSelection(watts: Float?) {
        if (watts == null || TdpProfiles.isStockWatts(watts)) applyStockPerformance() else applyDynamicTdp(watts)
    }

    /**
     * TDP dinâmico. Mudar só os watts (ex: arrastar o slider) NÃO reinicia o controlador -- ele
     * continua de onde está e converge para o novo alvo; só a troca de modo recomeça do zero.
     */
    fun applyDynamicTdp(watts: Float) {
        val clamped = TdpProfiles.clampWatts(watts)
        setTargets { current ->
            if (current.mode == PerfMode.TDP) current.copy(watts = clamped)
            else PerfTargets(mode = PerfMode.TDP, watts = clamped, generation = current.generation + 1)
        }
    }

    /** Stock: sem limite -- máximos escritos uma vez, nós devolvidos ao sistema, AutoTDP parado. */
    fun applyStockPerformance() {
        setTargets { current ->
            // A geração 0 é o estado de arranque (nunca aplicado): um pedido explícito de Stock
            // tem de correr pelo menos uma vez, para devolver nós trancados por um processo anterior.
            if (current.mode == PerfMode.STOCK && current.generation != 0L && appliedGeneration == current.generation) current
            else PerfTargets(mode = PerfMode.STOCK, generation = current.generation + 1)
        }
    }

    /**
     * Clocks manuais. [effClockKHz] é o cluster de eficiência (8 Gen 2 / 865); `null` ou <= 0 =
     * segue o ratio do Perf, como antes.
     */
    fun applyAbsoluteClocks(perfClockKHz: Long, primeClockKHz: Long, gpuClockHz: Long, effClockKHz: Long? = null) {
        val (perfRatio, primeRatio) = PerformanceCommandBuilder.absoluteClocksToRatios(
            perfClockKHz = perfClockKHz,
            primeClockKHz = primeClockKHz,
            policies = policies,
            fallbackPerfMaxKHz = (device.fallbackTables.perfMHz.lastOrNull() ?: 3532) * 1000L,
            fallbackPrimeMaxKHz = (device.fallbackTables.primeMHz.lastOrNull() ?: 4320) * 1000L,
        )
        setTargets { current ->
            val generation = if (current.mode == PerfMode.CLOCKS) current.generation else current.generation + 1
            val effRatio = PerformanceCommandBuilder.effClockToRatio(
                effClockKHz, policies, device.fallbackTables.effMHz.lastOrNull()?.let { it * 1000L },
            )
            PerfTargets(PerfMode.CLOCKS, perfRatio, primeRatio, gpuClockHz, generation = generation, effRatio = effRatio)
        }
    }

    /**
     * Modo da ventoinha. "Stock" agora DEVOLVE o modo que o sistema tinha antes de a app mexer
     * (antes não fazia nada: um "Sport" de uma regra por jogo ficava ligado no resto do sistema).
     * BLOQUEANTE (root) -- chamar fora da thread principal.
     */
    fun applyFanMode(fanMode: FanMode) {
        // Curva própria: guarda o modo do firmware (para o Stock o repor) e entrega a ventoinha
        // ao FanCurveController. Qualquer outro modo desliga a curva.
        FanCurveState.active.value = fanMode == FanMode.Custom
        if (fanMode == FanMode.Custom) {
            if (prefs.fanModeBackup < 0) {
                executor.getIntSystemSettingOrNull(FanMode.FAN_MODE)?.let { prefs.fanModeBackup = it }
            }
            return
        }
        if (fanMode in FanMode.selectable) {
            if (prefs.fanModeBackup < 0) {
                executor.getIntSystemSettingOrNull(FanMode.FAN_MODE)?.let { prefs.fanModeBackup = it }
            }
            executor.setIntSystemSetting(FanMode.FAN_MODE, fanMode.settingsValue)
        } else if (fanMode == FanMode.Stock) {
            val backup = prefs.fanModeBackup
            if (backup >= 0 && executor.setIntSystemSetting(FanMode.FAN_MODE, backup)) prefs.fanModeBackup = -1
        }
    }

    private fun setTargets(transform: (PerfTargets) -> PerfTargets) {
        targets.updateAndGet(transform)
        wake.trySend(Unit)
    }

    // ------------------------------------------------------------------ descoberta

    private fun loadHardware() {
        policies = scanCpuPolicies()
        ensureCpuBoostEnabled()
        gpu = createGpuControl()
        ppmAvailable = device.soc.isMediaTek && File(PPM_HARD_LIMIT).exists()

        val fallback = device.fallbackTables
        val sorted = policies.sortedBy { it.cpuinfoMaxFreqKHz }
        val prime = ClockTableCuration.kHzToMHz(sorted.lastOrNull()?.availableFreqsKHz.orEmpty())
        // Perf = a política logo abaixo do Prime (a mesma que absoluteClocksToRatios usa como 100%).
        val perf = ClockTableCuration.kHzToMHz(sorted.getOrNull(sorted.lastIndex - 1)?.availableFreqsKHz.orEmpty())
        val gpuMHz = ClockTableCuration.hzToMHz(gpu.availableHz)
        val allFromKernel = perf.isNotEmpty() && prime.isNotEmpty() && gpuMHz.isNotEmpty()
        // Eficiência = a política mais lenta, só quando há três ou mais (8 Gen 2, 865).
        val eff = if (sorted.size >= 3) ClockTableCuration.kHzToMHz(sorted.first().availableFreqsKHz) else emptyList()
        val effFallback = if (sorted.size >= 3 || sorted.isEmpty()) fallback.effMHz else emptyList()
        _clockTables.value = ClockTables(
            perfMHz = perf.ifEmpty { fallback.perfMHz },
            primeMHz = prime.ifEmpty { fallback.primeMHz },
            gpuMHz = gpuMHz.ifEmpty { fallback.gpuMHz },
            source = if (allFromKernel) ClockTables.SOURCE_KERNEL else ClockTables.SOURCE_FALLBACK,
            effMHz = eff.ifEmpty { effFallback },
        )
        Log.i(TAG, "${device.name}: ${policies.size} políticas, GPU=${gpu::class.simpleName} (${gpu.availableHz.size} OPPs), PPM=$ppmAvailable, tabelas=${_clockTables.value.source}")
    }

    /**
     * Nos kernels com `cpufreq/boost`, os OPPs boost só são aceites em `scaling_max_freq` com o
     * interruptor a 1. Se estiver a 0, escolher a frequência de topo no chip era ignorado em
     * silêncio (o kernel ficava no último OPP normal) -- o "faltam frequências" do 8 Elite.
     */
    private fun ensureCpuBoostEnabled() {
        val node = "$CPUFREQ_ROOT/boost"
        if (readLocal(node) == "0") {
            executor.executeAsRoot("echo 1 > $node")
            Log.i(TAG, "cpufreq/boost estava a 0 -- ligado para os OPPs de topo")
        }
    }

    /** Varre as políticas de cpufreq do kernel (nunca assume `policy0`/`policy6` fixos). */
    private fun scanCpuPolicies(): List<CpuPolicyNode> = runCatching {
        File(CPUFREQ_ROOT).listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("policy") }
            ?.mapNotNull { dir ->
                val max = readLocal("${dir.absolutePath}/cpuinfo_max_freq")?.toLongOrNull()
                    ?: readLocal("${dir.absolutePath}/scaling_max_freq")?.toLongOrNull()
                    ?: return@mapNotNull null
                CpuPolicyNode(
                    policyPath = dir.absolutePath,
                    cpuinfoMaxFreqKHz = max,
                    // Os OPPs "boost" (os mais altos, em muitos kernels Qualcomm) NÃO aparecem em
                    // scaling_available_frequencies, só em scaling_boost_frequencies -- sem os
                    // juntar faltavam as frequências de topo nos chips (ex: no 8 Elite).
                    // e alguns kernels só os mostram em stats/time_in_state. Junta-se tudo.
                    availableFreqsKHz = PerformanceCommandBuilder.mergeFrequencyTables(
                        available = parseFreqList(readLocal("${dir.absolutePath}/scaling_available_frequencies")),
                        boost = parseFreqList(readLocal("${dir.absolutePath}/scaling_boost_frequencies")),
                        timeInState = PerformanceCommandBuilder.parseTimeInState(readLocal("${dir.absolutePath}/stats/time_in_state")),
                        maxKHz = max,
                        minKHz = readLocal("${dir.absolutePath}/cpuinfo_min_freq")?.toLongOrNull(),
                    ),
                )
            }
            ?.sortedBy { it.policyPath }
            .orEmpty()
    }.getOrElse {
        Log.w(TAG, "scanCpuPolicies: falha a varrer cpufreq", it)
        emptyList()
    }

    private fun createGpuControl(): GpuControl = when (device.gpuBackend) {
        GpuBackend.KGSL -> {
            val live = GpuControl.Kgsl.TABLE_PATHS.asSequence()
                .map { parseFreqList(readValue(it)) }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
            when {
                live.isNotEmpty() -> GpuControl.Kgsl(live)
                File(GpuControl.Kgsl.MAX_PATH).exists() -> GpuControl.Kgsl(device.fallbackTables.gpuMHz.map { it * 1_000_000L })
                else -> GpuControl.None
            }
        }
        GpuBackend.MTK_GED -> {
            // /proc/gpufreq só é legível como root; o nó do teto tem de existir (kernel 4.19).
            val opps = GpuControl.MtkGed.parseOppDump(executor.executeAsRoot("cat ${GpuControl.MtkGed.OPP_DUMP_PATH}").getOrNull())
            val upboundExists = File(GpuControl.MtkGed.UPBOUND_PATH).exists() ||
                executor.executeAsRoot("ls ${GpuControl.MtkGed.UPBOUND_PATH}").getOrNull()?.contains("custom_upbound") == true
            if (opps.isNotEmpty() && upboundExists) GpuControl.MtkGed(opps) else GpuControl.None
        }
    }

    // ------------------------------------------------------------------ daemon

    private suspend fun runDaemon() {
        while (scope.isActive) {
            val t = targets.get()
            try {
                when (t.mode) {
                    PerfMode.STOCK -> {
                        if (appliedGeneration != t.generation) {
                            applyStockOnce()
                            appliedGeneration = t.generation
                        }
                        _autoTdp.value = AutoTdpTelemetry(active = false)
                        waitForWake(null)
                    }

                    PerfMode.CLOCKS -> {
                        enforce(t.perfRatio, t.primeRatio, t.gpuHz, PerformanceCommandBuilder.MANUAL_MIN_RATIO, t.effRatio)
                        appliedGeneration = t.generation
                        _autoTdp.value = AutoTdpTelemetry(active = false)
                        // Re-afirma de vez em quando (um daemon do fabricante pode repor os nós).
                        waitForWake(if (isInteractive()) CLOCKS_REASSERT_MS else IDLE_WAIT_MS)
                    }

                    PerfMode.TDP -> tdpTick(t)
                }
            } catch (e: Exception) {
                Log.w(TAG, "daemon: falha num passo (${t.mode})", e)
                delay(1_000)
            }
        }
    }

    private suspend fun tdpTick(t: PerfTargets) {
        val maxWatts = TdpProfiles.TDP_MAX_WATTS
        val minRatio = PerformanceCommandBuilder.TDP_MIN_RATIO
        val lastWatts = lastTargetWatts
        val bigJump = lastWatts != null && kotlin.math.abs(t.watts - lastWatts) / lastWatts > BIG_TARGET_CHANGE
        if (appliedGeneration != t.generation || autoState == null || bigJump) {
            // Modo novo, ou um salto grande de alvo (ex: 15 W -> 5 W): recomeça do ponto de
            // partida estimado em vez de lá chegar a -15% por segundo. Mantém a medição filtrada.
            autoState = AutoTdpController.initial(t.watts, maxWatts, minRatio).copy(filteredWatts = autoState?.filteredWatts)
            appliedGeneration = t.generation
        }
        lastTargetWatts = t.watts

        if (!isInteractive()) {
            publishTelemetry(t, null, AutoTdpPause.SCREEN_OFF)
            waitForWake(IDLE_WAIT_MS)
            return
        }

        // 4 amostras ao longo de ~1 s (current_now é instantâneo). Sai mais cedo se o alvo mudar.
        val samples = ArrayList<Float>(TDP_SAMPLES)
        repeat(TDP_SAMPLES) {
            readBatteryWatts()?.let { samples += it }
            if (waitForWake(TDP_SAMPLE_INTERVAL_MS) && targets.get().mode != PerfMode.TDP) return
        }
        val current = targets.get()
        if (current.mode != PerfMode.TDP) return

        val measured = samples.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val pause = when {
            isExternallyPowered() -> AutoTdpPause.CHARGING
            measured == null || measured < MIN_PLAUSIBLE_WATTS -> AutoTdpPause.NO_SENSOR
            else -> null
        }
        val state = autoState!!
        val next = if (pause == null) {
            AutoTdpController.step(state, measured!!, current.watts, minRatio, readGpuBusy())
        } else {
            // A carregar (ou sem leitura válida) a bateria não mede o consumo do aparelho:
            // mantém os tetos em que o loop estava em vez de os empurrar para o máximo.
            state
        }
        autoState = next

        val gpuMax = gpu.maxHz ?: Long.MAX_VALUE
        val gpuTarget = if (gpuMax == Long.MAX_VALUE) gpuMax else (gpuMax * next.gpuRatio).toLong()
        enforce(next.cpuRatio, next.cpuRatio, gpuTarget, minRatio)
        publishTelemetry(current, measured, pause)
    }

    private fun publishTelemetry(t: PerfTargets, measured: Float?, pause: AutoTdpPause?) {
        val s = autoState
        _autoTdp.value = AutoTdpTelemetry(
            active = true,
            targetWatts = t.watts,
            measuredWatts = s?.filteredWatts ?: measured,
            cpuPercent = ((s?.cpuRatio ?: 1f) * 100).toInt(),
            gpuPercent = ((s?.gpuRatio ?: 1f) * 100).toInt(),
            paused = pause,
        )
    }

    /** Espera por uma mudança de alvo, até [timeoutMs] (`null` = sem limite). `true` = acordado. */
    private suspend fun waitForWake(timeoutMs: Long?): Boolean =
        if (timeoutMs == null) {
            wake.receive()
            true
        } else {
            withTimeoutOrNull(timeoutMs) { wake.receive() } != null
        }

    // ------------------------------------------------------------------ escrita

    private fun enforce(perfRatio: Float, primeRatio: Float, gpuHz: Long, minRatio: Float, effRatio: Float? = null) {
        if (policies.isEmpty()) policies = scanCpuPolicies()
        val ops = ArrayList<HardwareWriteOp>()
        val cpuOps = PerformanceCommandBuilder.buildCpuFrequencyOps(policies, perfRatio, primeRatio, minRatio, effRatio)
        ops += cpuOps

        // Pisos do firmware: no Thor/RP6 o `pservice` sobe `scaling_min_freq` conforme o modo de
        // desempenho (no "Alto Desempenho" o piso É o máximo de fábrica) e um teto abaixo do piso
        // não pega. Só baixamos o piso quando ele está acima do nosso teto, e sem o trancar.
        cpuOps.forEach { op ->
            val minPath = op.targetPath.replace("scaling_max_freq", "scaling_min_freq")
            val currentMin = readLocal(minPath)?.toLongOrNull() ?: return@forEach
            val target = op.value.toLongOrNull() ?: return@forEach
            if (currentMin > target) ops += HardwareWriteOp(op.label + "-min", minPath, op.value, lockMode = null)
        }

        gpu.opFor(gpuHz, HardwareWriteOp.LOCKED_MODE)?.let { ops += it }

        val failures = ops.mapNotNull { op ->
            runCatching { applyWriteOp(op) }.getOrElse {
                Log.w(TAG, "enforce: ${op.label}", it)
                null
            }
        }.filterNot { it.ok }
        _lastApplyStatus.value = failures

        if (ppmAvailable) applyPpmLimits(cpuOps)
        appliedTargets = targets.get()
    }

    /**
     * MediaTek: o PPM/FPSGO pode passar por cima de `scaling_max_freq`. O teto que o PPM respeita
     * é `hard_userlimit_max_cpu_freq` ("<cluster> <kHz>"). Este nó não se lê de volta no mesmo
     * formato, por isso só escrevemos quando o valor muda.
     */
    private fun applyPpmLimits(cpuOps: List<HardwareWriteOp>) {
        val sorted = policies.sortedBy { it.cpuinfoMaxFreqKHz }
        cpuOps.forEach { op ->
            val cluster = sorted.indexOfFirst { op.targetPath.startsWith(it.policyPath + "/") }
            if (cluster < 0) return@forEach
            val value = "$cluster ${op.value}"
            if (lastPpm[op.targetPath] == value) return@forEach
            executor.executeAsRoot("echo $value > $PPM_HARD_LIMIT")
            lastPpm[op.targetPath] = value
        }
    }

    /** Stock: máximos escritos uma vez, permissões devolvidas, nós entregues ao sistema. */
    private fun applyStockOnce() {
        lastResults.clear()
        backoffUntil.clear()
        val ops = ArrayList<HardwareWriteOp>()
        policies.forEach { node ->
            val path = "${node.policyPath}/scaling_max_freq"
            ops += HardwareWriteOp("cpu-stock", path, node.cpuinfoMaxFreqKHz.toString(), restoreModeFor(path))
        }
        gpu.maxHz?.let { max ->
            when (val g = gpu) {
                is GpuControl.Kgsl -> g.opFor(max, restoreModeFor(GpuControl.Kgsl.MAX_PATH))?.let { ops += it }
                is GpuControl.MtkGed -> ops += HardwareWriteOp("gpu-upbound-opp", GpuControl.MtkGed.UPBOUND_PATH, "0", lockMode = null, verify = false)
                GpuControl.None -> Unit
            }
        }
        val failures = ops.mapNotNull { op -> runCatching { applyWriteOp(op, force = op.lockMode != null) }.getOrNull() }.filterNot { it.ok }
        _lastApplyStatus.value = failures
        if (ppmAvailable) {
            policies.sortedBy { it.cpuinfoMaxFreqKHz }.forEachIndexed { cluster, node ->
                executor.executeAsRoot("echo $cluster ${node.cpuinfoMaxFreqKHz} > $PPM_HARD_LIMIT")
            }
            lastPpm.clear()
        }
        appliedTargets = targets.get()
    }

    /**
     * Permissão a repor num nó ao voltar a Stock: se ficou trancado (0444) por nós -- nesta sessão
     * ou numa anterior que morreu -- devolve-lhe o modo do sistema; se não, não mexe.
     */
    private fun restoreModeFor(path: String): String? {
        val mode = runCatching { Os.stat(path).st_mode and 0x1FF }.getOrNull() ?: return null
        return if (mode == 0x124 /* 0444 */) HardwareWriteOp.DEFAULT_RESTORE_MODE else null
    }

    /** Permissões atuais do nó em octal ("664"), ou `null` se não der para ler. */
    private fun currentModeOf(path: String): String? =
        runCatching { Integer.toOctalString(Os.stat(path).st_mode and 0x1FF) }.getOrNull()?.takeIf { it.length == 3 }

    /**
     * Aplica uma escrita só se for preciso, com verificação e recuo:
     *  0. Lê o nó (leitura direta, sem root). Já tem o valor -> nada a fazer, zero binder.
     *  1. Cadeia `chmod && echo && chmod` numa transação; verifica com [isWriteApplied].
     *  2. Se não pegou, os passos como transações separadas (backends sem `sh -c`).
     *  3. Ainda abaixo do pedido e sem mexer: o kernel está a limitar (térmico/QoS) -- não é
     *     falha da app; lembramos o resultado para não reescrever a cada tick.
     *  Falhas reais entram em espera ([BACKOFF_MS]) em vez de repetirem 8 transações por segundo.
     */
    private fun applyWriteOp(requestedOp: HardwareWriteOp, force: Boolean = false): HardwareNodeStatus {
        val now = System.currentTimeMillis()
        // "Não mexer nas permissões" = desbloquear, escrever e devolver o modo que o nó TINHA
        // (e não deixá-lo a 0666, nem trancá-lo a 0444).
        val op = if (requestedOp.lockMode == null) requestedOp.copy(lockMode = currentModeOf(requestedOp.targetPath) ?: HardwareWriteOp.DEFAULT_RESTORE_MODE) else requestedOp
        val path = op.targetPath
        if (!op.verify) return applyUnverified(op, force, now)
        val before = readValue(path)
        if (before == op.value && !force) return record(HardwareNodeStatus(op.label, path, op.value, before, ok = true), now)

        val requested = op.value.toLongOrNull()
        val beforeNum = before?.toLongOrNull()
        val cached = lastResults[path]
        if (!force && cached != null && cached.first.ok && cached.first.requestedValue == op.value &&
            requested != null && beforeNum != null && beforeNum < requested && now - cached.second < REASSERT_MS
        ) {
            return cached.first // aplicado há pouco e o kernel mantém-no mais abaixo
        }
        val until = backoffUntil[path]
        if (cached != null && until != null && now < until && cached.first.requestedValue == op.value) return cached.first

        executor.executeAsRoot(op.asChainedCommand())
        var after = readValue(path)
        if (isWriteApplied(op.value, before, after) || (!op.isNumeric && after == op.value)) {
            return record(HardwareNodeStatus(op.label, path, op.value, after, ok = true), now)
        }

        op.asSequentialCommands().forEach { executor.executeAsRoot(it) }
        after = readValue(path)
        val afterNum = after?.toLongOrNull()
        val ok = isWriteApplied(op.value, before, after) ||
            (!op.isNumeric && after == op.value) ||
            // Abaixo do pedido mesmo assim: limite térmico/QoS do kernel, não falha da escrita.
            (requested != null && afterNum != null && afterNum < requested) ||
            // Nó que não se lê de volta (sem captura e sem leitura direta): não há como verificar.
            (after == null && !executor.outputCaptureWorks)

        val status = HardwareNodeStatus(op.label, path, op.value, after, ok)
        if (!ok) {
            backoffUntil[path] = now + BACKOFF_MS
            Log.w(TAG, "applyWriteOp: falha em ${op.label} ($path): pedido=${op.value} antes=$before depois=$after")
        }
        return record(status, now)
    }

    /** Nós sem leitura de volta fiável: escreve só quando o valor pedido muda. */
    private fun applyUnverified(op: HardwareWriteOp, force: Boolean, now: Long): HardwareNodeStatus {
        val cached = lastResults[op.targetPath]
        if (!force && cached != null && cached.first.requestedValue == op.value) return cached.first
        val ok = executor.executeAsRoot(op.asChainedCommand()).isSuccess
        return record(HardwareNodeStatus(op.label, op.targetPath, op.value, null, ok), now)
    }

    private fun record(status: HardwareNodeStatus, now: Long): HardwareNodeStatus {
        lastResults[status.path] = status to now
        if (status.ok) backoffUntil.remove(status.path)
        return status
    }

    private val HardwareWriteOp.isNumeric: Boolean get() = value.toLongOrNull() != null

    // ------------------------------------------------------------------ leitura

    /** Leitura direta (os nós de cpufreq/kgsl são legíveis por qualquer app); root só se falhar. */
    private fun readValue(path: String): String? =
        readLocal(path) ?: if (executor.outputCaptureWorks) executor.executeAsRoot("cat $path").getOrNull()?.trim() else null

    private fun readLocal(path: String): String? = runCatching { File(path).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun parseFreqList(raw: String?): List<Long> =
        raw.orEmpty().trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }.filter { it > 0 }.distinct().sorted()

    private fun readBatteryWatts(): Float? {
        val current = readLocal("$BATTERY/current_now")?.toLongOrNull()
        val voltage = readLocal("$BATTERY/voltage_now")?.toLongOrNull()
        if (current != null && voltage != null) return AutoTdpController.batteryWatts(current, voltage).takeIf { it > 0f }
        // Sysfs da bateria bloqueado para apps (AYN Thor): API do Android.
        return BatteryReader.read(context).watts
    }

    /**
     * A bateria só mede o consumo do aparelho quando é ELA a alimentá-lo. A carregar, cheia ou em
     * "Not charging" (separação de carga / bypass ligado à corrente) a medição não serve.
     */
    private fun isExternallyPowered(): Boolean {
        val status = readLocal("$BATTERY/status")?.lowercase()
            ?: return BatteryReader.read(context).externallyPowered ?: false
        return status != "discharging" && status != "unknown"
    }

    private fun readGpuBusy(): Int? = gpuBusyPercent()

    /**
     * Ocupação da GPU (0-100). Leitura direta; se o SELinux a bloquear (AYN Thor:
     * `gpu_busy_percentage` ilegível para apps), pelo PServer -- e lembra-se de qual funcionou.
     */
    fun gpuBusyPercent(): Int? {
        val path = gpu.busyPath ?: return null
        if (!gpuBusyNeedsRoot) {
            val direct = readLocal(path)
            if (direct != null) return gpu.parseBusy(direct)
            gpuBusyNeedsRoot = true
        }
        if (!executor.outputCaptureWorks) return null
        return gpu.parseBusy(executor.executeAsRoot("cat $path").getOrNull()?.trim())
    }

    @Volatile private var gpuBusyNeedsRoot = false

    private fun isInteractive(): Boolean = powerManager?.isInteractive ?: true

    private companion object {
        const val TAG = "PerformanceManager"
        const val CPUFREQ_ROOT = "/sys/devices/system/cpu/cpufreq"
        const val BATTERY = "/sys/class/power_supply/battery"
        const val PPM_HARD_LIMIT = "/proc/ppm/policy/hard_userlimit_max_cpu_freq"

        const val TDP_SAMPLES = 4

        /** Mudança relativa de alvo a partir da qual o AutoTDP recomeça da estimativa inicial. */
        const val BIG_TARGET_CHANGE = 0.15f
        const val TDP_SAMPLE_INTERVAL_MS = 250L

        /** Abaixo disto a "medição" é ruído (ou o aparelho está ligado à corrente). */
        const val MIN_PLAUSIBLE_WATTS = 0.5f

        const val CLOCKS_REASSERT_MS = 5_000L
        const val IDLE_WAIT_MS = 15_000L
        const val REASSERT_MS = 30_000L
        const val BACKOFF_MS = 30_000L
    }
}
