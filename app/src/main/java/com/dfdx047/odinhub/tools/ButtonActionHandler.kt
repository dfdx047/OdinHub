package com.dfdx047.odinhub.tools

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import com.dfdx047.odinhub.data.SharedPrefsRepo
import com.dfdx047.odinhub.models.ButtonAction
import com.dfdx047.odinhub.models.ButtonMacros
import com.dfdx047.odinhub.models.MacroStep
import com.dfdx047.odinhub.service.GamingOverlayService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executa as ações dos botões: gestos do Home (ButtonAction) e macros de M1/M2 (ButtonMacros).
 *
 * As ações "de sistema" (Home, Voltar, captura de ecrã, notificações...) usam as global actions
 * do serviço de acessibilidade -- não precisam de root. Gravação de ecrã, macros e "fechar jogo"
 * passam pelo PServerBinder (root).
 *
 * PARA DESLIGAR (se algo correr mal no aparelho): desligar "Gestos do botão Home" e as macros na
 * aba Controls devolve os botões ao comportamento 100% nativo -- o serviço deixa de os consumir.
 */
@Singleton
class ButtonActionHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val executor: ShellExecutor,
    private val prefs: SharedPrefsRepo,
    private val foregroundTracker: ForegroundAppTracker,
    private val capabilities: DeviceCapabilities,
) {
    /** O aparelho tem botões traseiros M1/M2 remapeáveis? (para o overlay esconder o separador) */
    val backButtons: kotlinx.coroutines.flow.StateFlow<Boolean> get() = capabilities.backButtons

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val main = Handler(Looper.getMainLooper())
    private var serviceRef: WeakReference<AccessibilityService>? = null

    @Volatile
    var isRecording: Boolean = false
        private set
    private var recordingBase: String? = null

    fun attach(service: AccessibilityService) { serviceRef = WeakReference(service) }
    fun detach() { serviceRef = null }

    private val isEn: Boolean get() = prefs.isEnglish

    // ------------------------------------------------------------------ ações

    fun perform(action: ButtonAction) {
        val service = serviceRef?.get()
        when (action) {
            ButtonAction.NONE -> Unit
            ButtonAction.HOME -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            ButtonAction.BACK -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            ButtonAction.RECENTS -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            ButtonAction.SCREENSHOT -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            ButtonAction.NOTIFICATIONS -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            ButtonAction.QUICK_SETTINGS -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            ButtonAction.POWER_MENU -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            ButtonAction.LOCK_SCREEN -> service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            ButtonAction.TOGGLE_OVERLAY -> {
                // Sem o serviço do overlay a correr ninguém recolhe o pedido e o gesto "não fazia
                // nada" -- explicamos porquê em vez de falhar em silêncio.
                if (GamingOverlayService.toggleOverlayFlow.subscriptionCount.value == 0) {
                    android.widget.Toast.makeText(
                        context,
                        if (isEn) "Side overlay is off — turn it on in Odin Hub" else "A barra lateral está desligada — ligue-a no Odin Hub",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    GamingOverlayService.toggleOverlayFlow.tryEmit(Unit)
                }
            }
            ButtonAction.TOGGLE_HUD -> com.dfdx047.odinhub.service.PerformanceHudService.toggle(context, prefs)
            ButtonAction.SCREEN_RECORD -> toggleScreenRecording()
            ButtonAction.CLOSE_APP -> closeForegroundGame(service)
        }
    }

    private fun closeForegroundGame(service: AccessibilityService?) {
        val pkg = foregroundTracker.currentPackage.value
        if (pkg.isBlank() || pkg == ForegroundAppTracker.GLOBAL || pkg == context.packageName || !pkg.matches(PACKAGE_NAME)) return
        service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        scope.launch { executor.executeAsRoot("am force-stop $pkg") }
        toast(if (isEn) "Closed $pkg" else "Fechado: $pkg")
    }

    // ------------------------------------------------------------------ macros M1/M2

    /**
     * Chamado pelo serviço de acessibilidade para cada tecla. Devolve `true` (consumida) se for a
     * tecla-gatilho de uma macro ligada -- o jogo nunca a chega a ver.
     */
    fun handleMacroKey(event: KeyEvent): Boolean {
        val button = when (event.keyCode) {
            ButtonMacros.TRIGGER_M1 -> "m1"
            ButtonMacros.TRIGGER_M2 -> "m2"
            else -> return false
        }
        val steps = effectiveSteps(button) ?: return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val command = ButtonMacros.toShellCommand(steps) ?: return true
            scope.launch {
                executor.executeAsRoot(command).onFailure { Log.w(TAG, "macro $button falhou", it) }
            }
        }
        return true
    }

    /**
     * Macros da regra do jogo em primeiro plano (AppOverrideEntity.m1Macro/m2Macro -- ver
     * MacroMode): null = segue o global. Só em memória; o watcher atualiza-as a cada troca de app.
     */
    @Volatile
    private var gameMacros: Pair<String?, String?> = null to null

    /** Passos que valem AGORA para o botão ("m1"/"m2"), ou `null` se não há macro ativa. */
    fun effectiveSteps(button: String): List<MacroStep>? {
        val game = if (button == "m1") gameMacros.first else gameMacros.second
        val steps = when {
            game != null -> ButtonMacros.decode(game)               // "" (desligada) -> vazio
            prefs.macroEnabled(button) -> ButtonMacros.decode(prefs.macroSteps(button))
            else -> emptyList()
        }
        return steps.takeIf { it.isNotEmpty() }
    }

    /**
     * Chamado pelo watcher ao trocar de app (e pelo overlay ao editar). BLOQUEANTE (root) --
     * chamar fora da thread principal.
     */
    fun setGameMacros(m1: String?, m2: String?) {
        gameMacros = m1 to m2
        syncTriggers()
    }

    /**
     * Garante que o mapeamento nativo de cada botão aponta para a tecla-gatilho SÓ quando há uma
     * macro ativa para ele (global ou do jogo), e repõe o mapeamento original quando não há.
     * Sem isto, uma macro só do jogo nunca disparava (o botão continuava com a tecla nativa).
     * BLOQUEANTE (root).
     */
    @Synchronized
    fun syncTriggers() {
        for (button in listOf("m1", "m2")) {
            val setting = settingFor(button)
            val trigger = if (button == "m1") ButtonMacros.TRIGGER_M1 else ButtonMacros.TRIGGER_M2
            val want = effectiveSteps(button) != null
            // Leitura falhada NÃO é "0": antes, uma falha guardava 0 como backup e o mapeamento
            // original do utilizador perdia-se quando a macro era desligada.
            val current = executor.getIntSystemSettingOrNull(setting) ?: readSettingDirect(setting) ?: continue
            if (want && current != trigger) {
                // Nunca guardar uma tecla-gatilho como "original" (a do outro botão também não).
                if (current != ButtonMacros.TRIGGER_M1 && current != ButtonMacros.TRIGGER_M2) {
                    prefs.setMacroNativeBackup(button, current)
                }
                executor.setIntSystemSetting(setting, trigger)
            } else if (!want && current == trigger) {
                val backup = prefs.macroNativeBackup(button)
                if (backup < 0) {
                    Log.w(TAG, "syncTriggers: sem mapeamento original guardado para $button; a deixar o botão sem tecla")
                }
                // Se a reposição falhar, o backup fica e o próximo sync tenta de novo.
                executor.setIntSystemSetting(setting, backup.coerceAtLeast(0))
            }
        }
    }

    /**
     * Grava um mapeamento NATIVO escolhido pelo utilizador, sob o mesmo monitor que o
     * [syncTriggers] -- uma troca de app a meio já não repõe o mapeamento antigo por cima.
     * O novo valor passa também a ser o "original" a repor quando uma macro for desligada.
     */
    @Synchronized
    fun setNativeMapping(setting: String, keyCode: Int) {
        val button = when (setting) {
            SettingsRepo.KEY_CUSTOM_M1_VALUE -> "m1"
            SettingsRepo.KEY_CUSTOM_M2_VALUE -> "m2"
            else -> null
        }
        if (button != null && keyCode != ButtonMacros.TRIGGER_M1 && keyCode != ButtonMacros.TRIGGER_M2) {
            prefs.setMacroNativeBackup(button, keyCode)
        }
        executor.setIntSystemSetting(setting, keyCode)
        // Uma macro POR JOGO ativa agora ainda precisa da tecla-gatilho neste botão.
        if (button != null) syncTriggers()
    }

    /**
     * Leitura sem root, para firmwares cujo PServer não devolve saída (Odin 2 Mini). Pode ser
     * recusada pelo Android para chaves não-públicas -- aí devolve null e o botão é ignorado.
     */
    private fun readSettingDirect(setting: String): Int? = runCatching {
        android.provider.Settings.System.getString(context.contentResolver, setting)?.trim()?.toIntOrNull()
    }.getOrNull()

    private fun settingFor(button: String) =
        if (button == "m1") SettingsRepo.KEY_CUSTOM_M1_VALUE else SettingsRepo.KEY_CUSTOM_M2_VALUE

    // ------------------------------------------------------------------ gravação de ecrã

    /**
     * Grava o ECRÃ INTEIRO com o `screenrecord` do sistema, via PServer (root): sem o diálogo do
     * Android 14/15 que obriga a escolher "uma app" ou "ecrã inteiro", e sem pedir permissões.
     * Sem áudio (limitação do `screenrecord`).
     *
     * O `screenrecord` tem um limite de 3 min em muitas versões; o laço em shell continua noutro
     * ficheiro (_2, _3...) enquanto a flag existir. Tenta primeiro 30 min por segmento e, se esta
     * versão do Android recusar (sai logo com erro), cai para 180 s -- sem sobrescrever nada.
     */
    private val recordingMutex = kotlinx.coroutines.sync.Mutex()

    fun toggleScreenRecording() {
        scope.launch {
            // Dois toques rápidos já não arrancam dois laços: a decisão é serializada.
            if (!recordingMutex.tryLock()) return@launch
            try {
                // O estado real é a flag + o processo (o laço sobrevive à morte do processo da
                // app); a flag sozinha não chega -- pode ter sobrado de um crash.
                // Sem captura de saída (Odin 2 Mini) não há como perguntar ao sistema: vale o
                // estado em memória.
                val canAsk = executor.outputCaptureWorks
                val running = if (canAsk) isScreenrecordRunning() || (isRecording && recFlagExists()) else isRecording
                if (running) {
                    stopRecording()
                } else {
                    executor.executeAsRoot("rm -f $REC_FLAG")
                    startRecording()
                }
                delay(TOGGLE_GUARD_MS)
            } finally {
                recordingMutex.unlock()
            }
        }
    }

    // Só comandos SIMPLES por transação: o PServer não corre de forma fiável cadeias `a; b` /
    // `a && b` / `( ... ) &` -- era por isso que a gravação nunca arrancava (só o `mkdir` do
    // início da linha corria). O laço de gravação vive num script que o PServer executa com um
    // único `sh <ficheiro>`; o script põe o laço em segundo plano e termina logo.
    private fun isScreenrecordRunning(): Boolean =
        executor.executeAsRoot("pidof screenrecord").getOrNull()?.trim()?.any { it.isDigit() } == true

    private fun recFlagExists(): Boolean =
        executor.executeAsRoot("ls $REC_FLAG").getOrNull()?.contains("odinhub_rec") == true

    /** Grava o script na pasta interna e na externa; devolve os caminhos a tentar, por ordem. */
    private fun writeRecordScript(base: String): List<String> {
        val script = """
            |#!/system/bin/sh
            |FLAG=$REC_FLAG
            |DIR=$REC_DIR
            |mkdir -p "${'$'}DIR"
            |touch "${'$'}FLAG"
            |(
            |  i=1; f=0; LIMIT=1800
            |  while [ -f "${'$'}FLAG" ]; do
            |    t0=${'$'}(date +%s)
            |    screenrecord --bit-rate $REC_BITRATE --time-limit ${'$'}LIMIT "${'$'}DIR/${base}_${'$'}i.mp4"
            |    if [ ${'$'}(( ${'$'}(date +%s) - t0 )) -lt 3 ]; then
            |      # Versões antigas do screenrecord só aceitam até 180 s: tenta de novo com 180.
            |      if [ ${'$'}LIMIT -gt 180 ]; then LIMIT=180; else f=${'$'}((f+1)); sleep 1; fi
            |    else
            |      f=0; i=${'$'}((i+1))
            |    fi
            |    [ ${'$'}f -ge 3 ] && rm -f "${'$'}FLAG"
            |  done
            |) > /dev/null 2>&1 < /dev/null &
            |""".trimMargin()
        val paths = mutableListOf<String>()
        runCatching {
            val file = java.io.File(context.filesDir, "odinhub_rec.sh")
            file.writeText(script)
            file.setReadable(true, false)
            context.filesDir.setExecutable(true, false)
            paths += file.absolutePath
        }
        // Recurso: o domínio SELinux do PServer pode não ler a pasta privada da app.
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return@runCatching
            val file = java.io.File(dir, "odinhub_rec.sh")
            file.writeText(script)
            paths += file.absolutePath
            paths += file.absolutePath.replace("/storage/emulated/0/", "/data/media/0/")
        }
        return paths.distinct().filter { it.matches(SAFE_SCRIPT_PATH) }
    }

    private suspend fun startRecording() {
        val base = "OdinHub_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        recordingBase = base
        isRecording = true
        val scripts = writeRecordScript(base)
        if (!executor.outputCaptureWorks) {
            scripts.firstOrNull()?.let { executor.executeAsRoot("sh $it") }
            toast(if (isEn) "Screen recording started" else "Gravação de ecrã iniciada")
            return // não dá para confirmar; o laço desliga-se sozinho se falhar
        }
        var started = false
        for (path in scripts) {
            executor.executeAsRoot("sh $path")
            delay(2000)
            // A gravar de verdade = há um screenrecord vivo.
            if (isScreenrecordRunning()) { started = true; break }
        }
        if (started) {
            toast(if (isEn) "Screen recording started — whole screen, no audio" else "Gravação iniciada — ecrã inteiro, sem áudio")
        } else {
            isRecording = false
            executor.executeAsRoot("rm -f $REC_FLAG")
            toast(if (isEn) "Could not start screen recording" else "Não foi possível iniciar a gravação")
        }
    }

    private suspend fun stopRecording() {
        isRecording = false
        val base = recordingBase
        // Primeiro a flag (para o laço não abrir outro segmento), depois SIGINT: é o sinal
        // com que o screenrecord fecha o MP4 corretamente.
        executor.executeAsRoot("rm -f $REC_FLAG")
        executor.executeAsRoot("pkill -INT screenrecord")
        delay(1500)
        val files = executor.executeAsRoot("ls $REC_DIR").getOrNull().orEmpty()
            .lines().map { it.trim() }
            .filter { it.endsWith(".mp4") && (base == null || it.startsWith(base)) }
            .map { "$SCAN_DIR/$it" }
        if (files.isNotEmpty()) {
            MediaScannerConnection.scanFile(context, files.toTypedArray(), null, null)
            toast(if (isEn) "Recording saved to Movies/OdinHub" else "Gravação guardada em Movies/OdinHub")
        } else if (base != null) {
            toast(if (isEn) "Recording stopped (no file was saved)" else "Gravação parada (nenhum arquivo salvo)")
        } else {
            // Gravação de antes de o processo ter reiniciado: não sabemos o nome dos ficheiros.
            MediaScannerConnection.scanFile(context, arrayOf(SCAN_DIR), null, null)
            toast(if (isEn) "Recording stopped — see Movies/OdinHub" else "Gravação parada — veja Movies/OdinHub")
        }
    }

    private fun toast(message: String) {
        main.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        const val TAG = "ButtonActionHandler"
        const val REC_DIR = "/sdcard/Movies/OdinHub"
        const val SCAN_DIR = "/storage/emulated/0/Movies/OdinHub"
        const val REC_FLAG = "/data/local/tmp/odinhub_rec.flag"
        const val REC_BITRATE = 20_000_000
        val PACKAGE_NAME = Regex("[A-Za-z0-9_.]+")
        val SAFE_SCRIPT_PATH = Regex("/[A-Za-z0-9_./-]+")

        /** Ignora um segundo toque logo a seguir (duplo clique acidental no gesto). */
        const val TOGGLE_GUARD_MS = 800L
    }
}
