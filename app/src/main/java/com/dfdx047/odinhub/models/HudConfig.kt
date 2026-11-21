package com.dfdx047.odinhub.models

/** Uma métrica do HUD de desempenho. O id é o que fica gravado nas preferências. */
enum class HudMetric(val id: String, val labelEn: String, val labelPt: String) {
    FPS("fps", "FPS", "FPS"),
    FRAMETIME("frametime", "Frame time", "Frame time"),
    FPS_LOW("fps_low", "1% low FPS", "FPS 1% low"),
    FPS_GRAPH("fps_graph", "Frame time graph", "Gráfico de frame time"),
    CPU_USAGE("cpu_usage", "CPU load", "Uso da CPU"),
    CPU_TEMP("cpu_temp", "CPU temperature", "Temperatura da CPU"),
    CPU_CLUSTERS("cpu_clusters", "CPU clusters (MHz)", "Clusters da CPU (MHz)"),
    CPU_CORES("cpu_cores", "Every core (MHz)", "Todos os núcleos (MHz)"),
    GPU_LOAD("gpu_load", "GPU load", "Uso da GPU"),
    GPU_TEMP("gpu_temp", "GPU temperature", "Temperatura da GPU"),
    GPU_FREQ("gpu_freq", "GPU frequency", "Frequência da GPU"),
    RAM("ram", "RAM", "RAM"),
    POWER("power", "Power draw (W)", "Consumo (W)"),
    BATTERY("battery", "Battery %", "Bateria %"),
    BATTERY_TEMP("battery_temp", "Battery temperature", "Temperatura da bateria"),
    TDP("tdp", "TDP target", "Alvo de TDP"),
    CLOCK("clock", "Time", "Hora"),
    /** Perfil guardado em uso (o ligado ao jogo, ou o global ativo). */
    PROFILE("profile", "Active profile", "Perfil ativo"),
    ;

    fun label(isEn: Boolean): String = if (isEn) labelEn else labelPt

    /** Precisa de root (PServer) a cada segundo: FPS/frame time (SurfaceFlinger) e uso da CPU (/proc/stat). */
    // CPU_USAGE usa /proc/stat por root quando dá, mas tem um recurso sem root (cpuidle).
    val needsRoot: Boolean get() = isFps

    /** Métricas que vêm dos tempos de apresentação dos frames (SurfaceFlinger). */
    val isFps: Boolean get() = this == FPS || this == FRAMETIME || this == FPS_GRAPH || this == FPS_LOW

    companion object {
        fun fromId(id: String): HudMetric? = entries.firstOrNull { it.id == id }
        val DEFAULT: Set<HudMetric> = linkedSetOf(FPS, FRAMETIME, CPU_USAGE, CPU_TEMP, GPU_LOAD, GPU_TEMP, RAM, POWER, BATTERY)
    }
}

/** Barra horizontal (no topo/fundo, como a do MangoHud em modo horizontal) ou coluna vertical. */
enum class HudLayout(val labelEn: String, val labelPt: String) {
    HORIZONTAL("Horizontal bar", "Barra horizontal"),
    VERTICAL("Vertical panel", "Painel vertical"),
    ;

    fun label(isEn: Boolean): String = if (isEn) labelEn else labelPt
}

/**
 * Estilo visual do HUD. [APP] segue o tema da app (cores, vidro, AMOLED); os outros são fixos,
 * pensados para se lerem por cima de qualquer jogo.
 */
enum class HudStyle(val labelEn: String, val labelPt: String) {
    APP("App theme", "Tema da app"),
    /** Cores por grupo como as predefinições do MangoHud (FPS, CPU, GPU, RAM...), fundo preto. */
    MANGO("MangoHud", "MangoHud"),
    /** Sem fundo: só texto branco com sombra forte. */
    MINIMAL("Minimal", "Minimal"),
    /** Fundo escuro, texto ciano/magenta com brilho. */
    NEON("Neon", "Neon"),
    /** Verde monoespaçado sobre preto. */
    TERMINAL("Terminal", "Terminal"),
    ;

