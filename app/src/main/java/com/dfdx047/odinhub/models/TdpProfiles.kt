package com.dfdx047.odinhub.models

/**
 * Perfil fixo de TDP.
 *
 * `watts == null` significa "Stock": sem limite nenhum -- clocks a 100% e o loop de AutoTDP
 * desligado (ver `PerformanceManager.applyStockPerformance`).
 */
data class TdpProfile(
    val id: String,
    val labelPt: String,
    val labelEn: String,
    val watts: Float?,
    val descriptionPt: String,
    val descriptionEn: String,
) {
    fun label(isEn: Boolean): String = if (isEn) labelEn else labelPt
    fun description(isEn: Boolean): String = if (isEn) descriptionEn else descriptionPt
}

/**
 * ÚNICA fonte de verdade dos perfis de TDP. É usada pelo ecrã de Settings, pelo overlay em jogo e
 * pelo editor de regras por app, para que os três concordem sempre nos mesmos watts.
 *
 * Os watts dependem do APARELHO (ver [DeviceProfiles] / [PowerEnvelope]): no Odin 3 (8 Elite)
 * são Power Save 7 W, Balanced 10 W, Triple A 15 W, slider até 25 W; num Odin 2 (8 Gen 2) ou num
 * Retroid Pocket 5 (865) o mesmo "Balanced" é muito menos (7 W / 4.5 W).
 *
 * Os perfis são INDEPENDENTES do slider: escolher um perfil aplica exatamente os watts dele e
 * marca-o como selecionado; arrastar o slider aplica o valor do slider e passa a seleção para
 * [ID_CUSTOM] (nenhum chip fica destacado). O id selecionado é persistido em
 * `SharedPrefsRepo.tdpProfileId` -- por isso uma regra "Balanced" segue o aparelho onde corre.
 */
object TdpProfiles {

    const val ID_POWER_SAVE = "power_save"
    const val ID_BALANCED = "balanced"
    const val ID_TRIPLE_A = "triple_a"
    const val ID_STOCK = "stock"
    const val ID_CUSTOM = "custom"

    /**
     * Envelope de potência do aparelho atual. Em produção vem de `DeviceRegistry` (definido no
     * arranque da app, ver OdinHubApplication); nos testes JVM pode ser trocado à vontade. Até lá
     * vale o do Odin 3, o comportamento histórico da app.
     */
    @Volatile
    var envelope: PowerEnvelope = PowerEnvelope(7f, 10f, 15f, 25f)

    /** Limites do slider de TDP, em watts, em todos os ecrãs. */
    const val TDP_MIN_WATTS = 1f
    val TDP_MAX_WATTS: Float get() = envelope.maxWatts

    /**
     * Sentinela usada para guardar "Stock (sem limite)" nos campos `Float` que não podem ser
     * nulos sem ambiguidade (ex: `AppOverrideEntity.tdpWatts`, onde `null` já significa "usa o
     * valor global"). Qualquer valor abaixo de [TDP_MIN_WATTS] é lido como Stock.
     */
    const val STOCK_SENTINEL_WATTS = 0f

    val POWER_SAVE: TdpProfile
        get() = TdpProfile(
            id = ID_POWER_SAVE,
            labelPt = "Power Save",
            labelEn = "Power Save",
            watts = envelope.powerSave,
            descriptionPt = "Mínimo para a maioria dos jogos/emuladores",
            descriptionEn = "Minimum for most games/emulators",
        )

    val BALANCED: TdpProfile
        get() = TdpProfile(
            id = ID_BALANCED,
            labelPt = "Balanced",
            labelEn = "Balanced",
            watts = envelope.balanced,
            descriptionPt = "Equilíbrio entre autonomia e desempenho",
            descriptionEn = "Balance between battery life and performance",
        )

    val TRIPLE_A: TdpProfile
        get() = TdpProfile(
            id = ID_TRIPLE_A,
            labelPt = "Triple A",
            labelEn = "Triple A",
            watts = envelope.tripleA,
            descriptionPt = "Para jogos pesados; mais calor e menos autonomia",
            descriptionEn = "For demanding games; more heat, less battery",
        )

    val STOCK: TdpProfile = TdpProfile(
        id = ID_STOCK,
        labelPt = "Stock",
        labelEn = "Stock",
        watts = null,
        descriptionPt = "Sem limite: clocks a 100% e AutoTDP desligado",
        descriptionEn = "No limit: 100% clocks, AutoTDP loop off",
    )

    /** Os perfis fixos, na ordem em que aparecem nos chips. */
    val fixed: List<TdpProfile> get() = listOf(POWER_SAVE, BALANCED, TRIPLE_A, STOCK)

    fun byId(id: String?): TdpProfile? = fixed.firstOrNull { it.id == id }

    /** Id do perfil fixo cujos watts coincidem exatamente, ou [ID_CUSTOM]. */
    fun idForWatts(watts: Float?): String {
        if (watts == null || isStockWatts(watts)) return ID_STOCK
        return fixed.firstOrNull { it.watts != null && it.watts == watts }?.id ?: ID_CUSTOM
    }

    /** `true` quando o valor guardado é a sentinela de "sem limite" -- ver [STOCK_SENTINEL_WATTS]. */
    fun isStockWatts(watts: Float): Boolean = watts < TDP_MIN_WATTS

    /** Encaixa watts no intervalo do slider do aparelho atual. */
    fun clampWatts(watts: Float): Float = watts.coerceIn(TDP_MIN_WATTS, TDP_MAX_WATTS)

    /**
     * Resolve o que deve ser aplicado ao hardware a partir do id persistido e do valor do slider:
     * `null` = Stock (sem limite); caso contrário os watts exatos (perfil fixo, ou o slider para
     * [ID_CUSTOM] e para ids desconhecidos).
     */
    fun resolveWatts(profileId: String?, sliderWatts: Float): Float? {
        if (profileId == ID_STOCK) return null
        val fixedProfile = byId(profileId)
        if (fixedProfile != null) return fixedProfile.watts
        return clampWatts(sliderWatts)
    }

    /** Codifica um valor resolvido ([resolveWatts]) num `Float` para campos não-nulos (Room). */
    fun encodeWatts(watts: Float?): Float = watts ?: STOCK_SENTINEL_WATTS

    /** "12.5 W" quando há casa decimal, "15 W" quando é inteiro. */
    fun formatWatts(watts: Float): String {
        val rounded = kotlin.math.round(watts * 10f) / 10f
        return if (rounded == kotlin.math.floor(rounded)) {
            "${rounded.toInt()} W"
        } else {
            "${"%.1f".format(java.util.Locale.US, rounded)} W"
        }
    }
}
