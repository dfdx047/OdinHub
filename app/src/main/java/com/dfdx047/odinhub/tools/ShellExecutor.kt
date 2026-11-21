package com.dfdx047.odinhub.tools

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exceção usada quando o binder do PServer devolve `false` em [IBinder.transact] — ou seja, a
 * transação foi entregue mas o lado remoto rejeitou-a (comando malformado, código de transação
 * desconhecido, ou o processo do servidor morreu a meio). Antes desta auditoria, esse `false`
 * era completamente ignorado e o chamador recebia `Result.success(null)`, indistinguível de uma
 * leitura legítima que devolve "null" — a causa raiz de porque as escritas em sysfs pareciam
 * "não colar" sem qualquer erro visível.
 */
class PServerTransactionRejectedException(cmd: String) :
    IllegalStateException("PServer rejeitou a transação (transact()==false) para: $cmd")

/**
 * Lançada quando um valor destinado a um comando root contém caracteres que o shell interpretaria
 * (`;`, `|`, `&`, `$`, backtick, redirecionamentos, nova linha...).
 *
 * Hoje todos os valores que chegam aqui vêm de sliders com `coerceIn` ou de constantes do código,
 * por isso não há nenhuma injeção possível na prática. Isto existe como rede de segurança para o
 * futuro: os comandos são montados por concatenação de strings e executados com privilégios de
 * root, portanto basta um único campo de texto livre novo chegar a um `echo ... > /sys/...` para
 * isto passar de teórico a real. Falhar de forma barulhenta numa escrita legítima é muito
 * preferível a executar silenciosamente algo que o utilizador não pediu.
 */
class UnsafeShellValueException(value: String) :
    IllegalArgumentException("Valor recusado por conter metacaracteres de shell: $value")

/**
 * Caracteres que dão ao shell um significado para além de "texto": encadeamento de comandos,
 * substituição, redirecionamento e quebras de linha.
 */
private val SHELL_METACHARACTERS = charArrayOf(
    ';', '|', '&', '$', '`', '>', '<', '(', ')', '{', '}', '\n', '\r', '\\', '"', '\'', '*', '?', '[', ']', '!', '~', '#',
)

private fun String.containsShellMetacharacters(): Boolean = any { it in SHELL_METACHARACTERS }

/** Aspas simples de shell: `it's` -> `'it'\''s'`. Para qualquer texto não-constante num comando root. */
fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/** Por onde os comandos root saem. */
enum class RootBackend { PSERVER, SU, NONE }

/**
 * Sessão `su` persistente (KernelSU / Magisk). Um único processo `su` para a vida da app: cada
 * comando é escrito no stdin e a saída lê-se até um marcador. Lançar um `su` por comando custava
 * ~30-60 ms e, com o daemon de clocks, dezenas de processos por minuto.
 *
 * O stdout é lido numa thread própria para uma fila: assim um comando que bloqueie (um nó de
 * sysfs que nunca devolve) não prende quem chamou -- ao fim de [timeoutMs] a sessão é morta e a
 * seguinte arranca do zero.
 */
private class SuSession private constructor(private val process: Process) {
    private val stdin = process.outputStream.bufferedWriter()
    private val lines = java.util.concurrent.LinkedBlockingQueue<String>()
    @Volatile var alive = true
        private set
    private var seq = 0L

    init {
        Thread({
            runCatching { process.inputStream.bufferedReader().forEachLine { lines.put(it) } }
            alive = false
            lines.put(EOF)
        }, "odinhub-su-out").apply { isDaemon = true }.start()
        // O stderr tem de ser drenado, senão o buffer enche e o `su` bloqueia.
        Thread({ runCatching { val buf = ByteArray(1024); val err = process.errorStream; while (err.read(buf) >= 0) Unit } }, "odinhub-su-err")
            .apply { isDaemon = true }.start()
    }

    fun run(cmd: String, timeoutMs: Long = 10_000L): Result<String?> {
        if (!alive) return Result.failure(IllegalStateException("su session closed"))
        val marker = "__ODH_${++seq}__"
        return try {
            lines.clear()
            // `echo` sozinho fecha uma última linha sem '\n'; o marcador vai numa linha própria.
            stdin.write("$cmd\necho\necho $marker\n")
            stdin.flush()
            val out = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val left = deadline - System.currentTimeMillis()
                val line = if (left > 0) lines.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS) else null
                if (line == null) {
                    close()
                    return Result.failure(java.util.concurrent.TimeoutException("su: sem resposta em ${timeoutMs} ms: $cmd"))
                }
                if (line == EOF) return Result.failure(IllegalStateException("su session died"))
                if (line == marker) break
                out.append(line).append('\n')
            }
            Result.success(out.toString().trim().takeIf { it.isNotEmpty() && it != "null" })
        } catch (e: Exception) {
            close()
            Result.failure(e)
        }
    }

    fun close() {
        alive = false
        runCatching { stdin.close() }
        runCatching { process.destroy() }
    }

    companion object {
        private const val EOF = "\u0000EOF"

        /** Abre o `su` e confirma que é mesmo root (`id -u` = 0). `null` = sem root concedido. */
        fun open(): SuSession? {
            val process = runCatching { ProcessBuilder("su").start() }.getOrNull() ?: return null
            val session = SuSession(process)
            val uid = session.run("id -u", timeoutMs = 4_000L).getOrNull()?.trim()
            if (uid == "0") return session
            session.close()
            return null
        }
    }
}

