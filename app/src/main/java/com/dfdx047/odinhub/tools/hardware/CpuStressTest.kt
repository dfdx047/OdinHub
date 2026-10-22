package com.dfdx047.odinhub.tools.hardware

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

/**
 * "Teste de stress da CPU" do ecrã da curva da ventoinha: um thread a 100 % por núcleo, durante
 * [DURATION_MS] ou até ser parado -- para ver a curva a reagir ao aquecimento.
 */
object CpuStressTest {
    const val DURATION_MS = 60_000L

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    @Volatile private var stopAt = 0L
    private val threads = mutableListOf<Thread>()

    @Synchronized
    fun toggle() = if (_running.value) stop() else start()

    @Synchronized
    fun start() {
        if (_running.value) return
        stopAt = System.currentTimeMillis() + DURATION_MS
        _running.value = true
        repeat(Runtime.getRuntime().availableProcessors()) { i ->
            threads += Thread({
                var x = 1.0001
                while (_running.value && System.currentTimeMillis() < stopAt) {
                    for (k in 0 until 200_000) x = sqrt(x * 1.0000001 + k)
                }
                if (x == 0.0) println(x) // impede o compilador de eliminar o laço
                finishIfDone()
            }, "odinhub-stress-$i").apply { isDaemon = true; start() }
        }
    }

    @Synchronized
    fun stop() {
        _running.value = false
        threads.clear()
    }

    @Synchronized
    private fun finishIfDone() {
        if (System.currentTimeMillis() >= stopAt) {
            _running.value = false
            threads.clear()
        }
    }
}
