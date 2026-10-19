package com.example.beast_academy_apk

/** Arbitrates renderer cleanup callbacks against timeout, resume, replacement, and shutdown. */
internal class PauseCompletion {
    private var generation = 0L
    private var pending: Long? = null

    fun begin(): Long = (++generation).also { pending = it }
    fun invalidate() { generation++; pending = null }
    fun complete(ticket: Long): Boolean {
        if (pending != ticket) return false
        pending = null
        return true
    }
}
