package com.dfdx047.odinhub.tools.hardware

/**
 * Representa uma política de cpufreq descoberta dinamicamente no kernel (ex: um cluster
 * "policyN" em /sys/devices/system/cpu/cpufreq). Não depende de [java.io.File] nem de qualquer
 * classe do Android, para que possa ser criada livremente em testes JVM puros.
 */
data class CpuPolicyNode(
    val policyPath: String,
    val cpuinfoMaxFreqKHz: Long,
    /**
     * Frequências que esta política REALMENTE aceita (a tabela de OPPs do kernel, lida de
     * `scaling_available_frequencies`). Vazia quando o nó não pôde ser lido.
     *
     * Sem isto escrevíamos valores arbitrários (`max * ratio`, ex: 1 071 000 kHz) que não existem
     * na tabela; o kernel aceitava a escrita mas assentava na frequência suportada mais próxima,
     * a leitura de verificação não batia certo com o pedido, e o resultado era reportado como
     * FALHA -- daí os avisos "Não foi possível aplicar: cpu-perf-policy-0" a aparecerem sem parar
     * enquanto, na prática, o limite estava a ser aplicado.
     */
    val availableFreqsKHz: List<Long> = emptyList(),
)

/**
 * Encaixa um alvo na lista de valores realmente suportados, escolhendo o maior valor que não
 * ultrapassa o alvo (é um LIMITE máximo: arredondar para cima furaria o limite pedido). Se o alvo
 * ficar abaixo de tudo, usa o menor suportado. Sem lista, devolve o alvo inalterado.
 */
fun snapToSupported(target: Long, supported: List<Long>, toleranceAbove: Long = SNAP_TOLERANCE): Long {
    if (supported.isEmpty()) return target
    return supported.filter { it <= target + toleranceAbove }.maxOrNull() ?: supported.min()
}

/**
 * Folga (na mesma unidade da tabela) acima do alvo que ainda conta como "o mesmo OPP".
 *
 * BUG QUE ISTO CORRIGE: a UI mostra MHz inteiros (3532800 kHz aparece como "3532 MHz") e devolve
 * 3532000 kHz. Com a comparação estrita `<=`, esse pedido caía no OPP ABAIXO (3321600) -- escolher
 * o chip do máximo aplicava o penúltimo valor da tabela. 999 cobre o truncamento kHz->MHz (e os
 * arredondamentos do ratio em Float) sem nunca saltar para o OPP seguinte, que está sempre a mais
 * de 100 MHz de distância.
 */
const val SNAP_TOLERANCE = 999L

/**
 * Uma escrita atómica num nó de sysfs, desenhada à volta do padrão "desbloquear (666) -> escrever
 * -> bloquear (444)" usado pelo Pulse/ClusterTune para conseguir escrever em nós protegidos pelo
 * kernel sem deixá-los permanentemente abertos a outros processos.
 *
 * Expomos os três passos separadamente (em vez de só a string encadeada) porque descobrimos que
 * encadear tudo com `&&` numa única transação binder tem duas falhas graves:
 *  1. Se qualquer nó no meio da cadeia for rejeitado pelo kernel (comum em GPUs/clusters
 *     bloqueados por bootloader), o `&&` aborta TODOS os passos seguintes — incluindo nós que
 *     não têm nada a ver com a falha. Isto por si só explica o comportamento "instável": qual
 *     nó falha primeiro depende da ordem de iteração do diretório, por isso o resultado parece
 *     aleatório de boot para boot.
 *  2. Alguns backends de shell privilegiado (dependendo de como o PServer invoca o processo)
 *     podem não suportar operadores de shell (`&&`, `>`) quando o comando é entregue como um
 *     único argumento — funcionam bem para comandos simples (`getprop`, `settings put`) mas
 *     falham silenciosamente em cadeias compostas.
 *
 * [PerformanceManager] tenta primeiro a cadeia completa (mais rápida, 1 transação) e, se a
 * verificação de leitura falhar, recua para os três passos como transações binder separadas.
 */
