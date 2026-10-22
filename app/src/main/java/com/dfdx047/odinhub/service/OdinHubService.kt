package com.dfdx047.odinhub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import com.dfdx047.odinhub.R
import com.dfdx047.odinhub.tools.hardware.PerformanceManager
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class OdinHubService : Service() {

    @Inject lateinit var performanceManager: PerformanceManager
    @Inject lateinit var prefs: com.dfdx047.odinhub.data.SharedPrefsRepo
    @Inject lateinit var updateManager: com.dfdx047.odinhub.tools.UpdateManager
    @Inject lateinit var fanCurve: com.dfdx047.odinhub.tools.hardware.FanCurveController
    private var updateJob: Job? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var lastForeground: String? = null

    override fun onCreate() {
        super.onCreate()
        runCatching {
            createNotificationChannel()
            val notification = buildNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(32, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(32, notification)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (pollJob?.isActive != true) {
            pollJob = serviceScope.launch { pollLoop() }
        }
        // Curva personalizada da ventoinha: corre aqui, num serviço em primeiro plano, para não
        // parar quando a app é fechada.
        fanCurve.start()
        // Procura versões novas em segundo plano (com notificação), mesmo sem abrir a app.
        if (updateJob?.isActive != true) {
            updateJob = serviceScope.launch {
                delay(60_000) // deixa o arranque assentar
                while (isActive) {
                    runCatching { updateManager.autoCheck() }
                    delay(BACKGROUND_UPDATE_INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    private suspend fun pollLoop() {
        while (serviceScope.isActive) {
            val foreground = currentForegroundPackage()
            if (foreground != null && foreground != lastForeground) {
                lastForeground = foreground
            }
            delay(1000)
        }
    }

    private fun currentForegroundPackage(): String? {
        return runCatching {
            val usageStats = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
            val now = System.currentTimeMillis()
            val events = usageStats.queryEvents(now - 10000, now)
            var latest: String? = null
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    latest = event.packageName
                }
            }
            latest
        }.getOrNull()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val BACKGROUND_UPDATE_INTERVAL_MS = 3 * 60 * 60 * 1000L
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel("odinhub_channel", "Odin Hub Monitor", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, "odinhub_channel")
        .setSmallIcon(R.drawable.ic_app_settings)
        .setContentTitle("Odin Hub: Performance")
        .setContentText(if (prefs.isEnglish) "Engine running and monitoring..." else "Motor ligado e a monitorizar...")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
}