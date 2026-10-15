package com.dfdx047.odinhub.tools.hardware

import android.util.Log
import com.dfdx047.odinhub.data.SharedPrefsRepo
import com.dfdx047.odinhub.models.ThermalPolicy
import com.dfdx047.odinhub.tools.DeviceRegistry
import com.dfdx047.odinhub.tools.ShellExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Um trip point "passive" de uma zona térmica, com a temperatura original (millicelsius). */
data class ThermalTrip(
    val tempPath: String,
    val originalMilliC: Int?,
)

/** Uma zona térmica relevante (CPU/GPU) descoberta em /sys/class/thermal. */
data class ThermalZone(
    val path: String,
    val type: String,
    val passiveTrips: List<ThermalTrip>,
) {
    val modePath: String get() = "$path/mode"
}

/** Estado exposto à UI (ver MainUiModel.thermalStatus). */
data class ThermalStatus(
    /** Escolha GLOBAL (cartão da aba Performance). */
    val mode: String = ThermalLimitModes.STOCK,
    /** O que está realmente aplicado agora: a regra do jogo em primeiro plano, ou o global. */
    val activeMode: String = ThermalLimitModes.STOCK,
    /** `true` quando [activeMode] vem de uma regra por jogo (App Overrides / overlay). */
    val fromAppOverride: Boolean = false,
    val disableZoneMode: Boolean = false,
    val zoneCount: Int = 0,
    val zoneNames: List<String> = emptyList(),
    val tripCount: Int = 0,
    val pServerAvailable: Boolean = false,
    val discovered: Boolean = false,
    /** `false` em aparelhos onde o throttling não passa pelos trip points (MediaTek). */
    val supported: Boolean = true,
    /** Código do último erro (ver [ThermalManager.ERR_*]) -- a UI traduz; `null` = tudo bem. */
    val lastError: String? = null,
    /** Detalhe do erro (nós que falharam, mensagem da exceção), para diagnóstico. */
    val lastErrorDetail: String? = null,
)

/**
 * Opções do limite térmico. O id é o que fica persistido em `SharedPrefsRepo.thermalLimitMode`.
 */
object ThermalLimitModes {
    const val STOCK = "stock"
    const val C85 = "85"
    const val C90 = "90"
    const val C95 = "95"
    const val UNTHROTTLED = "unthrottled"

    /** Temperatura (millicelsius) escrita nos trip points passivos, ou `null` para Stock. */
    fun tripMilliC(mode: String): Int? = when (mode) {
        C85 -> 85_000
        C90 -> 90_000
        C95 -> 95_000
        UNTHROTTLED -> UNTHROTTLED_MILLI_C
        else -> null
    }

    const val C95_MILLI_C = 95_000

    /** "Unthrottled": trips a 115 °C, acima de tudo o que o SoC atinge antes do corte de hardware. */
    const val UNTHROTTLED_MILLI_C = 115_000

    val all: List<String> = listOf(STOCK, C85, C90, C95, UNTHROTTLED)
}

/**
 * Limite térmico (via PServerBinder, que corre como root -- não precisa de KernelSU): sobe (ou repõe) os trip points passivos das zonas térmicas de CPU/GPU,
 * para o kernel só começar a estrangular os clocks a partir da temperatura escolhida.
 *
 * PEÇAS CRÍTICAS (escritas em sysfs via PServerBinder) e como desligá-las se causarem problemas no
 * aparelho:
 *  - [THERMAL_WRITES_ENABLED] = false desliga TODAS as escritas deste gestor de uma vez (a
 *    descoberta continua a funcionar, só para diagnóstico).
 *  - Ou, sem recompilar: escolher "Stock" no cartão, que repõe as temperaturas originais
 *    guardadas em `prefs.thermalTripBackup` e escreve "enabled" no `mode` de cada zona.
 *
 * Descoberta dinâmica e robusta: os nomes das zonas do 8 Elite não são conhecidos à partida,
 * por isso listamos `/sys/class/thermal/thermal_zone*`, lemos `type` e ficamos com as que
 * batem com [ZONE_TYPE_PATTERN]. Um nó ilegível nunca derruba nada -- é simplesmente ignorado.
 *
 * Reaplicação: os daemons térmicos do fabricante repõem os trips de tempos a tempos, por isso um
 * loop próprio reescreve a escolha a cada [REAPPLY_INTERVAL_MS] enquanto não for Stock. E como
 * sysfs volta aos valores de fábrica a cada reboot, o BootReceiver chama [reapplyPersisted].
 */