data class HardwareWriteOp(
    val label: String,
    val targetPath: String,
    val value: String,
    /**
     * Permissões a pôr no nó depois de escrever: "444" (trancado -- o perf HAL do fabricante não
     * consegue repor o valor), o modo original (ao voltar a Stock, para devolver o nó ao sistema),
     * ou `null` para não mexer (ex: `scaling_min_freq`, que o pservice do firmware tem de poder
     * continuar a escrever).
     */
    val lockMode: String? = LOCKED_MODE,
    /**
     * `false` para nós que não se leem de volta no mesmo formato (ex: o índice de OPP do GED da
     * MediaTek): escreve-se só quando o valor pedido muda, sem verificação por leitura.
     */
    val verify: Boolean = true,
) {
    init {
        // Defesa em profundidade: isto é concatenado num comando root. Hoje só chegam caminhos de
        // sysfs/proc e números, mas um valor estranho deve falhar alto em vez de ir parar ao shell.
        require(SAFE_PATH.matches(targetPath)) { "caminho inseguro: $targetPath" }
        require(SAFE_VALUE.matches(value)) { "valor inseguro: $value" }
        require(lockMode == null || SAFE_MODE.matches(lockMode)) { "modo inseguro: $lockMode" }
    }

    /** Só se desbloqueia quando se volta a pôr um modo depois -- nunca deixar um nó a 0666. */
    val unlockCommand: String? get() = lockMode?.let { "chmod 666 $targetPath" }
    val writeCommand: String get() = "echo $value > $targetPath"
    val lockCommand: String? get() = lockMode?.let { "chmod $it $targetPath" }

    /** Comando único e linear, usado como tentativa rápida quando o backend suporta `sh -c`. */
    fun asChainedCommand(): String = listOfNotNull(unlockCommand, writeCommand, lockCommand).joinToString(" && ")

    /** Os passos como comandos independentes, para execução sequencial e resiliente. */
    fun asSequentialCommands(): List<String> = listOfNotNull(unlockCommand, writeCommand, lockCommand)

    companion object {
        const val LOCKED_MODE = "444"

        /** Modo a repor quando não sabemos o original: o que os init.rc da Qualcomm usam (system:system 0664). */
        const val DEFAULT_RESTORE_MODE = "664"

        private val SAFE_PATH = Regex("/[A-Za-z0-9_./-]+")
        private val SAFE_VALUE = Regex("[A-Za-z0-9_. -]+")
        private val SAFE_MODE = Regex("[0-7]{3,4}")
    }
}

/**
 * Decide se uma escrita "pegou", comparando o pedido com o que o nó tinha ANTES e o que tem
 * DEPOIS. Estes nós são tetos cujos valores têm de existir na tabela de OPPs: ao pedir um valor
 * intermédio o driver assenta no OPP mais próximo, e por isso a leitura nem sempre é igual ao
 * pedido.
 *
 *  - igual, ou dentro de ±2% -> aplicado;
 *  - acima do pedido (> +2%) -> NÃO aplicado (o teto não está em vigor);
 *  - abaixo do pedido -> só conta se o valor MUDOU. A versão antiga aceitava "qualquer valor
 *    abaixo", e isso escondia as falhas ao SUBIR um limite (5 W -> Stock): a escrita não fazia
 *    nada, o valor antigo (mais baixo) passava na verificação e o recuo sequencial nunca corria.
 */
fun isWriteApplied(requested: String, before: String?, after: String?): Boolean {
    if (after == null) return false
    if (after == requested) return true
    val r = requested.toLongOrNull() ?: return false
    val a = after.toLongOrNull() ?: return false
    if (a > r * 102 / 100) return false
    if (a >= r * 98 / 100) return true
    return after != before
}

