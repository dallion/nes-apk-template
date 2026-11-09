package com.nes.template

class InputRouter(private val send: (Int, Int, Boolean) -> Unit) {
    private val held = linkedMapOf<String, MutableSet<Pair<Int, Int>>>()
    fun setButton(source: String, player: Int, button: Int, pressed: Boolean) {
        val key = player to button
        val before = held.values.any { key in it }
        if (pressed) held.getOrPut(source) { linkedSetOf() }.add(key)
        else held[source]?.let { it.remove(key); if(it.isEmpty()) held.remove(source) }
        val after = held.values.any { key in it }
        if (before != after) send(player, button, after)
    }
    fun releaseSource(source: String) {
        held[source]?.toList()?.forEach { (p,b) -> setButton(source,p,b,false) }
    }
    fun releaseAll() { held.keys.toList().forEach(::releaseSource) }
}

fun axisDirection(value: Float): Int = when {
    !value.isFinite() -> 0
    value > 0.25f -> 1
    value < -0.25f -> -1
    else -> 0
}
