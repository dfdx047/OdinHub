package com.dfdx047.odinhub.appsettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.dfdx047.odinhub.data.AppOverrideDao
import com.dfdx047.odinhub.data.SharedPrefsRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppOverrideListViewModel @Inject constructor(
    private val appOverrideDao: AppOverrideDao,
    private val appOverrideMapper: AppOverrideMapper,
    private val prefs: SharedPrefsRepo,
) : ViewModel() {
    /** Idioma da UI partilhado com o resto da app (SharedPrefsRepo.isEnglish). */
    val isEnglish: kotlinx.coroutines.flow.StateFlow<Boolean> = prefs.isEnglishFlow


    private val _uiState = MutableStateFlow(AppOverrideListUiModel())
    val uiState: StateFlow<AppOverrideListUiModel> = _uiState.asStateFlow()

    private var existingOverrides = emptyList<String>()

    init {
        // O tema escolhido pelo utilizador, para este ecrã deixar de ser o único com cores fixas.
        // Lido no arranque do ViewModel (que é recriado a cada navegação para este ecrã), por isso
        // uma mudança de tema feita em Settings já aparece aqui da próxima vez que se entra.
        _uiState.update {
            it.copy(selectedThemeIndex = prefs.selectedThemeIndex, useAmoledBlack = prefs.useAmoledBlack, useGlass = prefs.useGlass)
        }

        // Todo o trabalho pesado (PackageManager: lista de apps, ícones, nomes) DENTRO do flow, antes
        // do flowOn(IO). Antes o `flowOn` só movia a query do Room e o mapeamento corria no
        // `collect`, na thread principal -- a cada emissão do Room (incluindo cada escrita do
        // overlay). O `getprop` root da versão do firmware, que ninguém mostrava, saiu.
        viewModelScope.launch {
            combine(appOverrideDao.getAll(), prefs.isEnglishFlow) { overrides, isEn -> overrides to isEn }
                .map { (overrides, isEn) ->
                    Triple(
                        overrides.map { it.packageName },
                        appOverrideMapper.mapAppOverrides(overrides, isEn),
                        appOverrideMapper.mapOverrideCandidates(overrides),
                    )
                }
                .flowOn(Dispatchers.IO)
                .collect { (packages, list, candidates) ->
                    existingOverrides = packages
                    _uiState.update { it.copy(overrideList = list, overrideCandidates = candidates) }
                }
        }
    }

    fun addClicked() {
        _uiState.update {
            it.copy(showAppSelectDialog = true)
        }
    }

    fun dismissAppSelectDialog() {
        _uiState.update {
            it.copy(showAppSelectDialog = false)
        }
    }

    /**
     * System settings:
     * performance_mode: standard 0, performance 1, high performance 2
     * fan_mode: disabled 0, quiet 1, (balance 2), (performance 3), smart 4, sport 5, custom 6
     */
}