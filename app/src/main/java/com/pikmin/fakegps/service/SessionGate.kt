package com.pikmin.fakegps.service

/** Invalidates queued starts after a stop. Main-thread confined. */
class SessionGate {
    private var generation = 0L
    private var enabled = false
    val currentToken: Long? get() = if (enabled) generation else null
    fun begin(): Long { enabled = true; return ++generation }
    fun stop() { enabled = false; generation++ }
    fun accepts(token: Long): Boolean = enabled && token == generation
}