@Singleton
@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
class ShellExecutor @Inject constructor() {

    /**
     * Binder do PServer. Já não é resolvido uma única vez para sempre: se o serviço morrer ou se a
     * app arrancar (ex: no BOOT_COMPLETED) antes de ele estar registado, voltamos a pedi-lo ao
     * ServiceManager em vez de ficar sem root até o processo morrer.
     */
    @Volatile
    private var binder: IBinder? = lookupBinder()

    /**
     * Há como correr comandos root? Barato (sem I/O) para poder ser lido na thread principal: o
     * PServer registado, ou uma sessão `su` já aberta. A escolha real do caminho é feita no
     * primeiro comando (ver [resolveBackend]).
     */
    val pServerAvailable: Boolean get() = backend == RootBackend.SU || (backend != RootBackend.NONE && currentBinder() != null)

    /**
     * Segundo elemento do pedido ao PServer: "1" = devolver a saída do comando, "0" = sem saída.
     * Há firmwares em que os pedidos com captura de saída não correm de forma fiável. Testamos uma
     * vez; se a captura não funcionar passamos a "0" e as leituras fazem-se diretamente aos
     * ficheiros (os nós de sysfs são legíveis sem root).
     */
    @Volatile
    private var captureFlag = "1"

    /**
     * Caminho escolhido. `null` = ainda não decidido.
     *
     * ODIN 2 MINI (fw 1.0.0.117, Android 13): o SELinux do firmware NÃO deixa uma app normal
     * (`untrusted_app`) falar com o PServer -- o kernel recusa a transação com EPERM
     * ("binder: transaction failed 29201/-1" no dmesg; na app aparece como DeadObjectException
     * com o pservice vivo). Só as apps de sistema da AYN (`system_app`) passam. Sem um plano B,
     * NENHUMA escrita da app chegava ao hardware nesse aparelho. Aí usa-se o `su` do KernelSU
     * (a app tem de ter Superusuário concedido no gestor do KernelSU).
     */
    @Volatile
    private var backend: RootBackend? = null

    @Volatile
    private var su: SuSession? = null

    /** `false` quando o caminho root deste firmware não devolve a saída dos comandos. */
    val outputCaptureWorks: Boolean
        get() {
            val b = resolveBackend()
            return b == RootBackend.SU || (b == RootBackend.PSERVER && captureFlag == "1")
        }

    /** Caminho root em uso (resolve-o se ainda não foi). Bloqueante na primeira chamada. */
    val rootBackend: RootBackend get() = resolveBackend()

