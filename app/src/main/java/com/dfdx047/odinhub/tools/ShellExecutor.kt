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

    val pServerAvailable: Boolean get() = currentBinder() != null

    /**
     * Segundo elemento do pedido ao PServer: "1" = devolver a saída do comando, "0" = sem saída.
     * Há firmwares (o Odin 2 Mini é o caso conhecido) em que os pedidos com captura de saída não
     * correm de forma fiável. Testamos uma vez; se a captura não funcionar passamos a "0" e as
     * leituras fazem-se diretamente aos ficheiros (os nós de sysfs são legíveis sem root).
     */
    @Volatile
    private var captureFlag = "1"

    @Volatile
    private var captureProbed = false

    /** `false` quando o PServer deste firmware não devolve a saída dos comandos. */
    val outputCaptureWorks: Boolean
        get() {
            probeCaptureIfNeeded()
            return captureFlag == "1"
        }

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

    @Synchronized
    private fun probeCaptureIfNeeded() {
        if (captureProbed) return
        // Sem binder (ainda) não se marca a prova como feita: tenta-se de novo quando existir.
        val b = currentBinder() ?: return
        captureProbed = true
        val first = transactRaw(b, "echo $PROBE_TOKEN", "1")
        if (first.getOrNull()?.contains(PROBE_TOKEN) == true) return
        // Segunda prova, com um comando externo: só desistimos da captura se as duas falharem.
        val second = transactRaw(b, "cat /proc/sys/kernel/random/boot_id", "1")
        if (!second.getOrNull().isNullOrBlank()) return
        captureFlag = "0"
        Log.w(TAG, "PServer sem captura de saída neste firmware: a usar o modo '0' (leituras diretas)")
    }

    /**
     * O PServerBinder da AYN corre como root (uid 0): tudo o que a app escreve em sysfs -- TDP,
     * clocks, GPU, ventoinha, limite térmico -- passa por ele. Por isso a app NÃO usa `su` nem
     * KernelSU/Magisk em lado nenhum (o antigo interruptor "Usar KernelSU" e o caminho `su -c`
     * foram removidos: além de desnecessários, o `su -c id` de deteção fazia aparecer um pedido
     * de root a cada arranque em aparelhos com KernelSU).
     */
    val canWriteSysfs: Boolean get() = pServerAvailable

    fun executeAsRoot(cmd: String): Result<String?> {
        val localBinder = currentBinder()
        if (localBinder == null) {
            Log.w(TAG, "executeAsRoot: PServerBinder indisponível, comando descartado: $cmd")
            return Result.failure(IllegalStateException("PServerBinder not available"))
        }
        probeCaptureIfNeeded()
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
                    Log.e(TAG, "executeAsRoot: transact() lançou exceção para '$cmd'", it)
                    return Result.failure(it)
                }

            // CAUSA RAIZ: transact() devolve `false` (sem lançar exceção) quando o binder
            // remoto rejeita ou não consegue processar a transação. O código original nunca
            // verificava este valor, pelo que o comando falhava em silêncio e o chamador via
            // um "sucesso" com resultado nulo. Isto explica por que os sliders da UI mudavam
            // mas os nós de sysfs (TDP/clocks/GPU) não eram realmente escritos.
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