    fun label(isEn: Boolean): String = if (isEn) labelEn else labelPt
}

/** Canto/borda onde o HUD fica (o utilizador pode depois arrastá-lo). */
enum class HudPosition(val labelEn: String, val labelPt: String) {
    TOP_LEFT("Top left", "Topo esq."),
    TOP_CENTER("Top center", "Topo centro"),
    TOP_RIGHT("Top right", "Topo dir."),
    BOTTOM_LEFT("Bottom left", "Baixo esq."),
    BOTTOM_RIGHT("Bottom right", "Baixo dir."),
    ;

    fun label(isEn: Boolean): String = if (isEn) labelEn else labelPt
}

/**
 * Configuração do HUD de desempenho (o overlay pequeno com FPS, temperaturas, frequências...).
 * Tudo é opcional: cada métrica liga/desliga individualmente.
 */
data class HudConfig(
    val enabled: Boolean = false,
    val layout: HudLayout = HudLayout.HORIZONTAL,
    val position: HudPosition = HudPosition.TOP_LEFT,
    val metrics: Set<HudMetric> = HudMetric.DEFAULT,
    /** Tamanho do HUD ([MIN_SCALE]..[MAX_SCALE]); muda-se no cartão ou com pinça/canto no próprio HUD. */
    val textScale: Float = 1f,
    /** Opacidade do fundo do HUD (0..1). */
    val backgroundOpacity: Float = 0.75f,
    val style: HudStyle = HudStyle.APP,
) {
    fun has(metric: HudMetric): Boolean = metric in metrics

    /** Alguma métrica ligada precisa de root a cada segundo? */
    val needsRoot: Boolean get() = metrics.any { it.needsRoot }

    companion object {
        val TEXT_SCALES = listOf(0.8f, 1f, 1.25f)
        const val MIN_SCALE = 0.8f
        const val MAX_SCALE = 2.5f

        /** Tamanho válido: dentro dos limites e arredondado a 0.05 (não grava 1.2371...). */
        fun clampScale(scale: Float): Float =
            (Math.round(scale.coerceIn(MIN_SCALE, MAX_SCALE) * 20f) / 20f).coerceIn(MIN_SCALE, MAX_SCALE)

        fun encodeMetrics(metrics: Set<HudMetric>): String = HudMetric.entries.filter { it in metrics }.joinToString(",") { it.id }

        /** Inverso de [encodeMetrics]; ids desconhecidos (de versões futuras) são ignorados. */
        fun decodeMetrics(raw: String?): Set<HudMetric> =
            if (raw == null) HudMetric.DEFAULT
            else raw.split(',').mapNotNull { HudMetric.fromId(it.trim()) }.toCollection(linkedSetOf())
    }
}

/** Uma leitura do HUD. `null` = métrica indisponível neste aparelho (ou desligada). */
data class HudSnapshot(
    val fps: Float? = null,
    /** Frame time médio do último segundo, em ms. */
    val frametimeMs: Float? = null,
    /** FPS "1% low" dos últimos ~30 s (o percentil 99 dos frame times). */
    val fpsLow: Float? = null,
    /** Histórico recente de frame time (ms), do mais antigo para o mais recente, para o gráfico. */
    val frametimeHistory: List<Float> = emptyList(),
    val cpuUsagePercent: Int? = null,
    val cpuTempC: Float? = null,
    /** Frequência atual de cada cluster (MHz), do mais lento para o mais rápido. */
    val clusterMHz: List<Int> = emptyList(),
    /** Frequência de cada núcleo (MHz), cpu0..cpuN; 0 = núcleo desligado. */
    val coreMHz: List<Int> = emptyList(),
    val gpuLoadPercent: Int? = null,
    val gpuTempC: Float? = null,
    val gpuMHz: Int? = null,
    val ramUsedMB: Int? = null,
    val ramTotalMB: Int? = null,
    val powerW: Float? = null,
    val charging: Boolean = false,
    val batteryPercent: Int? = null,
    val batteryTempC: Float? = null,
    val tdpTargetW: Float? = null,
    /** Nome do perfil em uso; "" = ajustes sem perfil; `null` = métrica desligada. */
    val profileName: String? = null,
    /** O perfil vem do jogo (ligado a ele) e não do global. */
    val profileFromGame: Boolean = false,
)

