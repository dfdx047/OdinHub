package com.dfdx047.odinhub.data

import android.content.Context
import android.content.SharedPreferences
import com.dfdx047.odinhub.models.TdpProfiles
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class CustomProfile(val name: String, val type: String, val v1: Float, val v2: Float, val v3: Float, val v4: Float)

@Singleton
class SharedPrefsRepo @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)

    /**
     * Idioma da interface (EN/PT-BR), persistido e partilhado por TODOS os ecrãs.
     *
     * BUG QUE ISTO CORRIGE: o idioma vivia só num `rememberSaveable` do ecrã principal. O overlay,
     * o editor de regras por app e qualquer arranque novo da app não o viam -- por isso apareciam
     * textos em português mesmo com o inglês escolhido. Por omissão segue o idioma do sistema.
     */
    var isEnglish: Boolean
        get() = prefs.getBoolean("app_language_en", java.util.Locale.getDefault().language != "pt")
        set(value) {
            prefs.edit().putBoolean("app_language_en", value).apply()
            _isEnglishFlow.value = value
        }
    private val _isEnglishFlow = kotlinx.coroutines.flow.MutableStateFlow(
        prefs.getBoolean("app_language_en", java.util.Locale.getDefault().language != "pt")
    )
    /** Versão reativa de [isEnglish] -- o overlay e os ecrãs recompõem-se ao trocar de idioma. */
    val isEnglishFlow: kotlinx.coroutines.flow.StateFlow<Boolean> get() = _isEnglishFlow

    var isFirstRun: Boolean get() = prefs.getBoolean("is_first_run", true); set(value) = prefs.edit().putBoolean("is_first_run", value).apply()
    var selectedThemeIndex: Int get() = prefs.getInt("selected_theme_index", 1); set(value) = prefs.edit().putInt("selected_theme_index", value).apply()
    var useAmoledBlack: Boolean get() = prefs.getBoolean("use_amoled_black", false); set(value) = prefs.edit().putBoolean("use_amoled_black", value).apply()
    var overlayEnabled: Boolean get() = prefs.getBoolean("overlay_enabled", false); set(value) = prefs.edit().putBoolean("overlay_enabled", value).apply()
    var overlayHandleOpacity: Float get() = prefs.getFloat("overlay_handle_opacity", 0.5f); set(value) = prefs.edit().putFloat("overlay_handle_opacity", value).apply()
    var overlayHandleWidth: Int get() = prefs.getInt("overlay_handle_width", 22); set(value) = prefs.edit().putInt("overlay_handle_width", value).apply()

    var overlayPanelOpacity: Float get() = prefs.getFloat("overlay_panel_opacity", 0.95f); set(value) = prefs.edit().putFloat("overlay_panel_opacity", value).apply()
    var overlayShortcutKeyCode: Int get() = prefs.getInt("overlay_shortcut_keycode", 0); set(value) = prefs.edit().putInt("overlay_shortcut_keycode", value).apply()

    var bgmEnabled: Boolean get() = prefs.getBoolean("bgm_enabled", true); set(value) = prefs.edit().putBoolean("bgm_enabled", value).apply()
    var bgmVolume: Float get() = prefs.getFloat("bgm_volume", 0.5f); set(value) = prefs.edit().putFloat("bgm_volume", value).apply()
    var sfxEnabled: Boolean get() = prefs.getBoolean("sfx_enabled", true); set(value) = prefs.edit().putBoolean("sfx_enabled", value).apply()
    var sfxVolume: Float get() = prefs.getFloat("sfx_volume", 0.8f); set(value) = prefs.edit().putFloat("sfx_volume", value).apply()

    var disabledControllerStyle: String? get() = prefs.getString("disabled_controller_style", null); set(value) = prefs.edit().putString("disabled_controller_style", value).apply()
    var disabledL2r2Style: String? get() = prefs.getString("disabled_l2r2_style", null); set(value) = prefs.edit().putString("disabled_l2r2_style", value).apply()
    var saturationOverride: Float get() = prefs.getFloat("saturation_override", 1.0f); set(value) = prefs.edit().putFloat("saturation_override", value).apply()
    var temperatureOverride: Float get() = prefs.getFloat("temperature_override", 6500f); set(value) = prefs.edit().putFloat("temperature_override", value).apply()
    /** Já se desfez o estado antigo (Night Display ligado / HWC desativado)? Ver DisplayManager. */
    var displaySaturationActive: Boolean get() = prefs.getBoolean("display_sat_active", false); set(value) = prefs.edit().putBoolean("display_sat_active", value).apply()
    var displayMatrixActive: Boolean get() = prefs.getBoolean("display_matrix_active", false); set(value) = prefs.edit().putBoolean("display_matrix_active", value).apply()
    var displayColorMigrated: Boolean get() = prefs.getBoolean("display_color_migrated_v2", false); set(value) = prefs.edit().putBoolean("display_color_migrated_v2", value).apply()
    var appOverridesEnabled: Boolean get() = prefs.getBoolean("app_overrides_enabled", false); set(value) = prefs.edit().putBoolean("app_overrides_enabled", value).apply()
    var fanMode: Int get() = prefs.getInt("fan_mode", 0); set(value) = prefs.edit().putInt("fan_mode", value).apply()

    // ---- Gestos do botão Home (ver HomeGestureDetector / ButtonActionHandler) ----
    var homeGestureConfig: com.dfdx047.odinhub.models.HomeGestureConfig
        get() {
            val d = com.dfdx047.odinhub.models.HomeGestureConfig()
            fun action(key: String, def: com.dfdx047.odinhub.models.ButtonAction) =
                com.dfdx047.odinhub.models.ButtonAction.fromId(prefs.getString(key, def.id))
            return com.dfdx047.odinhub.models.HomeGestureConfig(
                enabled = prefs.getBoolean("home_gestures_enabled", d.enabled),
                singleTap = action("home_single_action", d.singleTap),
                doubleTap = action("home_double_action", d.doubleTap),
                tripleTap = action("home_triple_action", d.tripleTap),
                longPress = action("home_long_action", d.longPress),
                longPressMs = prefs.getLong("home_long_press_ms", d.longPressMs),
            )
        }
        set(value) {
            prefs.edit()
                .putBoolean("home_gestures_enabled", value.enabled)
                .putString("home_single_action", value.singleTap.id)
                .putString("home_double_action", value.doubleTap.id)
                .putString("home_triple_action", value.tripleTap.id)
                .putString("home_long_action", value.longPress.id)
                .putLong("home_long_press_ms", value.longPressMs)
                .apply()
        }

    // ---- Macros dos botões traseiros (ver ButtonMacros). `button` = "m1" | "m2". ----
    fun macroEnabled(button: String): Boolean = prefs.getBoolean("macro_${button}_enabled", false)
    fun macroSteps(button: String): String? = prefs.getString("macro_${button}_steps", null)
    fun saveMacro(button: String, enabled: Boolean, encodedSteps: String) {
        prefs.edit().putBoolean("macro_${button}_enabled", enabled).putString("macro_${button}_steps", encodedSteps).apply()
    }
    /** Mapeamento nativo que estava no botão antes de a macro o substituir pela tecla-gatilho. */
    fun macroNativeBackup(button: String): Int = prefs.getInt("macro_${button}_native_backup", -1)
    fun setMacroNativeBackup(button: String, keyCode: Int) { prefs.edit().putInt("macro_${button}_native_backup", keyCode).commit() }

    var activeLimitMode: String get() = prefs.getString("active_limit_mode", "TDP") ?: "TDP"; set(value) = prefs.edit().putString("active_limit_mode", value).apply()
    /** Slider de TDP. Por omissão o "Triple A" do aparelho atual (15 W no Odin 3, 11 W num Odin 2...). */
    var tdpValue: Float get() = prefs.getFloat("tdp_value", TdpProfiles.envelope.tripleA); set(value) = prefs.edit().putFloat("tdp_value", value).apply()

    /**
     * Perfil de TDP selecionado ("power_save" | "balanced" | "triple_a" | "stock" | "custom" --
     * ver TdpProfiles). Independente do slider: `tdpValue` é o valor do slider, e só é o que se
     * aplica quando o perfil é "custom". Instalações antigas (sem a chave) resolvem-se a partir
     * dos watts guardados, para que 15 W continue a aparecer como "Triple A".
     */
    var tdpProfileId: String
        get() = prefs.getString("tdp_profile_id", null) ?: TdpProfiles.idForWatts(tdpValue)
        set(value) = prefs.edit().putString("tdp_profile_id", value).apply()

    // Limite térmico (via PServerBinder) -- ver ThermalManager. `thermalLimitMode` é o id da opção escolhida
    // ("stock" | "85" | "90" | "95" | "unthrottled"); `thermalTripBackup` guarda, em JSON, as
    // temperaturas originais dos trip points ANTES da primeira modificação, para o "Stock"
    // conseguir repô-las mesmo depois de um reboot da app.
    var thermalLimitMode: String get() = prefs.getString("thermal_limit_mode", "stock") ?: "stock"; set(value) = prefs.edit().putString("thermal_limit_mode", value).apply()
    // Os backups usam commit() (síncrono): têm de estar em disco ANTES da primeira escrita em
    // sysfs. Com apply() um processo morto nessa janela perdia o backup com os trips já mexidos.
    var thermalTripBackup: String? get() = prefs.getString("thermal_trip_backup", null); set(value) { prefs.edit().putString("thermal_trip_backup", value).commit() }
    /** Valores originais do nó `mode` de cada zona (JSON caminho->valor), guardados antes de os desativar. */
    var thermalModeBackup: String? get() = prefs.getString("thermal_mode_backup", null); set(value) { prefs.edit().putString("thermal_mode_backup", value).commit() }
    /** `boot_id` do kernel no momento em que os backups térmicos foram tirados (ver ThermalManager). */
    var thermalBackupBootId: String? get() = prefs.getString("thermal_backup_boot_id", null); set(value) { prefs.edit().putString("thermal_backup_boot_id", value).commit() }
    /** Regra térmica por jogo ativa quando o processo morreu (para a desfazer no arranque seguinte). */
    var thermalOverrideActive: Boolean get() = prefs.getBoolean("thermal_override_active", false); set(value) = prefs.edit().putBoolean("thermal_override_active", value).apply()
    /** Valor de `fan_mode` do sistema antes de a app o mudar (-1 = nenhum guardado). */
    var fanModeBackup: Int get() = prefs.getInt("fan_mode_backup", -1); set(value) { prefs.edit().putInt("fan_mode_backup", value).commit() }
    var thermalDisableZoneMode: Boolean get() = prefs.getBoolean("thermal_disable_zone_mode", false); set(value) = prefs.edit().putBoolean("thermal_disable_zone_mode", value).apply()
    // Clocks manuais: por omissão o máximo do aparelho atual (antes eram os 3530/4320/1100 MHz
    // do 8 Elite, que noutro SoC só "funcionavam" por serem cortados ao máximo).
    var cpuPerfClock: Float get() = prefs.getFloat("cpu_perf_clock", defaultClocks.perfMHz.lastOrNull()?.toFloat() ?: 3530f); set(value) = prefs.edit().putFloat("cpu_perf_clock", value).apply()
    var cpuPrimeClock: Float get() = prefs.getFloat("cpu_prime_clock", defaultClocks.primeMHz.lastOrNull()?.toFloat() ?: 4320f); set(value) = prefs.edit().putFloat("cpu_prime_clock", value).apply()
    var gpuClock: Float get() = prefs.getFloat("gpu_clock", defaultClocks.gpuMHz.lastOrNull()?.toFloat() ?: 1100f); set(value) = prefs.edit().putFloat("gpu_clock", value).apply()
    private val defaultClocks get() = com.dfdx047.odinhub.tools.DeviceRegistry.current.fallbackTables

    var globalLsfgEnabled: Boolean get() = prefs.getBoolean("global_lsfg_enabled", false); set(value) = prefs.edit().putBoolean("global_lsfg_enabled", value).apply()
    var lsfgMultiplier: String get() = prefs.getString("lsfg_multiplier", "2x") ?: "2x"; set(value) = prefs.edit().putString("lsfg_multiplier", value).apply()
    var lsfgFramePacing: Boolean get() = prefs.getBoolean("lsfg_pacing", true); set(value) = prefs.edit().putBoolean("lsfg_pacing", value).apply()
    var lsfgPerformanceMode: Boolean get() = prefs.getBoolean("lsfg_performance_mode", false); set(value) = prefs.edit().putBoolean("lsfg_performance_mode", value).apply()

    var globalSgsrEnabled: Boolean get() = prefs.getBoolean("global_sgsr_enabled", false); set(value) = prefs.edit().putBoolean("global_sgsr_enabled", value).apply()
    var sgsrMode: String get() = prefs.getString("sgsr_mode", "Quality") ?: "Quality"; set(value) = prefs.edit().putString("sgsr_mode", value).apply()
    var sgsrSharpness: Float get() = prefs.getFloat("sgsr_sharpness", 0.5f); set(value) = prefs.edit().putFloat("sgsr_sharpness", value).apply()
    var reshadeProfile: String get() = prefs.getString("reshade_profile", "Native") ?: "Native"; set(value) = prefs.edit().putString("reshade_profile", value).apply()
    var showFpsOverlay: Boolean get() = prefs.getBoolean("fps_overlay", false); set(value) = prefs.edit().putBoolean("fps_overlay", value).apply()
    // `current_foreground_app` foi removido de propósito: guardar em disco qual o app em primeiro
    // plano era a causa raiz do overlay gravar as regras do jogo sob o pacote errado (ver
    // ForegroundAppTracker). Esse estado é do momento e vive agora só em memória, num StateFlow.

    // As antigas chaves soltas "override_<pkg>_*" (TDP/Clock/Fan/SGSR/LSFG/ReShade por jogo)
    // foram removidas: eram um armazenamento paralelo ao Room (AppOverrideEntity), lido só
    // pelo overlay e nunca pela aba Performance -> Per-App Overrides, o que fazia os dois
    // ecrãs nunca concordarem entre si. Ver AppOverrideRepository -- agora fonte única.

    fun saveCustomProfile(name: String, type: String, val1: Float, val2: Float, val3: Float, val4: Float) {
        prefs.edit().putString("custom_profile_${name}_type", type).putFloat("custom_profile_${name}_v1", val1).putFloat("custom_profile_${name}_v2", val2).putFloat("custom_profile_${name}_v3", val3).putFloat("custom_profile_${name}_v4", val4).apply()
        _customProfilesFlow.value = getAllCustomProfiles()
    }

    fun getAllCustomProfiles(): List<CustomProfile> {
        val profiles = mutableListOf<CustomProfile>()
        prefs.all.keys.filter { it.startsWith("custom_profile_") && it.endsWith("_type") }.forEach { key ->
            // removePrefix/removeSuffix: o replace() antigo também apagava "_type" do MEIO do
            // nome ("meu_type_x" virava "meu_x" e os valores eram lidos de chaves inexistentes).
            val name = key.removePrefix("custom_profile_").removeSuffix("_type")
            profiles.add(CustomProfile(name, prefs.getString(key, "TDP") ?: "TDP", prefs.getFloat("custom_profile_${name}_v1", 0f), prefs.getFloat("custom_profile_${name}_v2", 0f), prefs.getFloat("custom_profile_${name}_v3", 0f), prefs.getFloat("custom_profile_${name}_v4", 0f)))
        }
        return profiles.sortedBy { it.name.lowercase() }
    }

    private val _customProfilesFlow = kotlinx.coroutines.flow.MutableStateFlow(getAllCustomProfiles())

    /** Presets do utilizador, reativos: um preset gravado no overlay aparece logo no Settings. */
    val customProfilesFlow: kotlinx.coroutines.flow.StateFlow<List<CustomProfile>> get() = _customProfilesFlow

    // ---- Fundo da app (wallpaper). Antes vivia só em rememberSaveable e voltava ao padrão a
    // cada arranque. `wallpaperStatic` guarda 1/2 (não o id do recurso, que muda entre builds).
    var wallpaperType: String get() = prefs.getString("wallpaper_type", "Static") ?: "Static"; set(value) = prefs.edit().putString("wallpaper_type", value).apply()
    var wallpaperBlur: Boolean get() = prefs.getBoolean("wallpaper_blur", true); set(value) = prefs.edit().putBoolean("wallpaper_blur", value).apply()
    var wallpaperBlurIntensity: Float get() = prefs.getFloat("wallpaper_blur_intensity", 0.4f); set(value) = prefs.edit().putFloat("wallpaper_blur_intensity", value).apply()
    var wallpaperOpacity: Float get() = prefs.getFloat("wallpaper_opacity", 0.85f); set(value) = prefs.edit().putFloat("wallpaper_opacity", value).apply()
    var wallpaperStatic: Int get() = prefs.getInt("wallpaper_static", 1); set(value) = prefs.edit().putInt("wallpaper_static", value).apply()
    var wallpaperCustomUri: String? get() = prefs.getString("wallpaper_custom_uri", null); set(value) = prefs.edit().putString("wallpaper_custom_uri", value).apply()
    var wallpaperName: String? get() = prefs.getString("wallpaper_name", null); set(value) = prefs.edit().putString("wallpaper_name", value).apply()

    /** Último aparelho anunciado como "detetado" (para o aviso aparecer uma vez por aparelho). */
    var lastDetectedDeviceId: String? get() = prefs.getString("last_detected_device", null); set(value) = prefs.edit().putString("last_detected_device", value).apply()
}
