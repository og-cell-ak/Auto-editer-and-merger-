package com.futurethinking.aivideodirector.media

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object MediaExportGate {
    private val mutex = Mutex()
    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