/** Cálculos puros do HUD (testáveis sem Android). */
object HudMath {
    private const val INT64_MAX = Long.MAX_VALUE

    /**
     * `dumpsys SurfaceFlinger --latency <camada>`: 1.ª linha = período de refresh; depois linhas
     * `desejado apresentado pronto` (ns). Devolve a coluna 2 (momento REAL em que o frame foi para
     * o ecrã), sem as linhas ainda pendentes (0 ou INT64_MAX).
     */
    fun parseLatencyPresents(output: String?): List<Long> =
        output.orEmpty().lines().drop(1).mapNotNull { line ->
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 3) return@mapNotNull null
            cols[1].toLongOrNull()?.takeIf { it > 0 && it != INT64_MAX }
        }

    /**
     * Camadas candidatas do jogo em `dumpsys SurfaceFlinger --list`, a melhor primeiro. Os jogos
     * desenham normalmente numa `SurfaceView[pkg/atividade](BLAST)#id`; emuladores/NativeActivity
     * às vezes na própria janela `pkg/atividade#id`. No Android 14+ os nomes vêm embrulhados em
     * `RequestedLayerState{nome parentId=... }`.
     */
    fun layerCandidates(listOutput: String?, pkg: String): List<String> {
        if (pkg.isBlank()) return emptyList()
        return listOutput.orEmpty().lines()
            .map { it.trim() }
            .filter { it.contains(pkg) }
            .map { line ->
                if (line.startsWith("RequestedLayerState{")) {
                    line.removePrefix("RequestedLayerState{").substringBefore(" parentId=").substringBefore(" z=").removeSuffix("}")
                } else line
            }
            .filterNot { n -> EXCLUDED_LAYERS.any { n.contains(it) } }
            .distinct()
            .sortedBy { n ->
                when {
                    n.startsWith("SurfaceView[") && n.contains("(BLAST)") -> 0
                    n.startsWith("SurfaceView") -> 1
                    n.contains("#") -> 2
                    else -> 3
                }
            }
    }

    private val EXCLUDED_LAYERS = listOf("ActivityRecordInputSink", "Background for SurfaceView", "ActivityRecord{", "Bounds for -", "Splash Screen", "WindowToken")

    /** Página-flips do SurfaceFlinger (`service call SurfaceFlinger 1013`) -> contagem. */
    fun parsePageFlips(serviceCallOutput: String?): Long? {
        if (serviceCallOutput.isNullOrBlank()) return null
        val hex = Regex("Parcel\\(\\s*([0-9a-fA-F]{8})").find(serviceCallOutput)?.groupValues?.get(1) ?: return null
        return hex.toLongOrNull(16)
    }

    /** FPS a partir de duas contagens de page-flips separadas por [elapsedMs]. */
    fun fps(previousFlips: Long, currentFlips: Long, elapsedMs: Long): Float? {
        if (elapsedMs <= 0) return null
        val delta = currentFlips - previousFlips
        if (delta < 0) return null // contador reiniciou (ex: SurfaceFlinger reiniciado)
        return delta * 1000f / elapsedMs
    }

    /** Linha "cpu ..." de /proc/stat -> (ocupado, total) em jiffies. */
    fun parseCpuStat(line: String?): Pair<Long, Long>? {
        val parts = line?.trim()?.split(Regex("\\s+")) ?: return null
        if (parts.isEmpty() || parts[0] != "cpu") return null
        val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (values.size < 4) return null
        val idle = values[3] + values.getOrElse(4) { 0L } // idle + iowait
        val total = values.take(8).sum()
        return (total - idle) to total
    }

    fun cpuUsage(previous: Pair<Long, Long>, current: Pair<Long, Long>): Int? {
        val total = current.second - previous.second
        if (total <= 0) return null
        val busy = current.first - previous.first
        return ((busy * 100) / total).toInt().coerceIn(0, 100)
    }

    /**
     * Uso da CPU sem root, pelo tempo em idle de cada núcleo
     * (`/sys/devices/system/cpu/cpuN/cpuidle/stateK/time`, em µs, legível por apps).
     * [idleDeltaUs] = idle somado de todos os núcleos ligados no intervalo; [wallUs] = intervalo.
     */
    fun cpuUsageFromIdle(idleDeltaUs: Long, wallUs: Long, onlineCores: Int): Int? {
        if (wallUs <= 0 || onlineCores <= 0 || idleDeltaUs < 0) return null
        val capacity = wallUs * onlineCores
        return (100 - (idleDeltaUs * 100 / capacity)).toInt().coerceIn(0, 100)
    }

    /** /proc/meminfo -> (usada, total) em MB, com MemAvailable. */
    /**
     * Consumo do aparelho em watts e se há carregador.
     *  - Na tomada: o que entra pelo USB (`usb/voltage_now` x `usb/input_current_now`) MENOS o que
     *    entra na bateria. No RP5 a corrente da bateria é positiva a carregar e negativa a
     *    descarregar (docs/device-rp5.md §4), por isso: USB - I_bat x V_bat. Assim conta também a
     *    bateria a ajudar o carregador em carga (3DMark: USB 2,4 W e bateria a -1,3 A).
     *    Com o bypass a bateria fica perto de 0 e o consumo é o do USB.
     *  - Só na bateria: |corrente| x tensão da bateria.
     * Unidades: µA/µV (alguns kernels dão mA; o mesmo critério do [AutoTdpController.batteryWatts]).
     */
    fun systemPower(batteryCurrent: Long?, batteryVoltageUv: Long?, usbInputCurrent: Long?, usbVoltageUv: Long?): Pair<Float, Boolean>? {
        fun amps(raw: Long): Float = if (kotlin.math.abs(raw) in 1 until 20_000) raw / 1_000f else raw / 1_000_000f
        val battery = if (batteryCurrent != null && batteryVoltageUv != null && batteryVoltageUv > 0)
            amps(batteryCurrent) * (batteryVoltageUv / 1_000_000f) else null
        if (usbInputCurrent != null && usbVoltageUv != null && usbVoltageUv >= USB_MIN_UV) {
            val a = amps(usbInputCurrent)
            if (a >= USB_MIN_A) {
                val usb = a * (usbVoltageUv / 1_000_000f)
                // Sinal ao contrário noutro kernel daria um valor sem sentido: fica só o USB.
                val total = battery?.let { usb - it }?.takeIf { it > 0f } ?: usb
                return total to true
            }
        }
        return battery?.let { kotlin.math.abs(it) to false }
    }

    /** Abaixo disto não há carregador (ruído do sensor com o cabo fora). */
    private const val USB_MIN_UV = 4_000_000L
    private const val USB_MIN_A = 0.05f

    fun parseMeminfo(raw: String?): Pair<Int, Int>? {
        if (raw.isNullOrBlank()) return null
        fun kb(key: String) = Regex("(?m)^$key:\\s+(\\d+)").find(raw)?.groupValues?.get(1)?.toLongOrNull()
        val total = kb("MemTotal") ?: return null
        val available = kb("MemAvailable") ?: return null
        return ((total - available) / 1024).toInt() to (total / 1024).toInt()
    }

    /** Temperatura de um nó de sysfs: millicelsius (45000), decicelsius (450) ou celsius (45). */
    /**
     * Saída de `grep -H . <ficheiros>` ("caminho:valor" por linha) -> mapa caminho -> valor.
     * Serve para ler VÁRIOS nós por root numa só chamada sem depender da ordem (um nó ilegível
     * simplesmente não aparece, em vez de baralhar os restantes como faria um `cat a b c`).
     */
    fun parseGrepH(output: String?): Map<String, String> =
        output.orEmpty().lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("/") && it.contains(':') }
            .associate { it.substringBefore(':') to it.substringAfter(':').trim() }

    /** Papel de uma zona térmica pelo seu `type`: CPU, GPU ou nenhum. */
    fun zoneRole(type: String?): String? {
        val t = type?.trim()?.lowercase() ?: return null
        // "gpu-skin-avg-step" (RP5) é a temperatura da CARCAÇA perto da GPU, não da GPU.
        if (t.contains("skin")) return null
        return when {
            CPU_ZONE.containsMatchIn(t) -> "CPU"
            GPU_ZONE.containsMatchIn(t) -> "GPU"
            else -> null
        }
    }

    // 8 Elite: cpu-0-0-0, cpuss-0. 8 Gen 2 / 865: cpu-1-0-0, cpu-0-3-usr, cpuss-1, cpullc. Kernels
    // antigos: cpu0-silver-usr, cpu4-gold. MediaTek: mtktscpu, cpu_big, cpu_little.
    private val CPU_ZONE = Regex("^(cpu|cpuss|cpullc|mtktscpu|apc|silver|gold|prime)")
    // Adreno: gpuss-0, gpu-0-usr, gpu0, kgsl. MediaTek: mtktsgpu, gpu_.
    private val GPU_ZONE = Regex("^(gpu|gpuss|mtktsgpu|kgsl)")

    fun parseTempC(raw: String?): Float? {
        val v = raw?.trim()?.toLongOrNull() ?: return null
        return when {
            v > 1000 -> v / 1000f
            v > 150 -> v / 10f
            v > 0 -> v.toFloat()
            else -> null
        }
    }
}

