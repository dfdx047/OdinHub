package com.dfdx047.odinhub.models

/** Comparação de versões das releases ("v0.10" vs "0.9"), sem dependências do Android. */
object Versions {
    /** `true` se [remoteTag] for mais nova do que [installed]. Compara número a número. */
    fun isNewer(remoteTag: String, installed: String): Boolean {
        fun parts(v: String) = Regex("\\d+").findAll(v.substringBefore('-')).map { it.value.toInt() }.toList()
        val r = parts(remoteTag)
        val l = parts(installed)
        if (r.isEmpty()) return false
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