    private fun lookupBinder(): IBinder? = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
        getService.invoke(serviceManager, "PServerBinder") as IBinder?
    }.getOrNull()

    private fun currentBinder(): IBinder? {
        val cached = binder
        if (cached != null && cached.isBinderAlive) return cached
        return lookupBinder().also { binder = it }
    }

    /**
     * Decide o caminho uma vez: PServer (com ou sem captura de saída) se aceitar transações; senão
     * `su`; senão nenhum. Sem binder E sem `su` não se marca como decidido -- o PServer pode ainda
     * não estar registado (BOOT_COMPLETED cedo) e tenta-se outra vez no comando seguinte.
     */
    @Synchronized
    private fun resolveBackend(): RootBackend {
        backend?.let { return it }
        val b = currentBinder()
        if (b != null) {
            val first = transactRaw(b, "echo $PROBE_TOKEN", "1")
            if (first.isSuccess) {
                if (first.getOrNull()?.contains(PROBE_TOKEN) != true) {
                    // Segunda prova, com um comando externo: só se desiste da captura se as duas falharem.
                    val second = transactRaw(b, "cat /proc/sys/kernel/random/boot_id", "1")
                    if (second.getOrNull().isNullOrBlank()) {
                        captureFlag = "0"
                        Log.w(TAG, "PServer sem captura de saída neste firmware: a usar o modo '0' (leituras diretas)")
                    }
                }
                backend = RootBackend.PSERVER
                Log.i(TAG, "Root pelo PServer (captura=${captureFlag == "1"})")
                return RootBackend.PSERVER
            }
            Log.w(TAG, "PServer recusa a app (SELinux?): ${first.exceptionOrNull()?.javaClass?.simpleName} -- a tentar su")
        }
        val session = SuSession.open()
        if (session != null) {
            su = session
            backend = RootBackend.SU
            Log.i(TAG, "Root pelo su (KernelSU/Magisk)")
            return RootBackend.SU
        }
        if (b != null) {
            // Binder existe mas recusa, e não há su: não vale a pena repetir a prova a cada comando.
            backend = RootBackend.NONE
            Log.e(TAG, "Sem root: o PServer recusa a app e o su não está concedido")
        }
        return RootBackend.NONE
    }

    /** Volta a decidir o caminho (ex: o utilizador acabou de conceder Superusuário). */
    @Synchronized
    fun retryRoot() {
        if (backend == RootBackend.NONE) backend = null
    }

    /**
     * O PServerBinder da AYN corre como root (uid 0): no Odin 3 tudo o que a app escreve em sysfs
     * -- TDP, clocks, GPU, ventoinha, limite térmico -- passa por ele, sem `su`. Onde o firmware
     * não deixa (Odin 2 Mini, ver [backend]), usa-se uma sessão `su` persistente.
     */
    val canWriteSysfs: Boolean get() = pServerAvailable

    fun executeAsRoot(cmd: String): Result<String?> {
        return when (resolveBackend()) {
            RootBackend.SU -> runSu(cmd)
            RootBackend.PSERVER -> runPServer(cmd)
            RootBackend.NONE -> {
                Log.w(TAG, "executeAsRoot: sem caminho root, comando descartado: $cmd")
                Result.failure(IllegalStateException("no root backend"))
            }
        }
    }

    private fun runSu(cmd: String): Result<String?> = synchronized(suLock) {
        val session = su?.takeIf { it.alive } ?: SuSession.open().also { su = it }
            ?: return Result.failure(IllegalStateException("su indisponível"))
        val r = session.run(cmd)
        if (r.isFailure && !session.alive) {
            // A sessão morreu a meio (timeout / processo morto): uma nova e uma segunda tentativa.
            val fresh = SuSession.open().also { su = it } ?: return r
            return fresh.run(cmd)
        }
        r
    }

    private val suLock = Any()

    private fun runPServer(cmd: String): Result<String?> {
        val localBinder = currentBinder()
        if (localBinder == null) {
            Log.w(TAG, "executeAsRoot: PServerBinder indisponível, comando descartado: $cmd")
            return Result.failure(IllegalStateException("PServerBinder not available"))
        }
        val result = transactRaw(localBinder, cmd, captureFlag)
        // Binder morto (o PServer reiniciou): pede-o de novo e tenta uma vez.
        if (result.exceptionOrNull() is android.os.DeadObjectException) {
            binder = null
            val fresh = currentBinder() ?: return result
            return transactRaw(fresh, cmd, captureFlag)
        }
        return result
    }

    private fun transactRaw(localBinder: IBinder, cmd: String, flag: String): Result<String?> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeStringArray(arrayOf(cmd, flag))

            val delivered = runCatching { localBinder.transact(0, data, reply, 0) }
                .getOrElse {
                    Log.e(TAG, "executeAsRoot: transact() lançou exceção para '$cmd': $it")
                    return Result.failure(it)
                }

            // transact() devolve `false` (sem lançar exceção) quando o binder remoto rejeita ou
            // não consegue processar a transação -- antes era ignorado e o comando falhava em
            // silêncio com um "sucesso" nulo.
            if (!delivered) {
                Log.e(TAG, "executeAsRoot: transact() devolveu false para '$cmd'")
                return Result.failure(PServerTransactionRejectedException(cmd))
            }

            // Em modo "0" a resposta pode vir vazia: não é erro, só não há saída.
            val result = runCatching { reply.createByteArray() }.getOrNull()
                ?.toString(Charset.defaultCharset())?.trim()
                ?.takeIf { it.isNotEmpty() && it != "null" }
            return Result.success(result)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private companion object {
        const val TAG = "ShellExecutor"
        const val PROBE_TOKEN = "odinhub_probe_ok"
    }

    /** `settings get system <key>`, ou `null` se a chave não existir / a leitura falhar. */
    fun getSystemSettingOrNull(setting: String): String? {
        if (!isSafeForCommand(setting)) return null
        return getSystemSetting(setting).getOrNull()
    }

    /** Como [getIntSystemSetting], mas `null` numa falha -- para quem não pode confundir falha com 0. */
    fun getIntSystemSettingOrNull(setting: String): Int? = getSystemSettingOrNull(setting)?.trim()?.toIntOrNull()

    private fun getProperty(property: String): Result<String?> {
        return executeAsRoot("getprop $property")
    }

    fun getIntProperty(property: String, defaultValue: Int): Int {
        return getProperty(property)
            .mapCatching { it?.toInt() ?: defaultValue }
            .getOrDefault(defaultValue)
    }

    fun getFloatProperty(property: String, defaultValue: Float): Float {
        return getProperty(property)
            .mapCatching { it?.toFloat() ?: defaultValue }
            .getOrDefault(defaultValue)
    }

    fun getBooleanProperty(property: String, defaultValue: Boolean): Boolean {
        return getProperty(property)
            .map { if (it == null) defaultValue else it == "1" }
            .getOrDefault(defaultValue)
    }

    fun getStringProperty(property: String, defaultValue: String): String {
        return getProperty(property)
            .map { it ?: defaultValue }
            .getOrDefault(defaultValue)
    }

    private fun getSystemSetting(setting: String): Result<String?> {
        return executeAsRoot("settings get system $setting")
    }

    fun getStringSystemSetting(setting: String, defaultValue: String): String {
        return getSystemSetting(setting)
            .map { it ?: defaultValue }
            .getOrDefault(defaultValue)
    }

    fun setStringSystemSetting(setting: String, value: String) {
        if (!isSafeForCommand(setting, value)) return
        executeAsRoot("settings put system $setting $value")
    }

    /**
     * Valida os fragmentos que vão ser concatenados num comando executado como root.
     *
     * Os comandos aqui são montados por interpolação de strings (`"echo $value > $file"`), o que
     * é seguro enquanto tudo o que entra vier de sliders com `coerceIn` e de constantes — o caso
     * de hoje. Assim que um campo de texto livre chegar a um destes caminhos, passa a ser
     * injeção de shell com privilégios de root. Em vez de confiar que isso nunca vai acontecer,
     * recusamos o comando e registamos o motivo: uma escrita perdida é um bug visível e fácil de
     * diagnosticar; um comando injetado executado como root, não.
     */
    private fun isSafeForCommand(vararg fragments: String): Boolean {
        fragments.forEach { fragment ->
            if (fragment.containsShellMetacharacters()) {
                Log.e(TAG, "Comando recusado", UnsafeShellValueException(fragment))
                return false
            }
        }
        return true
    }

    fun getIntSystemSetting(setting: String, defaultValue: Int): Int {
        return getSystemSetting(setting)
            .mapCatching { it?.toInt() ?: defaultValue }
            .getOrDefault(defaultValue)
    }

    fun setIntSystemSetting(setting: String, value: Int): Boolean {
        if (!isSafeForCommand(setting)) return false
        return executeAsRoot("settings put system $setting $value").isSuccess
    }

    fun getBooleanSystemSetting(setting: String, defaultValue: Boolean): Boolean {
        return getSystemSetting(setting)
            .map { if (it == null) defaultValue else it == "1" }
            .getOrDefault(defaultValue)
    }

    fun setBooleanSystemSetting(setting: String, value: Boolean) {
        setIntSystemSetting(setting, if (value) 1 else 0)
    }

    private fun getValue(file: String): Result<String?> {
        return executeAsRoot("cat $file")
    }

    fun getStringValue(file: String, defaultValue: String): String {
        return getValue(file)
            .map { it ?: defaultValue }
            .getOrDefault(defaultValue)
    }

    fun setStringValue(file: String, value: String) {
        // `file` é validado tal como o valor: apesar de hoje vir sempre de constantes
        // (SettingsRepo.KEY_*), é ele que fecha a interpolação `echo ... > $file`.
        if (!isSafeForCommand(file, value)) return
        executeAsRoot("echo $value > $file")
    }

    fun getIntValue(file: String, defaultValue: Int): Int {
        return getValue(file)
            .mapCatching { it?.toInt() ?: defaultValue }
            .getOrDefault(defaultValue)
    }

    fun setIntValue(file: String, value: Int) {
        if (!isSafeForCommand(file)) return
        executeAsRoot("echo $value > $file")
    }

    fun getFloatValue(file: String, defaultValue: Float): Float {
        return getValue(file)
            .mapCatching { it?.toFloat() ?: defaultValue }
            .getOrDefault(defaultValue)
    }

    fun setFloatValue(file: String, value: Float) {
        if (!isSafeForCommand(file)) return
        executeAsRoot("echo $value > $file")
    }

    fun getBooleanValue(file: String, defaultValue: Boolean): Boolean {
        return getValue(file)
            .map { if (it == null) defaultValue else it == "1" }
            .getOrDefault(defaultValue)
    }

    fun setBooleanValue(file: String, value: Boolean) {
        setIntValue(file, if (value) 1 else 0)
    }
}