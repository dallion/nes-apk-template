package com.nes.template

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

// Constructed in code with an injected input router; not inflated from XML.
@SuppressLint("ViewConstructor")
class TouchControls(context: Context, private val input: InputRouter) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttons = linkedMapOf<Int,Pair<String,RectF>>()
    private val active = mutableMapOf<Int,Set<Int>>()
    private fun d(value: Float) = value * resources.displayMetrics.density
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        buttons.clear()
        touchLayout(w.toFloat(),h.toFloat(),resources.displayMetrics.density).forEach {
            buttons[it.key] = it.label to RectF(it.left,it.top,it.right,it.bottom)
        }
    }
    override fun onDraw(canvas: Canvas) {
        buttons.forEach { (key,pair) ->
            val (label,r) = pair
            paint.color = if(active.values.any { key in it }) Color.rgb(91,125,170) else Color.rgb(43,51,65)
            canvas.drawRoundRect(r,d(12f),d(12f),paint)
            paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER
            paint.textSize = d(if(label.length > 1) 12f else 20f)
            canvas.drawText(label,r.centerX(),r.centerY()-(paint.ascent()+paint.descent())/2,paint)
        }
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(event.actionMasked == MotionEvent.ACTION_CANCEL) {
            active.keys.toList().forEach { input.releaseSource("touch:$it") }; active.clear(); invalidate(); return true
        }
        val released = if(event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
        for(i in 0 until event.pointerCount) {
            val id = event.getPointerId(i)
            val keys = if(i == released) emptySet() else buttons.filterValues { it.second.contains(event.getX(i),event.getY(i)) }.keys
            val previous = active[id].orEmpty()
            (previous + keys).forEach { input.setButton("touch:$id",0,it,it in keys) }
            if(keys.isEmpty()) active.remove(id) else active[id] = keys.toSet()
        }
        if(event.actionMasked == MotionEvent.ACTION_UP) performClick()
        invalidate(); return true
    }
    fun clearVisuals() { active.clear(); invalidate() }
}
