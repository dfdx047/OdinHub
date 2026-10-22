package com.dfdx047.odinhub.tools.hardware

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Leitura da bateria pela API do Android (BatteryManager + o broadcast "sticky"
 * ACTION_BATTERY_CHANGED), que não depende de permissões nem do SELinux.
 *
 * BUG (AYN Thor): o firmware não deixa as apps lerem `/sys/class/power_supply/battery/...`.
 * O HUD mostrava "BAT --%" e "CONS --W" e, pior, o AutoTDP ficava sem medição de consumo (sem
 * amostras de watts não consegue convergir). Os nós de sysfs continuam a ser a primeira opção
 * (são mais finos); isto é o recurso quando não se leem.
 */
object BatteryReader {

    data class Reading(
        val percent: Int?,
        val watts: Float?,
        val tempC: Float?,
        /** A carregar ou cheia/ligada à corrente: a bateria não mede o consumo do aparelho. */
        val externallyPowered: Boolean?,
    )

    fun read(context: Context): Reading {
        val bm = context.getSystemService(BatteryManager::class.java)
        val sticky = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()

        val percent = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
            ?: sticky?.let { i ->
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) level * 100 / scale else null
            }

        // CURRENT_NOW em µA (Long.MIN_VALUE / 0 = não suportado); tensão do broadcast em mV.
        val currentUa = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.takeIf { it != Long.MIN_VALUE && it != 0L && it != Int.MIN_VALUE.toLong() }
        val voltageMv = sticky?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }
        val watts = if (currentUa != null && voltageMv != null) {
            AutoTdpController.batteryWatts(currentUa, voltageMv * 1000L).takeIf { it > 0f }
        } else null

        val temp = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }

        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val external = when {
            sticky == null -> null
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL -> true
            plugged > 0 -> true // ligado mas "not charging" (bypass / separação de carga)
            else -> false
        }
        return Reading(percent, watts, temp, external)
    }
}
