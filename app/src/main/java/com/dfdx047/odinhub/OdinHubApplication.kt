package com.dfdx047.odinhub

import android.app.Application
import com.dfdx047.odinhub.models.TdpProfiles
import com.dfdx047.odinhub.tools.DeviceRegistry
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class OdinHubApplication : Application() {
    override fun onCreate() {
        // Antes de qualquer ecrã/serviço: os perfis de TDP e o teto do slider seguem o aparelho
        // detetado (Odin 3, Odin 2, Retroid Pocket 5...). A deteção não faz I/O root.
        TdpProfiles.envelope = DeviceRegistry.current.power
        super.onCreate()
    }
}
