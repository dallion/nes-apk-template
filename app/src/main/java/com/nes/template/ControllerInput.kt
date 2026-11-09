package com.nes.template

import android.content.SharedPreferences
import androidx.core.content.edit
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

class ControllerInput(private val input: InputRouter, private val preferences: SharedPreferences) {
    val mapping = linkedMapOf(
        KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_B to KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_START to KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_SELECT to KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_DPAD_UP to KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT
    ).apply {
        keys.toList().forEach { physical ->
            if(preferences.contains("map.$physical")) this[physical] = preferences.getInt("map.$physical",physical)
        }
        preferences.all.filterKeys { it.startsWith("map.") }.forEach { (key,value) ->
            key.removePrefix("map.").toIntOrNull()?.let { if(value is Int) this[it] = value }
        }
    }
    fun remap(physical: Int, target: Int) {
        input.releaseAll()
        mapping[physical] = target
        preferences.edit { putInt("map.$physical",target) }
    }
    fun resetMapping() {
        preferences.edit { clear() }
    }
    fun key(event: KeyEvent): Boolean {
        if (!isController(event.source)) return false
        val target = mapping[event.keyCode] ?: return false
        if(event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        input.setButton("pad:${event.deviceId}:key:${event.keyCode}",0,target,event.action == KeyEvent.ACTION_DOWN)
        return true
    }
    fun motion(event: MotionEvent): Boolean {
        if(event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false
        fun axis(source: String, x: Float, y: Float) {
            val dx = axisDirection(x); val dy = axisDirection(y)
            input.setButton(source,0,KeyEvent.KEYCODE_DPAD_LEFT,dx < 0)
            input.setButton(source,0,KeyEvent.KEYCODE_DPAD_RIGHT,dx > 0)
            input.setButton(source,0,KeyEvent.KEYCODE_DPAD_UP,dy < 0)
            input.setButton(source,0,KeyEvent.KEYCODE_DPAD_DOWN,dy > 0)
        }
        axis("pad:${event.deviceId}:hat",event.getAxisValue(MotionEvent.AXIS_HAT_X),event.getAxisValue(MotionEvent.AXIS_HAT_Y))
        axis("pad:${event.deviceId}:stick",event.getAxisValue(MotionEvent.AXIS_X),event.getAxisValue(MotionEvent.AXIS_Y))
        return true
    }
    companion object {
        fun isPhysicalController(sources: Int, isVirtual: Boolean) = !isVirtual && (
            sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
        )

        fun isController(source: Int) = source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD
    }
}
