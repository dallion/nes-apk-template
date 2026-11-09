package com.nes.template

import android.view.KeyEvent

class SpeedHotkeys(private val getSpeed: () -> Int, private val setSpeed: (Int) -> Unit) {
    private val held = mutableSetOf<Pair<Int,Int>>()

    fun key(deviceId: Int, keyCode: Int, action: Int, repeatCount: Int): Boolean {
        val delta = when(keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> -1
            KeyEvent.KEYCODE_BUTTON_R1 -> 1
            else -> return false
        }
        val key = deviceId to keyCode
        when(action) {
            KeyEvent.ACTION_DOWN -> if(repeatCount == 0 && held.add(key)) {
                val current = getSpeed()
                val next = (current+delta).coerceIn(1,4)
                if(next != current) setSpeed(next)
            }
            KeyEvent.ACTION_UP -> held.remove(key)
            else -> return false
        }
        return true
    }

    fun clear() { held.clear() }
}