/**
 * Acumula os tempos de apresentação dos frames entre leituras (cada `--latency` devolve os ~127
 * mais recentes, com repetições) e calcula FPS, frame time e 1% low. Puro e testável.
 */
class FpsTracker(private val bufferNs: Long = 30_000_000_000L) {
    private val presents = ArrayDeque<Long>()

    /** Junta uma leitura nova (só os frames posteriores ao último já visto). */
    fun add(newPresents: List<Long>) {
        val last = presents.lastOrNull() ?: Long.MIN_VALUE
        newPresents.filter { it > last }.sorted().forEach { presents.addLast(it) }
        val newest = presents.lastOrNull() ?: return
        while (presents.isNotEmpty() && newest - presents.first() > bufferNs) presents.removeFirst()
    }

    fun clear() = presents.clear()

    /** Último frame apresentado (ns), para saber se o jogo está parado. */
    val newest: Long? get() = presents.lastOrNull()

    /** FPS na última janela de [windowNs] (por omissão 1 s). */
    fun fps(windowNs: Long = 1_000_000_000L): Float? {
        val newest = presents.lastOrNull() ?: return null
        val recent = presents.filter { newest - it <= windowNs }
        if (recent.size < 2) return null
        val span = recent.last() - recent.first()
        if (span <= 0) return null
        return (recent.size - 1) * 1e9f / span
    }

