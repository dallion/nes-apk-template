package com.nes.template

class FpsCounter {
    private var startedAt: Long? = null
    private var frames = 0

    fun onFrame(nowNanos: Long): Double? {
        val start = startedAt
        if(start == null) {
            startedAt = nowNanos
            return null
        }
        frames++
        val elapsed = nowNanos-start
        if(elapsed < 500_000_000L) return null
        val fps = frames*1_000_000_000.0/elapsed
        startedAt = nowNanos
        frames = 0
        return fps
    }

    fun reset() {
        startedAt = null
        frames = 0
    }
}
