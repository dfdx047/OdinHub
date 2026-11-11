package com.dfdx047.odinhub.tools

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Comandos de root com saída de VÁRIAS linhas.
 *
 * O PServer só devolve a PRIMEIRA linha da saída (no RP5: `seq 1 5` -> `1`, e
 * `dumpsys SurfaceFlinger --list` -> só `Display 0 ...`). Aqui o comando corre dentro de um script
 * (`sh <script> <comando...>`, um comando simples para o PServer) que junta as linhas com o
 * separador [SEP] (0x1E, "record separator") numa linha só. Depois separa-se de novo.
 */
@Singleton
class RootLines @Inject constructor(
    @ApplicationContext private val context: Context,
    private val executor: ShellExecutor,
) {
    @Volatile private var script: String? = null
    @Volatile private var broken = false

    /** Saída completa de [cmd] (linhas separadas por '\n'), ou null se não houver captura. */
    fun run(cmd: String): String? {
        if (!executor.outputCaptureWorks || broken) return null
        val path = script ?: install() ?: return null
        return executor.executeAsRoot("sh $path $cmd").getOrNull()?.let(::split)
    }

    private fun install(): String? {
        // Escolhe o primeiro caminho do script que o PServer consegue correr (filesDir ou externo).
        for (path in RootScripts.write(context, NAME, SCRIPT)) {
            if (executor.executeAsRoot("sh $path echo ok").getOrNull()?.let(::split)?.trim() == "ok") {
                script = path
                return path
            }
        }
        broken = true
        return null
    }

    companion object {
        const val SEP = '\u001e'
        private const val NAME = "root_lines.sh"

        /** `tr` do toybox aceita octal: \036 = 0x1E. O `echo` final só fecha a linha. */
        val SCRIPT = """
            |#!/system/bin/sh
            |# Retroid Hub: corre o comando e junta as linhas da saida numa so (o PServer so devolve a 1.a).
            |"${'$'}@" 2>/dev/null | tr '\n' '\036'
            |echo
            |""".trimMargin()

        /** Inverso do script: volta a pôr as quebras de linha. */
        fun split(raw: String): String = raw.replace(SEP, '\n').trimEnd('\n')
    }
}