/**
 * Constrói os comandos de hardware (CPU/GPU) a partir de dados já lidos do kernel, sem tocar em
 * [java.io.File] ou em qualquer API do Android — toda a matemática de clocks/TDP e a formatação
 * dos comandos vive aqui, isolada e testável com JUnit puro.
 */
object PerformanceCommandBuilder {

    const val CPU_MIN_RATIO = 0.30f
    const val CPU_MAX_RATIO = 1.0f

    /**
     * Piso mínimo usado especificamente pelo TDP dinâmico (AutoTDP), separado de [CPU_MIN_RATIO].
     *
     * CAUSA RAIZ do bug "5W não limita, 10W limita": [wattsToRatio] e o passo de convergência em
     * `PerformanceManager.calculateAutoTdpStep` usavam o mesmo piso de 30% que os clocks manuais.
     * Um alvo de 5W (5/25 = 20%) era imediatamente sujeito a `coerceIn(0.30f, 1.0f)` e ficava preso
     * em 30% -- o que ainda deixa a CPU a desenhar bem mais do que 5W reais, por isso o utilizador
     * via "nenhum limite". Um alvo de 10W (10/25 = 40%) já ficava acima do piso e conseguia
     * convergir normalmente, por isso "funcionava". Um piso mais baixo dá ao loop fechado
     * (estilo Pulse, ver `calculateAutoTdpStep`) espaço real para descer até alvos baixos.
     */
    const val TDP_MIN_RATIO = 0.12f

    /**
     * Piso dos clocks MANUAIS. Cada chip da UI é um OPP real do SoC, escolhido de propósito pelo
     * utilizador -- com o piso antigo de 30% os chips mais baixos da tabela (ex: 384 MHz no Perf,
     * 1017 MHz no Prime) eram silenciosamente puxados para cima e não faziam nada.
     */
    const val MANUAL_MIN_RATIO = 0.05f

    /**
     * Gera uma escrita de `scaling_max_freq` por cada política de CPU descoberta em tempo de
     * execução (varredura dinâmica, ao estilo Pulse: nunca assume `policy0`/`policy6` fixos,
     * porque o mapeamento de clusters varia por SoC e por firmware).
     *
     * A política com a maior frequência máxima é sempre tratada como o cluster "Prime"; todas as
     * outras recebem o ratio "Perf". Isto reflete topologias 1+3+4, 1+5+2, etc. sem hardcoding.
     */
    fun buildCpuFrequencyOps(
        policies: List<CpuPolicyNode>,
        perfRatio: Float,
        primeRatio: Float,
        // BUG CRÍTICO QUE ISTO CORRIGE: este piso estava fixo em [CPU_MIN_RATIO] (30%) aqui
        // dentro. Ou seja, todo o trabalho do [TDP_MIN_RATIO] (12%) -- o `wattsToRatio` e o passo
        // de convergência do AutoTDP -- era desfeito na última linha antes de escrever em sysfs:
        // um alvo de 5W calculava 12%, chegava aqui e era imediatamente puxado de volta para 30%.
        // A correção documentada do "5W não limita, 10W limita" nunca chegou a ter efeito real no
        // aparelho. Agora quem chama diz qual é o piso apropriado ao modo em uso.
        minRatio: Float = CPU_MIN_RATIO,
        /**
         * Ratio do cluster de EFICIÊNCIA (o mais lento) quando há três ou mais clusters. `null` =
         * segue o [perfRatio] (o comportamento antigo, e o que o AutoTDP usa).
         */
        effRatio: Float? = null,
    ): List<HardwareWriteOp> {
        if (policies.isEmpty()) return emptyList()
        val sorted = policies.sortedBy { it.cpuinfoMaxFreqKHz }
        val hasEff = sorted.size >= 3
        return sorted.mapIndexed { index, node ->
            val isPrime = index == sorted.lastIndex
            val isEff = hasEff && index == 0
            val ratio = when {
                isPrime -> primeRatio
                isEff && effRatio != null -> effRatio
                else -> perfRatio
            }.coerceIn(minRatio, CPU_MAX_RATIO)
            val rawTargetKHz = (node.cpuinfoMaxFreqKHz * ratio)
                .toLong()
                .coerceIn(1L, node.cpuinfoMaxFreqKHz)
            // Encaixa na tabela de OPPs do kernel: escrevemos um valor que existe mesmo, por isso
            // a leitura de verificação passa a bater certo em vez de reportar uma falsa falha.
            val targetFreqKHz = snapToSupported(rawTargetKHz, node.availableFreqsKHz)
            buildWriteOp(
                label = when {
                    isPrime -> "cpu-prime-policy"
                    isEff -> "cpu-eff-policy"
                    else -> "cpu-perf-policy-$index"
                },
                path = "${node.policyPath}/scaling_max_freq",
                value = targetFreqKHz.toString(),
            )
        }
    }

