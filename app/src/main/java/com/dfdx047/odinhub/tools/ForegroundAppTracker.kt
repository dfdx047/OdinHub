package com.dfdx047.odinhub.tools

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fonte única de verdade, **reativa**, sobre qual app está em primeiro plano.
 *
 * CAUSA RAIZ que esta classe resolve (bug relatado: "o overlay e o banco de dados não estão
 * interconectados"): o overlay lia o pacote atual com `val currentApp = prefs.currentForegroundApp`
 * — uma leitura simples de SharedPreferences, que **não é um `State` do Compose**. Como a
 * `ComposeView` do overlay é criada UMA única vez (no `onCreate` do [com.dfdx047.odinhub.service.GamingOverlayService])
 * e nunca mais recompõe por causa disso, `currentApp` ficava congelado no valor que estivesse
 * gravado quando o serviço arrancou — normalmente `"global"`, ou o jogo anterior. Resultado: o
 * utilizador abria o GTA, punha 5 W no overlay, e a linha era gravada no Room **sob o pacote
 * errado**. O ecrã "Per-App Overrides" nunca mostrava nada para o GTA porque, do ponto de vista do
 * banco, aquela regra nunca foi criada para o GTA.
 *
 * Com um [StateFlow], o overlay passa a observar (`collectAsState`) e a sua composição re-chaveia
 * sozinha a cada troca de jogo: os `remember(currentApp)` voltam a ler os valores do jogo certo, e
 * as escritas passam a cair na linha certa do banco.
 *
 * [isGameForeground] responde ao segundo bug relatado ("o overlay só deve aparecer dentro do
 * jogo, igual aos modos game de celular"): começa em `false` (antes começava em `true`, e por isso
 * a barrinha aparecia na home até o serviço de acessibilidade disparar pela primeira vez) e só
 * passa a `true` quando o app em primeiro plano é mesmo um app de utilizador.
 */
@Singleton
class ForegroundAppTracker @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Pacote do app de utilizador atualmente em primeiro plano, ou [GLOBAL] fora de qualquer app. */
    private val _currentPackage = MutableStateFlow(GLOBAL)
    val currentPackage: StateFlow<String> = _currentPackage.asStateFlow()

    /**
     * `true` só quando há um app/jogo de utilizador em primeiro plano. Arranca em `false` de
     * propósito: a barrinha do overlay nunca deve aparecer antes de sabermos que estamos mesmo
     * dentro de um app.
     */
    private val _isGameForeground = MutableStateFlow(false)
    val isGameForeground: StateFlow<Boolean> = _isGameForeground.asStateFlow()

    /** Scope próprio do singleton: a sondagem de reserva vive enquanto o processo viver. */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pollingJob: Job? = null

    @Volatile
    private var accessibilityConnected = false

    /**
     * Chamado pelo [com.dfdx047.odinhub.service.ForegroundAppWatcherService] quando liga/desliga.
     * Enquanto o serviço estiver ligado, ele é a fonte de eventos (imediato, sem sondagem).
     */
    fun setAccessibilityConnected(connected: Boolean) {
        accessibilityConnected = connected
        if (connected) {
            Log.i(TAG, "Serviço de acessibilidade ligado -- deteção por eventos ativa.")
        } else {
            Log.w(TAG, "Serviço de acessibilidade desligado -- a usar sondagem de reserva.")
        }
    }

    /**
     * Rede de segurança para o bug relatado "o overlay não aparece em jogo nenhum".
     *
     * A deteção de primeiro plano depende do serviço de acessibilidade. Só que esse serviço nunca
     * chegava a ser ativado (ver SettingsRepo.enableA11yService: nome de componente errado e falta
     * de `accessibility_enabled`), portanto [onForegroundPackage] nunca era chamado,
     * [isGameForeground] ficava eternamente `false` e o puxador não aparecia em lado nenhum. Antes
     * isto passava despercebido só porque o valor inicial era `true` e a barrinha aparecia em todo
     * o lado — trocar um bug pelo outro.
     *
     * Esta sondagem corre apenas quando o serviço de acessibilidade NÃO está ligado, e usa o
     * UsageStatsManager (permissão que a app já pede). Se nem isso estiver disponível, entramos em
     * modo degradado: assumimos que há um app em primeiro plano e mostramos o puxador, em vez de o
     * esconder para sempre sem o utilizador perceber porquê.
     */
    fun startFallbackDetection() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (isActive) {
                if (!accessibilityConnected) {
                    val pkg = resolveForegroundPackage()
                    if (pkg != null) {
                        onForegroundPackage(pkg)
                    } else if (PermissionChecker.hasUsageAccess(context)) {
                        // Com acesso às estatísticas, "nenhum evento" só quer dizer que ninguém
                        // mudou de app no último minuto: mantém o estado que já tínhamos (antes
                        // isto mostrava o puxador no launcher ao fim de 60 s parado).
                    } else {
                        // Sem acessibilidade E sem acesso a estatísticas de uso: não temos como
                        // saber onde estamos. Mostrar o puxador é o mal menor -- o utilizador
                        // consegue pelo menos usar e configurar o overlay.
                        _isGameForeground.value = true
                    }
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopFallbackDetection() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * Eventos de acessibilidade chegam às dezenas por segundo em algumas apps; resolver intents no
     * PackageManager a cada um seria caro. O veredito por pacote nunca muda enquanto a app estiver
     * instalada, portanto fica em cache.
     */
    private val trackableCache = ConcurrentHashMap<String, Boolean>()

    /**
     * Todos os pacotes que conseguem atuar como ecrã inicial. Resolvido preguiçosamente e uma só
     * vez: usar `resolveActivity` sozinho devolveria apenas o launcher ativo, e em aparelhos com
     * mais de um launcher instalado (muito comum em handhelds — Daijisho, ES-DE, o launcher de
     * fábrica da AYN) os outros continuariam a contar como "jogo".
     */
    private val homePackages: Set<String> by lazy {
        runCatching {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager
                .queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrElse {
            Log.w(TAG, "Falha ao resolver os launchers do sistema", it)
            emptySet()
        }
    }

    /**
     * Decide se um pacote conta como "app de utilizador" (jogo, emulador, app normal) para efeitos
     * de overlay e de regras por app.
     *
     * Antes isto era `pkg != launcherPackage` mais um filtro pelo nome do projeto base do qual
     * esta app derivou. Os dois estavam errados:
     *  - Aquele filtro comparava o pacote com o nome antigo do projeto, que nunca correspondeu ao
     *    `applicationId` real (`com.dfdx047.odinhub`) — por isso nunca dava verdade e o próprio
     *    Odin Hub era tratado como um jogo, com barrinha do overlay por cima do próprio app e
     *    regras por app criadas para ele. Agora comparamos com `context.packageName`, que é
     *    sempre a verdade em tempo de execução, seja qual for o nome do pacote.
     *  - `!= launcherPackage` deixava passar Definições, diálogos do sistema, teclados, a folha de
     *    permissões, etc.
     *
     * O critério agora é: não somos nós, não é um launcher, não é a UI do sistema, e o pacote tem
     * um *launcher intent* próprio — ou seja, é algo que o utilizador consegue abrir a partir da
     * gaveta de apps. Diálogos do sistema, IMEs e componentes internos não têm.
     */
    fun isTrackableApp(pkg: String): Boolean = trackableCache.getOrPut(pkg) {
        when {
            pkg.isBlank() -> false
            pkg == context.packageName -> false
            pkg == ANDROID_SYSTEM -> false
            pkg.endsWith(".systemui") -> false
            pkg in homePackages -> false
            else -> runCatching {
                context.packageManager.getLaunchIntentForPackage(pkg) != null
            }.getOrDefault(false)
        }
    }

    /**
     * Descobre qual o app em primeiro plano AGORA, sem depender de eventos de acessibilidade.
     *
     * Serve para semear o estado quando o serviço de acessibilidade arranca com um jogo já aberto
     * (ou é reiniciado pelo sistema a meio do jogo): sem isto, o overlay ficaria escondido dentro
     * do jogo até o utilizador trocar de app e voltar.
     *
     * Usa o [UsageStatsManager] — e não `rootInActiveWindow` — porque o serviço está declarado com
     * `canRetrieveWindowContent="false"` (ver accessibility_service_config.xml). Com essa flag a
     * `false`, `rootInActiveWindow` devolve sempre null, por isso qualquer tentativa de semear por
     * aí falharia em silêncio. A permissão PACKAGE_USAGE_STATS já é pedida e verificada no
     * arranque da app (ver PermissionChecker/MainActivity).
     */
    fun resolveForegroundPackage(lookbackMs: Long = FOREGROUND_LOOKBACK_MS): String? = runCatching {
        val usageStats = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return@runCatching null

        val now = System.currentTimeMillis()
        val events = usageStats.queryEvents(now - lookbackMs, now)
        val event = UsageEvents.Event()
        var lastResumed: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastResumed = event.packageName
            }
        }
        lastResumed
    }.getOrNull()

    /**
     * Chamado pelo watcher de acessibilidade a cada mudança de app em primeiro plano.
     * Devolve `true` quando o novo pacote é um app de utilizador (e portanto as regras por app
     * devem ser aplicadas); `false` quando voltámos para a home/sistema/para esta própria app.
     */
    /**
     * `true` para janelas que aparecem POR CIMA do jogo sem que o utilizador tenha saído dele:
     * a cortina de notificações e as notificações heads-up (SystemUI), o teclado, diálogos do
     * sistema ("android"), popups de outros serviços sem ícone na gaveta, etc.
     *
     * BUG RELATADO: "aciono o overlay, desço a barra de notificação (ou chega uma notificação) e o
     * overlay some; só volta indo aos recentes e reabrindo o jogo". A cortina emite um
     * TYPE_WINDOW_STATE_CHANGED com o pacote da SystemUI; nós tratávamos isso como "saiu do
     * jogo" e escondíamos o puxador. Ao fechar a cortina, o jogo volta a ter foco mas NÃO emite
     * um novo evento -- por isso o puxador ficava escondido até se trocar de app.
     *
     * Estas janelas passam a ser ignoradas por completo: o estado anterior (jogo X à frente)
     * mantém-se. Só um launcher, a própria Odin Hub (MainActivity) ou outro app de utilizador
     * mudam o estado.
     */
    fun isTransientWindow(pkg: String): Boolean =
        com.dfdx047.odinhub.models.FeatureFlags.IGNORE_TRANSIENT_WINDOWS && pkg.isNotBlank() && pkg != context.packageName && pkg !in homePackages && !isTrackableApp(pkg)

    fun onForegroundPackage(pkg: String): Boolean {
        // Janela transitória por cima do jogo: nada muda (ver isTransientWindow).
        if (isTransientWindow(pkg)) return _isGameForeground.value
        val trackable = isTrackableApp(pkg)
        // Deliberadamente NÃO persistimos isto em SharedPreferences. O antigo
        // `prefs.currentForegroundApp` era precisamente a origem do bug: um valor guardado em
        // disco que sobrevivia ao processo e era lido mais tarde como se ainda fosse verdade,
        // quando o app em primeiro plano é, por natureza, informação do momento. Ao arrancar não
        // há nenhum app em primeiro plano conhecido até o serviço de acessibilidade reportar um
        // (ver ForegroundAppWatcherService.onServiceConnected).
        _currentPackage.value = if (trackable) pkg else GLOBAL
        _isGameForeground.value = trackable
        return trackable
    }

    companion object {
        private const val TAG = "ForegroundAppTracker"
        private const val ANDROID_SYSTEM = "android"

        /** Janela de histórico consultada ao semear o app em primeiro plano (ver [resolveForegroundPackage]). */
        private const val FOREGROUND_LOOKBACK_MS = 60_000L

        /**
         * Cadência da sondagem de reserva. Só corre quando o serviço de acessibilidade está em
         * baixo, e uma consulta ao UsageStatsManager é barata, por isso 1,5 s dá uma resposta
         * praticamente imediata ao entrar num jogo sem pesar na bateria.
         */
        private const val POLL_INTERVAL_MS = 1_500L

        /**
         * Sentinela para "nenhum app de utilizador em primeiro plano". NUNCA deve ser gravado como
         * `packageName` no Room — ver a guarda em `QuickAccessContent.persistOverride`, que existe
         * precisamente porque era sob este valor que as regras do jogo acabavam a ser gravadas.
         */
        const val GLOBAL = "global"
    }
}
