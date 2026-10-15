package com.dfdx047.odinhub.appsettings

import android.graphics.drawable.Drawable

data class AppOverrideListUiModel(
    val showAppSelectDialog: Boolean = false,
    val overrideList: List<AppUiModel> = emptyList(),
    val overrideCandidates: List<AppUiModel> = emptyList(),
    // Tema ativo da app (ver ui/theme/Theme.kt). Antes este ecrã tinha cores fixas
    // (0xFF0F1115 / 0xFF1976D2) e era o único sítio da app que ignorava por completo o motor de
    // temas -- por isso parecia um ecrã de outra aplicação.
    val selectedThemeIndex: Int = 0,
    val useAmoledBlack: Boolean = false,
)

data class AppUiModel(
    val packageName: String,
    val appName: String,
    val appIcon: Drawable,
    val subtitle: String? = null,
    val tdpProfile: String? = null,
    val clockProfile: String? = null,
    val fanProfile: String? = null,
)