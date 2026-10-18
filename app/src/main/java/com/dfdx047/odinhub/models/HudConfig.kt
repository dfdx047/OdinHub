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
    ;

    fun label(isEn: Boolean): String = if (isEn) labelEn else labelPt

    /** Precisa de root (PServer) a cada segundo: FPS/frame time (SurfaceFlinger) e uso da CPU (/proc/stat). */
    val needsRoot: Boolean get() = isFps || this == CPU_USAGE

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
    /** 0.8 / 1.0 / 1.25 -- tamanho do texto. */
    val textScale: Float = 1f,
    /** Opacidade do fundo do HUD (0..1). */
    val backgroundOpacity: Float = 0.75f,
) {
    fun has(metric: HudMetric): Boolean = metric in metrics

    /** Alguma métrica ligada precisa de root a cada segundo? */
    val needsRoot: Boolean get() = metrics.any { it.needsRoot }

    companion object {
        val TEXT_SCALES = listOf(0.8f, 1f, 1.25f)

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

    /** /proc/meminfo -> (usada, total) em MB, com MemAvailable. */
    fun parseMeminfo(raw: String?): Pair<Int, Int>? {
        if (raw.isNullOrBlank()) return null
        fun kb(key: String) = Regex("(?m)^$key:\\s+(\\d+)").find(raw)?.groupValues?.get(1)?.toLongOrNull()
        val total = kb("MemTotal") ?: return null
        val available = kb("MemAvailable") ?: return null
        return ((total - available) / 1024).toInt() to (total / 1024).toInt()
    }

    /** Temperatura de um nó de sysfs: millicelsius (45000), decicelsius (450) ou celsius (45). */
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
