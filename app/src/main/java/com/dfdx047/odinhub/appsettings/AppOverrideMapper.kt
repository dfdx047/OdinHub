package com.dfdx047.odinhub.appsettings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import com.dfdx047.odinhub.data.AppOverrideEntity
import com.dfdx047.odinhub.models.FanMode
import com.dfdx047.odinhub.models.TdpProfiles
import com.dfdx047.odinhub.tools.hardware.ThermalLimitModes
import javax.inject.Inject

class AppOverrideMapper @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun mapOverrideCandidates(existingOverrides: List<AppOverrideEntity>): List<AppUiModel> {
        return context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA).filter {
            it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.enabled
        }.filterNot { appInfo ->
            existingOverrides.any { appInfo.packageName == it.packageName }
        }.map {
            val icon = context.packageManager.getApplicationIcon(it.packageName)
            val label = context.packageManager.getApplicationLabel(it).toString()
            AppUiModel(it.packageName, label, icon)
        }.sortedBy {
            it.appName
        }
    }

    fun mapAppOverrides(overrides: List<AppOverrideEntity>, isEn: Boolean): List<AppUiModel> {
        return overrides.mapNotNull { mapAppOverride(it, isEn) }
    }

    fun mapAppOverride(app: AppOverrideEntity, isEn: Boolean): AppUiModel? {
        val appInfo = runCatching {
            context.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
        }.getOrNull() ?: return null

        return AppUiModel(
            packageName = app.packageName,
            appName = context.packageManager.getApplicationLabel(appInfo).toString(),
            appIcon = context.packageManager.getApplicationIcon(appInfo),
            subtitle = subtitleFor(app, isEn),
            tdpProfile = app.tdpProfile,
            clockProfile = app.clockProfile,
            fanProfile = app.fanProfile,
        )
    }

    /**
     * Resumo da regra, calculado na hora a partir dos campos numéricos e no idioma da app. Antes
     * era montado dos rótulos gravados em inglês no Room ("TDP: … | Fan: …") e ignorava o limite
     * térmico, a cor e as macros -- uma regra só térmica aparecia como "Stock".
     */
    private fun subtitleFor(app: AppOverrideEntity, isEn: Boolean): String {
        val parts = mutableListOf<String>()
        if (app.limitMode == "TDP") {
            app.tdpWatts?.let { w ->
                parts += "TDP " + if (TdpProfiles.isStockWatts(w)) "Stock" else TdpProfiles.formatWatts(w)
            }
        } else {
            val perf = app.perfClockKHz?.div(1000)
            val prime = app.primeClockKHz?.div(1000)
            if (perf != null || prime != null) parts += "Clocks ${perf ?: "-"}/${prime ?: "-"} MHz"
        }
        FanMode.selectable.firstOrNull { it.settingsValue == app.fanSettingsValue }?.let {
            parts += (if (isEn) "Fan " else "Ventoinha ") + it.shortLabel
        }
        app.thermalMode?.let { mode ->
            val label = when (mode) {
                ThermalLimitModes.STOCK -> "Stock"
                ThermalLimitModes.UNTHROTTLED -> if (isEn) "off" else "sem limite"
                else -> "$mode °C"
            }
            parts += (if (isEn) "Thermal " else "Térmico ") + label
        }
        if (app.displayOverride) parts += if (isEn) "Color" else "Cor"
        val macros = listOfNotNull(app.m1Macro?.let { "M1" }, app.m2Macro?.let { "M2" })
        if (macros.isNotEmpty()) parts += macros.joinToString("/")
        return parts.joinToString(" | ").ifEmpty { if (isEn) "Follows global" else "Segue o global" }
    }
}
