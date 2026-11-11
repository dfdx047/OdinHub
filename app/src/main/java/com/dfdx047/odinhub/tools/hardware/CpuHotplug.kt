package com.dfdx047.odinhub.tools.hardware

/**
 * Regras de segurança do hotplug de núcleos (`/sys/devices/system/cpu/cpuN/online`).
 *
 * O estado pedido é uma máscara de bits de núcleos DESLIGADOS (bit N = cpuN desligado): 0 = todos
 * ligados, que é também o "Stock". Tudo aqui é JVM puro, para as regras serem testadas sem aparelho.
 *
 * Regras (nunca negociáveis, aplicadas em [sanitize] antes de qualquer escrita):
 *  1. o cpu0 nunca é desligado (é o núcleo de arranque; vários kernels nem o deixam desligar e o
 *     sistema depende dele para os temporizadores e interrupções);
 *  2. fica sempre pelo menos um núcleo ligado (redundante com a regra 1, mas mantida para o caso de
 *     um kernel sem cpu0 hotplugável na lista);
 *  3. só se mexe em núcleos que o kernel diz existirem (`possible`).
 *
 * Quando volta tudo a ligar: no "Stock", ao sair do jogo e no arranque (ver PerformanceManager,
 * ForegroundAppWatcherService e BootReceiver).
 */
object CpuHotplug {

    /** Núcleo de arranque: nunca desligado. */
    const val BOOT_CPU = 0

    /** Máscara "tudo ligado". */
    const val ALL_ONLINE = 0

    fun maskOf(cpus: Iterable<Int>): Int = cpus.filter { it in 0..30 }.fold(0) { acc, cpu -> acc or (1 shl cpu) }

    fun cpusOf(mask: Int): List<Int> = (0..30).filter { mask and (1 shl it) != 0 }

    fun isOffline(mask: Int, cpu: Int): Boolean = cpu in 0..30 && mask and (1 shl cpu) != 0

    /**
     * Aplica as regras de segurança a um pedido. [possible] = núcleos que existem (ex: 0..7).
     * Devolve a máscara que pode mesmo ser escrita.
     */
    fun sanitize(requestedOffline: Int, possible: List<Int>): Int {
        if (possible.isEmpty()) return ALL_ONLINE
        val sorted = possible.distinct().sorted()
        // Regra 3: só núcleos que existem. Regra 1: nunca o cpu0.
        var mask = maskOf(cpusOf(requestedOffline).filter { it in sorted && it != BOOT_CPU })
        // Regra 2: pelo menos um ligado. Se o pedido desligava tudo, fica ligado o mais baixo.
        if (sorted.all { isOffline(mask, it) }) mask = mask and (1 shl sorted.first()).inv()
        return mask
    }

    /** Liga ou desliga um núcleo no pedido (já com as regras aplicadas). */
    fun toggleCpu(mask: Int, cpu: Int, offline: Boolean, possible: List<Int>): Int {
        val raw = if (offline) mask or (1 shl cpu) else mask and (1 shl cpu).inv()
        return sanitize(raw, possible)
    }

    /**
     * Liga ou desliga um cluster inteiro. Desligar o cluster de eficiência deixa sempre o cpu0
     * ligado (regra 1) -- ou seja, nesse cluster "desligado" quer dizer "só o cpu0".
     */
    fun toggleCluster(mask: Int, cluster: CpuCluster, offline: Boolean, possible: List<Int>): Int {
        val bits = maskOf(cluster.cpus)
        val raw = if (offline) mask or bits else mask and bits.inv()
        return sanitize(raw, possible)
    }

    /** O cluster está todo desligado (descontando o cpu0, que nunca desliga)? */
    fun isClusterOffline(mask: Int, cluster: CpuCluster): Boolean {
        val switchable = cluster.cpus.filter { it != BOOT_CPU }
        return switchable.isNotEmpty() && switchable.all { isOffline(mask, it) }
    }

    /**
     * Ordem segura das escritas para chegar de [online] (estado atual lido do sysfs: núcleo ->
     * ligado?) à máscara [targetOffline]: PRIMEIRO liga os que têm de ficar ligados, SÓ DEPOIS
     * desliga os outros -- assim nunca há um instante com menos núcleos do que o pedido final.
     * Devolve pares (núcleo, valor a escrever: true = "1").
     */
    fun plan(online: Map<Int, Boolean>, targetOffline: Int, possible: List<Int>): List<Pair<Int, Boolean>> {
        val target = sanitize(targetOffline, possible)
        val cpus = possible.distinct().sorted().filter { it != BOOT_CPU }
        val toOnline = cpus.filter { !isOffline(target, it) && online[it] == false }
        val toOffline = cpus.filter { isOffline(target, it) && online[it] != false }
        return toOnline.map { it to true } + toOffline.map { it to false }
    }

    /** "0-3,5" / "0-7" (formato de `/sys/devices/system/cpu/possible`) -> lista de núcleos. */
    fun parseCpuList(raw: String?): List<Int> =
        raw.orEmpty().trim().split(',').flatMap { part ->
            val p = part.trim()
            when {
                p.isEmpty() -> emptyList()
                p.contains('-') -> {
                    val (a, b) = p.split('-', limit = 2).map { it.trim().toIntOrNull() }
                    if (a != null && b != null && a <= b) (a..b).toList() else emptyList()
                }
                else -> listOfNotNull(p.toIntOrNull())
            }
        }.distinct().sorted()

    /**
     * Clusters a partir das políticas de cpufreq (núcleos + frequência máxima de cada uma). A mais
     * rápida é o Prime; com três ou mais, a mais lenta é a Eficiência; o resto é Perf.
     */
    fun clustersFrom(policies: List<Pair<List<Int>, Long>>): List<CpuCluster> {
        val sorted = policies.filter { it.first.isNotEmpty() }.sortedBy { it.second }
        if (sorted.isEmpty()) return emptyList()
        return sorted.mapIndexed { index, (cpus, _) ->
            val kind = when {
                index == sorted.lastIndex -> ClusterKind.PRIME
                sorted.size >= 3 && index == 0 -> ClusterKind.EFFICIENCY
                else -> ClusterKind.PERF
            }
            CpuCluster(kind, cpus.distinct().sorted())
        }
    }
}

enum class ClusterKind { EFFICIENCY, PERF, PRIME }

data class CpuCluster(val kind: ClusterKind, val cpus: List<Int>)

/** Estado real de um núcleo, lido do sysfs (para a UI). */
data class CoreState(val cpu: Int, val online: Boolean, val cluster: ClusterKind?)
