package com.dfdx047.odinhub.tools

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.dfdx047.odinhub.BuildConfig
import com.dfdx047.odinhub.data.SharedPrefsRepo
import com.dfdx047.odinhub.models.AppInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/** Uma versão publicada no GitHub com um APK anexado. */
data class AppRelease(
    val tag: String,
    val title: String,
    val notes: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
)

/** Estado do auto-update para a UI. */
sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Available(val release: AppRelease) : UpdateState()

    /** [progress] 0..1, ou -1 quando o servidor não diz o tamanho. */
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState()
    data class Installing(val release: AppRelease) : UpdateState()

    /** Instalação entregue ao instalador do sistema (o utilizador confirma lá). */
    data class WaitingForInstaller(val release: AppRelease) : UpdateState()
    data class Failed(val message: String, val release: AppRelease? = null) : UpdateState()
}

/**
 * Auto-update a partir das releases do GitHub.
 *
 *  1. [check]: `GET /repos/<dono>/<repo>/releases/latest` -- só vê releases PUBLICADAS (os rascunhos
 *     que o workflow cria não contam até serem publicados no GitHub).
 *  2. [downloadAndInstall]: descarrega o APK para a pasta da app e instala:
 *     - pelo PServerBinder (root): `pm install -r`, sem perguntas, e volta a abrir a app;
 *     - sem PServer, ou se falhar: entrega o APK ao instalador do Android (o utilizador confirma).
 *
 * ATENÇÃO À ASSINATURA: o Android só aceita a atualização se o APK novo estiver assinado com a
 * MESMA chave do instalado. Builds de máquinas diferentes com a chave de debug de cada uma não
 * se atualizam entre si (INSTALL_FAILED_UPDATE_INCOMPATIBLE) -- ver `signingConfigs` no Gradle.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val executor: ShellExecutor,
    private val prefs: SharedPrefsRepo,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Verificação automática no arranque: no máximo a cada [AUTO_CHECK_INTERVAL_MS], e respeita "ignorar esta versão". */
    suspend fun autoCheck() {
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck < AUTO_CHECK_INTERVAL_MS) return
        prefs.lastUpdateCheck = now
        val found = check(manual = false)
        if (found != null && found.tag == prefs.skippedUpdateTag) _state.value = UpdateState.Idle
    }

    /** Pergunta ao GitHub pela última release. Devolve-a se for mais nova do que a instalada. */
    suspend fun check(manual: Boolean = true): AppRelease? = withContext(Dispatchers.IO) {
        if (manual) _state.value = UpdateState.Checking
        val result = runCatching { fetchLatest() }
        val release = result.getOrNull()
        _state.value = when {
            result.isFailure -> if (manual) UpdateState.Failed(errorText(result.exceptionOrNull())) else UpdateState.Idle
            release == null -> if (manual) UpdateState.UpToDate else UpdateState.Idle
            isNewer(release.tag, BuildConfig.VERSION_NAME) -> UpdateState.Available(release)
            else -> if (manual) UpdateState.UpToDate else UpdateState.Idle
        }
        release?.takeIf { isNewer(it.tag, BuildConfig.VERSION_NAME) }
    }

    fun skip(release: AppRelease) {
        prefs.skippedUpdateTag = release.tag
        _state.value = UpdateState.Idle
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    suspend fun downloadAndInstall(release: AppRelease) = withContext(Dispatchers.IO) {
        val apk = runCatching { download(release) }.getOrElse {
            Log.w(TAG, "download falhou", it)
            _state.value = UpdateState.Failed(errorText(it), release)
            return@withContext
        }
        _state.value = UpdateState.Installing(release)
        if (executor.pServerAvailable && installWithRoot(apk)) return@withContext
        installWithSystemInstaller(apk, release)
    }

    // ------------------------------------------------------------------ GitHub

    private fun fetchLatest(): AppRelease? {
        val conn = (URL("$API/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "OdinHub/${BuildConfig.VERSION_NAME}")
        }
        try {
            // 404 = ainda não há nenhuma release publicada.
            if (conn.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (conn.responseCode != HttpURLConnection.HTTP_OK) error("GitHub HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            var size = 0L
            if (assets != null) {
                // Prefere o APK de release; senão, qualquer .apk anexado.
                val candidates = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
                val best = candidates.firstOrNull { it.optString("name").contains("release", ignoreCase = true) } ?: candidates.firstOrNull()
                apkUrl = best?.optString("browser_download_url")?.takeIf { it.isNotBlank() }
                size = best?.optLong("size") ?: 0L
            }
            apkUrl ?: return null // release sem APK: nada para instalar
            return AppRelease(
                tag = json.optString("tag_name"),
                title = json.optString("name").ifBlank { json.optString("tag_name") },
                notes = json.optString("body"),
                apkUrl = apkUrl,
                apkSizeBytes = size,
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun download(release: AppRelease): File {
        val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // só guardamos o APK mais recente
        val out = File(dir, "OdinHub-${release.tag.filter { it.isLetterOrDigit() || it == '.' }}.apk")
        var url = URL(release.apkUrl)
        var conn: HttpURLConnection
        // O GitHub redireciona os anexos para outro domínio; segue até 5 saltos.
        var hops = 0
        while (true) {
            conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "OdinHub/${BuildConfig.VERSION_NAME}")
            }
            val code = conn.responseCode
            if (code in 300..399 && hops < 5) {
                url = URL(url, conn.getHeaderField("Location"))
                conn.disconnect()
                hops++
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) error("HTTP $code")
            break
        }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSizeBytes
        try {
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastEmit = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (done - lastEmit > 256 * 1024 || done == total) {
                            lastEmit = done
                            _state.value = UpdateState.Downloading(release, if (total > 0) done.toFloat() / total else -1f)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (total > 0 && out.length() != total) error("download incompleto")
        return out
    }

    // ------------------------------------------------------------------ instalação

    /**
     * Instala como root. O processo da app morre a meio (está a ser substituído), por isso o
     * `am start` vai na MESMA linha de comando: corre no shell do PServer, que sobrevive, e volta
     * a abrir a app atualizada.
     */
    private fun installWithRoot(apk: File): Boolean {
        val pkg = context.packageName
        // O shell root pode ver o armazenamento por outro caminho: copia para /data/local/tmp.
        val sources = listOf(apk.absolutePath, apk.absolutePath.replace("/storage/emulated/0/", "/data/media/0/"))
        for (src in sources) {
            if (!src.matches(SAFE_PATH)) continue
            val cmd = "cp $src $TMP_APK && chmod 644 $TMP_APK && pm install -r $TMP_APK; r=\$?; rm -f $TMP_APK; " +
                "[ \$r -eq 0 ] && am start -n $pkg/.main.MainActivity"
            val out = executor.executeAsRoot(cmd).getOrNull().orEmpty()
            if (out.contains("Success")) return true
            if (out.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")) {
                _state.value = UpdateState.Failed(
                    if (prefs.isEnglish) "This update is signed with a different key than the installed app. Uninstall Odin Hub and install the new APK manually."
                    else "Esta atualização está assinada com uma chave diferente da instalada. Desinstale o Odin Hub e instale o APK novo manualmente.",
                )
                return true // não adianta tentar o instalador: falharia pelo mesmo motivo
            }
            // Sem captura de saída (ex: Odin 2 Mini) não há "Success" para ler: o instalador
            // do sistema ainda é o caminho seguro.
            if (!executor.outputCaptureWorks) return false
        }
        return false
    }

    private fun installWithSystemInstaller(apk: File, release: AppRelease) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            _state.value = UpdateState.WaitingForInstaller(release)
        }.onFailure {
            Log.w(TAG, "instalador do sistema falhou", it)
            _state.value = UpdateState.Failed(errorText(it), release)
        }
    }

    private fun errorText(t: Throwable?): String =
        (if (prefs.isEnglish) "Update failed: " else "Falha na atualização: ") + (t?.message ?: t?.javaClass?.simpleName ?: "?")

    companion object {
        private const val TAG = "UpdateManager"
        private const val API = "https://api.github.com"

        /** "dono/repo" tirado do link do projeto (AppInfo.GITHUB_URL). */
        val REPO: String = AppInfo.GITHUB_URL.removePrefix("https://github.com/").trimEnd('/')

        private const val TMP_APK = "/data/local/tmp/odinhub_update.apk"
        private val SAFE_PATH = Regex("/[A-Za-z0-9_./-]+")
        const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

        fun isNewer(remoteTag: String, installed: String): Boolean =
            com.dfdx047.odinhub.models.Versions.isNewer(remoteTag, installed)
    }
}
