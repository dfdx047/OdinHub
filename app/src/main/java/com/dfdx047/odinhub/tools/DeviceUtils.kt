package com.dfdx047.odinhub.tools

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import com.dfdx047.odinhub.models.DeviceIdentity
import com.dfdx047.odinhub.models.DeviceProfile
import com.dfdx047.odinhub.models.DeviceProfiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * O aparelho em que a app está a correr, detetado UMA vez por processo.
 *
 * Só usa `android.os.Build` e system properties lidas por reflexão (`SystemProperties.get`): as
 * `ro.*` são legíveis por qualquer app, por isso a deteção NÃO passa pelo PServerBinder. A versão
 * anterior fazia `getprop` via root na thread principal (no `init` do MainViewModel) -- duas
 * transações binder bloqueantes antes do primeiro fotograma, e um ANR se o PServer estivesse lento.
 */
object DeviceRegistry {
    private const val TAG = "DeviceRegistry"

    val identity: DeviceIdentity by lazy {
        runCatching {
            DeviceIdentity(
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                socModel = Build.SOC_MODEL.orEmpty().ifBlank { sysProp("ro.soc.model") },
                platform = sysProp("ro.board.platform"),
                hardware = Build.HARDWARE.orEmpty(),
                retroName = sysProp("ro.vendor.retro.name"),
            )
        }.getOrElse {
            Log.w(TAG, "identity: falha a ler Build/props", it)
            DeviceIdentity.EMPTY
        }
    }

    val current: DeviceProfile by lazy {
        DeviceProfiles.detect(identity).also {
            Log.i(TAG, "Aparelho: ${it.name} (${it.soc.label}, oficial=${it.official}) <- $identity")
        }
    }

    /** Versão do firmware AYN (`ro.build.odin2.ota.version`), ou vazio. */
    val firmwareVersion: String by lazy { sysProp("ro.build.odin2.ota.version") }

    @SuppressLint("PrivateApi")
    fun sysProp(key: String): String = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        clazz.getMethod("get", String::class.java).invoke(null, key) as? String
    }.getOrNull().orEmpty().trim()
}

/**
 * Capacidades confirmadas em tempo de execução (o perfil do aparelho é só o palpite inicial).
 *
 * Hoje: se o firmware tem os botões traseiros remapeáveis (M1/M2). A chave
 * `remap_custom_to_m1_value` existe no firmware AYN do Odin 2/3; nos Retroid pode não existir.
 * A leitura é via PServer (bloqueante), por isso corre em IO e a UI observa o StateFlow.
 */
@Singleton
class DeviceCapabilities @Inject constructor(
    private val executor: ShellExecutor,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _backButtons = MutableStateFlow(DeviceRegistry.current.backButtons)
    val backButtons: StateFlow<Boolean> = _backButtons.asStateFlow()

    init {
        scope.launch {
            if (!executor.pServerAvailable) return@launch
            // `settings get` devolve "null" (-> null) quando a chave não existe. Só serve para
            // LIGAR: num Odin acabado de configurar a chave pode ainda não existir (o utilizador
            // nunca remapeou nada) e os botões existem na mesma -- aí vale o perfil do aparelho.
            val value = executor.getSystemSettingOrNull(SettingsRepo.KEY_CUSTOM_M1_VALUE)
            if (value?.toIntOrNull() != null) _backButtons.value = true
        }
    }
}

/** Fachada mantida para os ViewModels que já a injetavam. Não faz I/O root. */
class DeviceUtils @Inject constructor() {
    fun getDeviceVersion(): String = DeviceRegistry.firmwareVersion
    fun getDeviceProfile(): DeviceProfile = DeviceRegistry.current
}