    /** Tempo de cada um dos últimos [count] frames (ms), do mais antigo para o mais recente. */
    fun recentFrameTimesMs(count: Int): List<Float> =
        presents.toList().takeLast(count + 1).zipWithNext { a, b -> (b - a) / 1e6f }.filter { it > 0f }

    /** Frame time médio da última janela, em ms. */
    fun frametimeMs(windowNs: Long = 1_000_000_000L): Float? = fps(windowNs)?.takeIf { it > 0f }?.let { 1000f / it }

    /** FPS 1% low: 1 s / percentil 99 dos intervalos entre frames no buffer (precisa de >= 30 frames). */
    fun onePercentLow(): Float? {
        if (presents.size < 31) return null
        val gaps = presents.zipWithNext { a, b -> b - a }.filter { it > 0 }.sorted()
        if (gaps.isEmpty()) return null
        val p99 = gaps[((gaps.size - 1) * 0.99).toInt()]
        return 1e9f / p99
    }
}

/**
 * Predefinições e grupos de métricas do HUD, usados pelo separador "HUD" do painel rápido e pelo
 * cartão do HUD. Puro (sem Android) para os testes.
 */
object HudPresets {
    data class Preset(val id: String, val labelEn: String, val labelPt: String, val metrics: Set<HudMetric>) {
        fun label(isEn: Boolean): String = if (isEn) labelEn else labelPt
    }

