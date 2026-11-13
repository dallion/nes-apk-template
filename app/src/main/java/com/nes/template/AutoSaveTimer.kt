package com.nes.template

/** Accumulates foreground gameplay time; menus and background time do not count. */
class AutoSaveTimer {
    private var previous: Long? = null
    private var elapsed = 0L

    fun onFrame(nowMillis: Long): Boolean {
        previous?.let { elapsed += (nowMillis - it).coerceAtLeast(0) }
        previous = nowMillis
        if(elapsed < 30_000L) return false
        elapsed = 0L
        return true
    }

    fun pause() { previous = null }
}