@Singleton
class ThermalManager @Inject constructor(
    private val executor: ShellExecutor,
    private val prefs: SharedPrefsRepo,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var reapplyJob: Job? = null

    /** Que trips este SoC deixa mexer (ver ThermalPolicy / DeviceProfiles). */
    private val policy: ThermalPolicy = DeviceRegistry.current.thermal

    /** `false` até à primeira chamada de [setAppOverride] neste processo. */
    @Volatile
    private var overrideSeen = false

    private val _status = MutableStateFlow(ThermalStatus(mode = prefs.thermalLimitMode, activeMode = prefs.thermalLimitMode, disableZoneMode = prefs.thermalDisableZoneMode))

    /**
     * Limite térmico da regra do jogo em primeiro plano (ForegroundAppWatcherService), ou `null`
     * para seguir o global. Só em memória: ao sair do jogo o watcher volta a pô-lo a `null`.
     */
    @Volatile
    private var appOverrideMode: String? = null

    private val effectiveMode: String get() = appOverrideMode ?: prefs.thermalLimitMode
    val status: StateFlow<ThermalStatus> = _status.asStateFlow()

    @Volatile
    private var zones: List<ThermalZone> = emptyList()

    init {
        scope.launch {
            discover()
            reapplyPersisted()
            startReapplyLoop()
        }
    }

    /** O PServerBinder (que corre como root) está disponível para escrever em sysfs? */
    val pServerAvailable: Boolean get() = executor.canWriteSysfs

    // ------------------------------------------------------------------ descoberta

    /**
     * Varre /sys/class/thermal e guarda as zonas CPU/GPU com os seus trip points passivos.
     * Segura contra tudo: nós ilegíveis, diretórios vazios, valores não numéricos.
     */
    @Synchronized
    fun discover(): List<ThermalZone> {
        val found = mutableListOf<ThermalZone>()
        if (!policy.supported) {
            zones = emptyList()
            _status.update { it.copy(supported = false, discovered = true, pServerAvailable = pServerAvailable) }
            return found
        }
        // Backup de um boot anterior não serve de referência (ver dropBackupsFromPreviousBoot).
        dropBackupsFromPreviousBoot()
        val factory = backupMap()
        try {
            val zoneDirs = listDir(THERMAL_ROOT).filter { it.startsWith("thermal_zone") }
            for (zoneName in zoneDirs) {
                val zonePath = "$THERMAL_ROOT/$zoneName"
                val type = readNode("$zonePath/type") ?: continue
                if (!ZONE_TYPE_PATTERN.containsMatchIn(type)) continue

                val entries = listDir(zonePath)
                val fanTrips = fanTripIndices(zonePath, entries)
                val trips = entries
                    .mapNotNull { TRIP_TYPE_PATTERN.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
                    .sorted()
                    .filter { it !in fanTrips }
                    .mapNotNull { index ->
                        val tripType = readNode("$zonePath/trip_point_${index}_type") ?: return@mapNotNull null
                        if (!tripType.equals("passive", ignoreCase = true)) return@mapNotNull null
                        val tempPath = "$zonePath/trip_point_${index}_temp"
                        // O valor de FÁBRICA vem do backup quando já mexemos neste boot: lido do
                        // nó, um trip que pusemos a 85 °C ficava abaixo do mínimo deste SoC e
                        // desaparecia da lista -- e os modos seguintes nunca mais lhe tocavam.
                        val original = factory[tempPath] ?: readNode(tempPath)?.toIntOrNull()
                        // Trips abaixo do mínimo deste SoC são da ventoinha/conforto, não de
                        // throttling (ex: 50-85 °C nos 8 Gen 2 da Moorechip). Não lhes tocamos.
                        if ((original ?: 0) < policy.minOriginalMilliC) return@mapNotNull null
                        ThermalTrip(tempPath = tempPath, originalMilliC = original)
                    }
                found.add(ThermalZone(path = zonePath, type = type, passiveTrips = trips))
            }
        } catch (e: Exception) {
            Log.w(TAG, "discover: falha a varrer $THERMAL_ROOT", e)
            _status.update { it.copy(lastError = ERR_DISCOVER_FAILED, lastErrorDetail = e.message) }
        }
        zones = found
        _status.update {
            it.copy(
                zoneCount = found.size,
                zoneNames = found.map { z -> z.type },
                tripCount = found.sumOf { z -> z.passiveTrips.size },
                pServerAvailable = pServerAvailable,
                discovered = true,
            )
        }
        Log.i(TAG, "discover: ${found.size} zonas CPU/GPU, ${found.sumOf { it.passiveTrips.size }} trips passivos")
        return found
    }

    /** Repete a descoberta (botão "atualizar" na UI). */
    fun refresh() {
        scope.launch { discover() }
    }

    // ------------------------------------------------------------------ API pública

    /**
     * Escolhe e aplica um modo (ver [ThermalLimitModes]). Persiste a escolha antes de escrever,
     * para o loop de reaplicação e o BootReceiver a apanharem mesmo que a escrita falhe agora.
     */
    fun setMode(mode: String) {
        val safeMode = if (mode in ThermalLimitModes.all) mode else ThermalLimitModes.STOCK
        prefs.thermalLimitMode = safeMode
        _status.update { it.copy(mode = safeMode, activeMode = effectiveMode, lastError = null, lastErrorDetail = null) }
        scope.launch { applyCurrent() }
    }

    /** Liga/desliga "também desativar a política de throttling da zona" (`mode` = disabled). */
    fun setDisableZoneMode(enabled: Boolean) {
        prefs.thermalDisableZoneMode = enabled
        _status.update { it.copy(disableZoneMode = enabled, lastError = null, lastErrorDetail = null) }
        scope.launch { applyCurrent() }
    }

    /**
     * Regra por jogo: `mode` = id de [ThermalLimitModes], ou `null` para voltar ao global.
     * Chamado pelo ForegroundAppWatcherService a cada troca de app.
     */
    fun setAppOverride(mode: String?) {
        val safe = mode?.takeIf { it in ThermalLimitModes.all }
        val changed = safe != appOverrideMode
        appOverrideMode = safe
        // A primeira chamada do processo aplica SEMPRE: se o processo morreu a meio de um jogo
        // com uma regra "Unthrottled", os trips ficaram a 115 °C em sysfs e o `null` que chega
        // agora parecia "sem alteração" -- nada os repunha até ao reboot.
        val first = !overrideSeen
        overrideSeen = true
        if (!changed && !first && !prefs.thermalOverrideActive) return
        prefs.thermalOverrideActive = safe != null
        _status.update { it.copy(activeMode = effectiveMode, fromAppOverride = safe != null) }
        if (safe == null && !hasAnythingToRestore()) return
        scope.launch { applyCurrent() }
    }

    /** Há trips/modos modificados por nós (backup guardado) ou uma escolha não-Stock em vigor? */
    private fun hasAnythingToRestore(): Boolean =
        prefs.thermalLimitMode != ThermalLimitModes.STOCK || prefs.thermalTripBackup != null ||
            prefs.thermalModeBackup != null || prefs.thermalDisableZoneMode

    /**
     * Reaplica o que está persistido (arranque da app e BootReceiver). Em Stock só faz alguma
     * coisa se houver um backup -- ou seja, se algo pode ter ficado modificado.
     */
    fun reapplyPersisted() {
        if (effectiveMode == ThermalLimitModes.STOCK && !hasAnythingToRestore()) return
        applyCurrent()
    }

    // ------------------------------------------------------------------ aplicação

    private fun startReapplyLoop() {
        reapplyJob?.cancel()
        reapplyJob = scope.launch {
            while (isActive) {
                delay(REAPPLY_INTERVAL_MS)
                if (effectiveMode != ThermalLimitModes.STOCK || prefs.thermalDisableZoneMode) {
                    applyCurrent(quiet = true)
                }
            }
        }
    }

    @Synchronized
    private fun applyCurrent(quiet: Boolean = false) {
        val mode = effectiveMode
        val disableZones = prefs.thermalDisableZoneMode
        if (!THERMAL_WRITES_ENABLED) {
            _status.update { it.copy(lastError = ERR_WRITES_DISABLED, lastErrorDetail = null) }
            return
        }
        if (!pServerAvailable) {
            _status.update { it.copy(pServerAvailable = false, lastError = ERR_NO_PSERVER, lastErrorDetail = null) }
            return
        }
        if (!policy.supported) {
            _status.update { it.copy(supported = false, lastError = null, lastErrorDetail = null) }
            return
        }
        if (zones.isEmpty()) discover()
        if (zones.isEmpty()) {
            _status.update { it.copy(lastError = ERR_NO_ZONES, lastErrorDetail = null) }
            return
        }
        dropBackupsFromPreviousBoot()

        val failures = mutableListOf<String>()
        val targetMilliC = ThermalLimitModes.tripMilliC(mode)
        if (targetMilliC == null) {
            failures += restoreTrips()
        } else {
            backupTripsIfNeeded()
            failures += writeTrips(targetMilliC)
        }

        // Política de throttling da zona: só se mexe no nó `mode` quando o utilizador liga a
        // opção. Antes escrevia-se "enabled" em TODAS as zonas a cada aplicação (e a cada 30 s),
        // o que alterava o estado de fábrica de zonas que a Qualcomm deixa desativadas de
        // propósito. Agora: ao ligar, guarda os valores originais e escreve "disabled"; ao
        // desligar, repõe exatamente o que lá estava.
        failures += if (disableZones) {
            backupModesIfNeeded()
            writeZoneModes("disabled")
        } else {
            restoreZoneModes()
        }

        val detail = if (failures.isEmpty()) null else failures.take(4).joinToString(", ") + (if (failures.size > 4) "…" else "")
        val error = if (detail == null) null else ERR_WRITE_FAILED
        if (!quiet || error != null) {
            _status.update { it.copy(mode = prefs.thermalLimitMode, activeMode = mode, fromAppOverride = appOverrideMode != null, disableZoneMode = disableZones, pServerAvailable = true, lastError = error, lastErrorDetail = detail) }
        }
        if (detail != null) Log.w(TAG, "applyCurrent($mode): falhou em $detail")
    }

    /**
     * Guarda as temperaturas originais UMA vez, antes da primeira modificação. Se a leitura
     * inicial (leitura direta) falhou nalgum trip, tenta agora via PServer -- é o último momento em que
     * o valor de fábrica ainda está lá.
     */
    private fun backupTripsIfNeeded() {
        if (prefs.thermalTripBackup != null) return
        val trips = JSONObject()
        zones.forEach { zone ->
            zone.passiveTrips.forEach { trip ->
                // Lê o valor ATUAL: depois de um reboot (ver dropBackupsFromPreviousBoot) é o de
                // fábrica. Nunca guardamos o nosso próprio "Unthrottled" como se fosse de fábrica.
                val current = readNode(trip.tempPath)?.toIntOrNull() ?: trip.originalMilliC
                if (current != null && current != ThermalLimitModes.UNTHROTTLED_MILLI_C) {
                    trips.put(trip.tempPath, JSONObject().put(KEY_VALUE, current).put(KEY_TYPE, zone.type))
                }
            }
        }
        if (trips.length() > 0) {
            stampBootId()
            prefs.thermalTripBackup = JSONObject().put(KEY_TRIPS, trips).toString()
            Log.i(TAG, "backupTripsIfNeeded: ${trips.length()} trips guardados")
        }
    }

    /**
     * Um backup de um boot anterior já não serve: sysfs volta aos valores de fábrica a cada boot
     * (e uma atualização do firmware pode mudar esses valores ou a numeração das zonas). Sem isto,
     * um backup apanhado com trips já modificados (ex: reinstalação da app sem reboot) ficava a
     * "repor" 115 °C para sempre.
     */
    private fun dropBackupsFromPreviousBoot() {
        val bootId = currentBootId() ?: return
        val stamped = prefs.thermalBackupBootId
        if (stamped == null) {
            // Backup de uma versão antiga da app (sem boot id): adota-o neste boot.
            if (prefs.thermalTripBackup != null || prefs.thermalModeBackup != null) prefs.thermalBackupBootId = bootId
            return
        }
        if (stamped != bootId) {
            Log.i(TAG, "dropBackupsFromPreviousBoot: reboot desde o último backup, a descartar")
            prefs.thermalTripBackup = null
            prefs.thermalModeBackup = null
            prefs.thermalBackupBootId = null
        }
    }

    private fun stampBootId() {
        currentBootId()?.let { prefs.thermalBackupBootId = it }
    }

    /** O nó ficou trancado (0444) por uma escrita nossa? */
    private fun isLocked(path: String): Boolean =
        runCatching { (android.system.Os.stat(path).st_mode and 0x1FF) == 0x124 }.getOrDefault(false)

    private fun currentBootId(): String? =
        runCatching { File(BOOT_ID_PATH).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * Escreve o novo limite SÓ onde faz sentido. No 8 Elite cada zona de CPU/GPU tem dois trips
     * passivos: ~95 °C (o que o HAL térmico usa para começar a estrangular) e ~135 °C (margem do
     * thermal-engine). Escrever o mesmo valor em todos era errado: "85 °C" também puxava o trip de
     * 135 °C para baixo, e "Unthrottled" (115 °C) acabava por BAIXAR esse trip em vez de o subir.
     * Ver [targetFor].
     */
    private fun writeTrips(milliC: Int): List<String> {
        val originals = backupMap()
        val failures = mutableListOf<String>()
        zones.forEach { zone ->
            zone.passiveTrips.forEach { trip ->
                val original = originals[trip.tempPath] ?: trip.originalMilliC
                val target = targetFor(original, milliC) ?: return@forEach
                if (!writeVerified(trip.tempPath, target.toString())) failures += "${zone.type}:${trip.tempPath.substringAfterLast('/')}"
            }
        }
        return failures
    }

    /** Entrada do backup: valor de fábrica e o `type` da zona (para não escrever na zona errada). */
    private data class TripBackup(val value: Int, val type: String?)

    /** Lê o backup -- formato novo `{"trips": {caminho: {"v":.., "t":..}}}` ou o antigo `{caminho: valor}`. */
    private fun backupEntries(): Map<String, TripBackup>? {
        val raw = prefs.thermalTripBackup ?: return emptyMap()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val trips = json.optJSONObject(KEY_TRIPS) ?: json
        val out = mutableMapOf<String, TripBackup>()
        val keys = trips.keys()
        while (keys.hasNext()) {
            val path = keys.next()
            val entry = trips.optJSONObject(path)
            val value = entry?.optInt(KEY_VALUE, -1) ?: trips.optInt(path, -1)
            if (value > 0) out[path] = TripBackup(value, entry?.optString(KEY_TYPE)?.takeIf { it.isNotEmpty() })
        }
        return out
    }

    private fun backupMap(): Map<String, Int> = backupEntries().orEmpty().mapValues { it.value.value }

    /** O `type` atual da zona dona deste caminho (`.../thermal_zoneN/trip_point_M_temp`). */
    private fun zoneTypeOf(path: String): String? = readNode(path.substringBeforeLast('/') + "/type")

        /** Repõe as temperaturas do backup. Sem backup não há nada a repor (nunca foi modificado). */
    private fun restoreTrips(): List<String> {
        if (prefs.thermalTripBackup == null) return emptyList()
        val entries = backupEntries() ?: run {
            Log.w(TAG, "restoreTrips: backup ilegível")
            prefs.thermalTripBackup = null
            return listOf("thermalTripBackup")
        }
        val failures = mutableListOf<String>()
        entries.forEach { (path, backup) ->
            // A zona mudou de número desde o backup? Então este caminho é OUTRA zona: não tocar.
            if (backup.type != null && zoneTypeOf(path) != backup.type) return@forEach
            if (!writeVerified(path, backup.value.toString(), restoring = true)) failures += path.substringAfterLast('/')
        }
        return failures
    }

    private fun backupModesIfNeeded() {
        if (prefs.thermalModeBackup != null) return
        val json = JSONObject()
        // "disabled" nunca é de fábrica numa zona que vamos desativar -- seria o nosso próprio valor.
        zones.forEach { zone -> readNode(zone.modePath)?.takeIf { it != "disabled" }?.let { json.put(zone.modePath, it) } }
        if (json.length() > 0) {
            stampBootId()
            prefs.thermalModeBackup = json.toString()
        }
    }

    /** Repõe os `mode` guardados; sem backup não há nada a repor (nunca foram alterados). */
    private fun restoreZoneModes(): List<String> {
        val raw = prefs.thermalModeBackup ?: return emptyList()
        val json = runCatching { JSONObject(raw) }.getOrElse { prefs.thermalModeBackup = null; return emptyList() }
        val failures = mutableListOf<String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val path = keys.next()
            val value = json.optString(path, "")
            if (value.isNotEmpty() && !writeVerified(path, value, restoring = true)) failures += path.substringAfterLast("thermal/")
        }
        if (failures.isEmpty()) prefs.thermalModeBackup = null
        return failures
    }

    private fun writeZoneModes(value: String): List<String> {
        val failures = mutableListOf<String>()
        zones.forEach { zone ->
            // Nem todas as zonas expõem `mode`; sem o nó, não há nada a fazer (e não é erro).
            if (readNode(zone.modePath) == null && !File(zone.modePath).exists()) return@forEach
            if (!writeVerified(zone.modePath, value)) failures += "${zone.type}:mode"
        }
        return failures
    }

    /**
     * Escrita "chmod 666 -> echo -> chmod 444" (o mesmo padrão de HardwareWriteOp usado para os
     * clocks) com verificação por leitura. Se o valor já for o pedido, não escreve nada -- é o
     * que torna o loop de 30 s barato quando o daemon do fabricante não mexeu em nada.
     */
    private fun writeVerified(path: String, value: String, restoring: Boolean = false): Boolean {
        if (readNode(path) == value && !(restoring && isLocked(path))) return true
        // Ao repor (Stock), o nó volta às permissões do sistema em vez de ficar trancado a 0444
        // -- senão o serviço térmico do fabricante deixava de o poder ajustar.
        val lock = if (restoring) HardwareWriteOp.DEFAULT_RESTORE_MODE else HardwareWriteOp.LOCKED_MODE
        val op = HardwareWriteOp(label = path.substringAfterLast('/'), targetPath = path, value = value, lockMode = lock)
        executor.executeAsRoot(op.asChainedCommand())
        if (readNodeAsRoot(path) == value) return true
        op.asSequentialCommands().forEach { executor.executeAsRoot(it) }
        return readNodeAsRoot(path) == value
    }

    // ------------------------------------------------------------------ leitura

    /**
     * Índices de trip ligados a uma VENTOINHA (`cdevN/type` contém "fan", ex: "pwm-fan"). São
     * trips passivos mas não fazem throttling -- só decidem quando a ventoinha acelera. Leitura
     * direta só (sem root): se não for legível, a proteção por temperatura mínima ainda vale.
     */
    private fun fanTripIndices(zonePath: String, entries: List<String>): Set<Int> =
        entries.mapNotNull { CDEV_TRIP_PATTERN.matchEntire(it)?.groupValues?.get(1) }
            .mapNotNull { n ->
                val type = runCatching { File("$zonePath/cdev$n/type").readText().trim() }.getOrNull()
                if (type?.contains("fan", ignoreCase = true) != true) return@mapNotNull null
                runCatching { File("$zonePath/cdev${n}_trip_point").readText().trim().toInt() }.getOrNull()
            }
            .toSet()

    private fun listDir(path: String): List<String> {
        val local = runCatching { File(path).list()?.toList() }.getOrNull()
        if (!local.isNullOrEmpty()) return local
        if (!pServerAvailable) return emptyList()
        return executor.executeAsRoot("ls $path").getOrNull()
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
    }

    /** Leitura simples primeiro (a maioria destes nós é world-readable), PServer só se falhar. */
    private fun readNode(path: String): String? {
        runCatching { File(path).readText().trim() }.getOrNull()?.let { return it }
        return readNodeAsRoot(path)
    }

    private fun readNodeAsRoot(path: String): String? {
        if (!pServerAvailable) return null
        return executor.executeAsRoot("cat $path").getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val TAG = "ThermalManager"

        /**
         * INTERRUPTOR GERAL das escritas térmicas. Pôr a `false` se o aparelho reagir mal
         * (reinícios, throttling errático): a descoberta continua a funcionar, mas nada é escrito.
         */
        const val THERMAL_WRITES_ENABLED = com.dfdx047.odinhub.models.FeatureFlags.THERMAL_WRITES_ENABLED

        // Códigos de erro expostos em ThermalStatus.lastError (a UI traduz).
        const val ERR_WRITES_DISABLED = "writes_disabled"
        const val ERR_NO_PSERVER = "no_pserver"
        const val ERR_NO_ZONES = "no_zones"
        const val ERR_WRITE_FAILED = "write_failed"
        const val ERR_DISCOVER_FAILED = "discover_failed"

        /**
         * Valor a escrever num trip cujo valor de fábrica é [originalMilliC], ou `null` para não
         * lhe tocar:
         *  - Trips de "segunda linha" (fábrica >= [SECOND_STAGE_MILLI_C], ex: 135 °C) nunca são
         *    alterados -- são a rede de segurança.
         *  - Os restantes (o trip que de facto inicia o throttling, ~95 °C) passam a [chosenMilliC].
         *    Sem valor de fábrica conhecido, o modo Unthrottled não toca nesse trip (podia ser um
         *    trip de emergência); 85/90/95 °C escrevem na mesma.
         */
        fun targetFor(originalMilliC: Int?, chosenMilliC: Int): Int? {
            if (originalMilliC != null && originalMilliC >= SECOND_STAGE_MILLI_C) return null
            if (originalMilliC == null && chosenMilliC > ThermalLimitModes.C95_MILLI_C) return null
            return chosenMilliC
        }

        /** Trips com valor de fábrica igual ou acima disto são de emergência -- nunca mexemos. */
        const val SECOND_STAGE_MILLI_C = 110_000

        const val THERMAL_ROOT = "/sys/class/thermal"
        const val BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id"
        private const val KEY_TRIPS = "trips"
        private const val KEY_VALUE = "v"
        private const val KEY_TYPE = "t"
        private val CDEV_TRIP_PATTERN = Regex("cdev(\\d+)_trip_point")
        const val REAPPLY_INTERVAL_MS = 30_000L

        /** Tipos de zona que interessam (CPU/GPU e os nomes que a Qualcomm lhes dá). */
        val ZONE_TYPE_PATTERN = Regex("(cpu|cpuss|gpu|gpuss|apc|silver|gold|prime|oryon|kgsl)", RegexOption.IGNORE_CASE)
        private val TRIP_TYPE_PATTERN = Regex("trip_point_(\\d+)_type")
    }
}