    data class Group(val labelEn: String, val labelPt: String, val metrics: List<HudMetric>) {
        fun label(isEn: Boolean): String = if (isEn) labelEn else labelPt
    }

    val FPS_ONLY = Preset("fps", "FPS only", "Só FPS", linkedSetOf(HudMetric.FPS))
    val ESSENTIAL = Preset(
        "essential", "Essential", "Essencial",
        linkedSetOf(HudMetric.FPS, HudMetric.CPU_TEMP, HudMetric.GPU_TEMP, HudMetric.POWER, HudMetric.BATTERY),
    )
    val STANDARD = Preset("standard", "Standard", "Padrão", HudMetric.DEFAULT)
    val FULL = Preset("full", "Full", "Completo", HudMetric.entries.toCollection(linkedSetOf()))

    val all: List<Preset> = listOf(FPS_ONLY, ESSENTIAL, STANDARD, FULL)

    val groups: List<Group> = listOf(
        Group("FRAMES", "QUADROS", listOf(HudMetric.FPS, HudMetric.FRAMETIME, HudMetric.FPS_LOW, HudMetric.FPS_GRAPH)),
        Group("CPU", "CPU", listOf(HudMetric.CPU_USAGE, HudMetric.CPU_TEMP, HudMetric.CPU_CLUSTERS, HudMetric.CPU_CORES)),
        Group("GPU", "GPU", listOf(HudMetric.GPU_LOAD, HudMetric.GPU_TEMP, HudMetric.GPU_FREQ)),
        Group("SYSTEM", "SISTEMA", listOf(HudMetric.PROFILE, HudMetric.RAM, HudMetric.POWER, HudMetric.TDP, HudMetric.BATTERY, HudMetric.BATTERY_TEMP, HudMetric.CLOCK)),
    )

    /** Liga/desliga uma métrica, mantendo a ordem do enum (a ordem em que o HUD as desenha). */
    fun toggle(metrics: Set<HudMetric>, metric: HudMetric): Set<HudMetric> =
        HudMetric.entries.filter { if (it == metric) it !in metrics else it in metrics }.toCollection(linkedSetOf())

    /** Rótulo curto para os chips do painel (390 dp de largura). */
    fun shortLabel(metric: HudMetric, isEn: Boolean): String = when (metric) {
        HudMetric.FPS -> "FPS"
        HudMetric.FRAMETIME -> "Frame time"
        HudMetric.FPS_LOW -> "1% low"
        HudMetric.FPS_GRAPH -> if (isEn) "Graph" else "Gráfico"
        HudMetric.CPU_USAGE -> if (isEn) "Load" else "Uso"
        HudMetric.CPU_TEMP, HudMetric.GPU_TEMP -> if (isEn) "Temp" else "Temp."
        HudMetric.CPU_CLUSTERS -> "Clusters"
        HudMetric.CPU_CORES -> if (isEn) "Cores" else "Núcleos"
        HudMetric.GPU_LOAD -> if (isEn) "Load" else "Uso"
        HudMetric.GPU_FREQ -> "MHz"
        HudMetric.RAM -> "RAM"
        HudMetric.POWER -> if (isEn) "Watts" else "Watts"
        HudMetric.BATTERY -> if (isEn) "Battery" else "Bateria"
        HudMetric.BATTERY_TEMP -> if (isEn) "Batt. temp" else "Temp. bat."
        HudMetric.TDP -> "TDP"
        HudMetric.CLOCK -> if (isEn) "Time" else "Hora"
        HudMetric.PROFILE -> if (isEn) "Profile" else "Perfil"
    }
}
