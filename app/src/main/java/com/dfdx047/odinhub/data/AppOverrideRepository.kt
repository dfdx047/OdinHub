package com.dfdx047.odinhub.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fonte única de verdade para as regras por jogo (TDP/Clock/Fan/LSFG/SGSR/ReShade).
 *
 * Antes desta auditoria existiam DOIS armazenamentos paralelos e desligados um do outro:
 *  - A tabela Room `AppOverrideEntity`, escrita apenas pelo ecrã "Per-App Overrides" (aba
 *    Performance), guardando só NOMES de perfil (ex: "Balanced") sem nenhum valor numérico.
 *  - Chaves soltas no SharedPrefs (`override_<pkg>_tdp`, etc.), escritas apenas pelo overlay
 *    em jogo, e são estas — não a tabela Room — que o `ForegroundAppWatcherService`
 *    efetivamente lia para aplicar os limites.
 * Resultado: configurar um jogo na aba Performance não tinha NENHUM efeito em jogo, porque
 * nada nunca traduzia o nome do perfil escolhido ali para os números que o motor de hardware
 * usa. Este repositório substitui os dois: os valores numéricos passam a viver só no Room
 * (`AppOverrideEntity.tdpWatts/perfClockKHz/...`), e tanto o overlay como o watcher leem e
 * escrevem exclusivamente através daqui.
 *
 * Expomos um [StateFlow] em cache (por pacote) para que o [com.dfdx047.odinhub.service.ForegroundAppWatcherService]
 * -- que corre no thread de eventos de acessibilidade e não pode esperar por uma query
 * suspend a cada troca de app -- consiga ler o último valor conhecido de forma síncrona via
 * `.value`, sem nunca bloquear esse thread em I/O de disco.
 */
@Singleton
class AppOverrideRepository @Inject constructor(
    private val dao: AppOverrideDao,
) {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val loaded = MutableStateFlow(false)

    val overridesByPackage: StateFlow<Map<String, AppOverrideEntity>> =
        dao.getAll()
            .map { list -> list.associateBy { it.packageName } }
            .onEach { loaded.value = true }
            .stateIn(repositoryScope, SharingStarted.Eagerly, emptyMap())

    /** Leitura síncrona e não-bloqueante a partir do cache em memória (sempre atualizado pelo Flow do Room). */
    fun get(packageName: String): AppOverrideEntity? = overridesByPackage.value[packageName]

    /**
     * Como [get], mas espera (até [timeoutMs]) que o Room entregue a primeira leitura. Num
     * processo acabado de arrancar o cache ainda está vazio: o watcher lia "sem regra" e aplicava
     * o perfil GLOBAL ao jogo em vez da regra dele, e nada corrigia isso até à troca de app seguinte.
     */
    suspend fun getWhenLoaded(packageName: String, timeoutMs: Long = 3_000L): AppOverrideEntity? {
        if (!loaded.value) withTimeoutOrNull(timeoutMs) { loaded.first { it } }
        return get(packageName)
    }

    fun has(packageName: String): Boolean = get(packageName) != null

    suspend fun upsert(entity: AppOverrideEntity) = withContext(Dispatchers.IO) {
        dao.save(entity)
    }

    /**
     * Escrita "dispara e esquece", presa ao scope do repositório (singleton) em vez do scope de
     * quem chama.
     *
     * Existe por causa de um caminho que perdia escritas em silêncio: o overlay gravava com
     * `rememberCoroutineScope()`, que o Compose cancela assim que o painel sai da composição. A
     * gravação de segurança feita no `onDispose` (o momento em que o painel fecha, ou em que o
     * utilizador troca de jogo) era lançada exatamente nesse scope já cancelado -- ou seja, a
     * última alteração feita antes de fechar o painel podia nunca chegar ao Room. Aqui a coroutine
     * pertence ao repositório, que vive enquanto o processo viver.
     */
    fun upsertAsync(entity: AppOverrideEntity) {
        // Um só escritor, por ordem: dois toques seguidos no overlay lançavam duas coroutines no
        // pool de IO (multi-thread) e a fotografia mais antiga podia ser gravada por ÚLTIMO.
        repositoryScope.launch(serialWriter) { dao.save(entity) }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val serialWriter = Dispatchers.IO.limitedParallelism(1)

    suspend fun delete(packageName: String) = withContext(Dispatchers.IO) {
        dao.deleteByPackageName(packageName)
    }
}
