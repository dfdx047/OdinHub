package com.dfdx047.odinhub.models

import com.dfdx047.odinhub.BuildConfig

/**
 * Identidade do projeto num único sítio.
 *
 * Tudo o que é "quem fez isto e onde vive" passa a sair daqui, em vez de andar espalhado por
 * strings soltas na UI (a versão, por exemplo, estava escrita à mão como "Versão 0.5" num cartão
 * — e por isso ficava desatualizada em relação ao `versionName` real a cada release).
 */
object AppInfo {

    const val NAME = "Odin Hub"

    const val DEVELOPER = "dfdx047"

    /** Sai do `versionName` do Gradle, por isso nunca diverge do que foi realmente compilado. */
    val version: String = BuildConfig.VERSION_NAME

    val versionCode: Int = BuildConfig.VERSION_CODE

    /**
     * ATENÇÃO: confirma este endereço se o repositório for renomeado no GitHub. É o único sítio
     * onde o link existe — a UI e o botão de atualizações leem daqui.
     */
    const val GITHUB_URL = "https://github.com/dfdx047/OdinHub"

    /** Lista de releases: `/releases/latest` dá 404 enquanto nenhuma release estiver PUBLICADA (rascunhos não contam). */
    const val RELEASES_URL = "$GITHUB_URL/releases"

    /** Descrição curta mostrada no cartão "Sobre". */
    const val TAGLINE_PT =
        "Central de controlo de hardware e desempenho para os portáteis AYN Odin e Retroid Pocket. " +
            "Ajusta TDP, clocks de CPU/GPU, ventoinha e calibração de cor — " +
            "com perfis próprios por jogo, aplicados automaticamente."

    const val TAGLINE_EN =
        "Hardware and performance control center for AYN Odin and Retroid Pocket handhelds. " +
            "Tune TDP, CPU/GPU clocks, fan behaviour and display calibration — " +
            "with per-game profiles applied automatically."
}