    fun buildGpuFrequencyOp(gpuMaxPath: String, targetHz: Long): HardwareWriteOp =
        buildWriteOp(label = "gpu-max-clock", path = gpuMaxPath, value = targetHz.toString())

    fun buildWriteOp(label: String, path: String, value: String): HardwareWriteOp =
        HardwareWriteOp(label = label, targetPath = path, value = value)

    /** Converte um alvo de TDP em watts para o ratio de clock normalizado usado pelo AutoTDP. */
    fun wattsToRatio(watts: Float, maxWatts: Float = 25f, minRatio: Float = TDP_MIN_RATIO): Float =
        (watts / maxWatts).coerceIn(minRatio, CPU_MAX_RATIO)

    /**
     * Deriva os ratios perf/prime a partir de clocks absolutos (KHz) pedidos pelo utilizador,
     * usando os máximos de cada política já descoberta como referência de 100%.
     */
    fun absoluteClocksToRatios(
        perfClockKHz: Long,
        primeClockKHz: Long,
        policies: List<CpuPolicyNode>,
        fallbackPerfMaxKHz: Long = 3_530_000L,
        fallbackPrimeMaxKHz: Long = 4_320_000L,
    ): Pair<Float, Float> {
        val sorted = policies.sortedBy { it.cpuinfoMaxFreqKHz }
        val maxPerf = sorted.getOrNull(sorted.lastIndex - 1)?.cpuinfoMaxFreqKHz ?: fallbackPerfMaxKHz
        val maxPrime = sorted.lastOrNull()?.cpuinfoMaxFreqKHz ?: fallbackPrimeMaxKHz

        val perfRatio = (perfClockKHz.toFloat() / maxPerf.toFloat()).coerceIn(MANUAL_MIN_RATIO, CPU_MAX_RATIO)
        val primeRatio = (primeClockKHz.toFloat() / maxPrime.toFloat()).coerceIn(MANUAL_MIN_RATIO, CPU_MAX_RATIO)
        return perfRatio to primeRatio
    }

    /**
     * Ratio do cluster de eficiência (a política mais lenta) para um clock absoluto, ou `null`
     * quando o SoC não tem esse cluster (menos de três políticas) ou o valor não foi definido
     * (<= 0) -- aí o cluster segue o ratio do Perf.
     */
    fun effClockToRatio(effClockKHz: Long?, policies: List<CpuPolicyNode>, fallbackEffMaxKHz: Long? = null): Float? {
        if (effClockKHz == null || effClockKHz <= 0L) return null
        val sorted = policies.sortedBy { it.cpuinfoMaxFreqKHz }
        val maxEff = when {
            sorted.size >= 3 -> sorted.first().cpuinfoMaxFreqKHz
            sorted.isEmpty() -> fallbackEffMaxKHz ?: return null
            else -> return null
        }
        return (effClockKHz.toFloat() / maxEff.toFloat()).coerceIn(MANUAL_MIN_RATIO, CPU_MAX_RATIO)
    }
}
