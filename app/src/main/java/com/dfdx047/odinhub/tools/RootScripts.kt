package com.dfdx047.odinhub.tools

import android.content.Context
import java.io.File

/**
 * Scripts de shell para o PServer. Regra do projeto: o PServer só executa de forma fiável UM
 * comando simples por chamada -- nada de `a; b`, `a && b` ou `( … ) &`. Qualquer lógica composta
 * vai para um ficheiro e corre com `sh <ficheiro>` (uma única chamada simples).
 *
 * O script é gravado na pasta privada da app e, como recurso, na pasta externa da app (o domínio
 * SELinux do PServer pode não conseguir ler a privada). Devolve os caminhos a tentar, por ordem.
 */
object RootScripts {
    private val SAFE_PATH = Regex("/[A-Za-z0-9_./-]+\\.sh")

    fun write(context: Context, name: String, content: String): List<String> {
        require(name.matches(Regex("[A-Za-z0-9_.-]+\\.sh"))) { "nome de script inseguro: $name" }
        val paths = mutableListOf<String>()
        runCatching {
            val file = File(context.filesDir, name)
            file.writeText(content)
            file.setReadable(true, false)
            context.filesDir.setExecutable(true, false)
            paths += file.absolutePath
        }
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return@runCatching
            val file = File(dir, name)
            file.writeText(content)
            paths += file.absolutePath
            paths += file.absolutePath.replace("/storage/emulated/0/", "/data/media/0/")
        }
        return paths.distinct().filter { it.matches(SAFE_PATH) }
    }
}
