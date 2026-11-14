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
    private var sideWidth = 0

    fun setSideWidth(value: Int) {
        if (sideWidth == value) return
        sideWidth = value
        rebuildButtons(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuildButtons(w, h)
    }

    private fun rebuildButtons(w: Int, h: Int) {
        active.keys.forEach { input.releaseSource("touch:$it") }
        active.clear()
        buttons.clear()
        touchLayout(w.toFloat(),h.toFloat(),resources.displayMetrics.density,sideWidth.toFloat()).forEach {
            buttons[it.key] = it.label to RectF(it.left,it.top,it.right,it.bottom)
        }
    }
    override fun onDraw(canvas: Canvas) {
        buttons.forEach { (key,pair) ->
            val (label,r) = pair
            val pressed = active.values.any { key in it }
            val face = label == "A" || label == "B"
            paint.color = when {
                pressed -> Color.rgb(128, 139, 109)
                label == "A" -> Color.rgb(169, 54, 50)
                label == "B" -> Color.rgb(102, 115, 84)
                else -> Color.rgb(72, 78, 67)
            }
            val radius = if (face || label.length > 1) r.height()/2 else d(7f)
            canvas.drawRoundRect(r, radius, radius, paint)
            paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER
            paint.textSize = d(if(label.length > 1) 12f else 20f)
            canvas.drawText(label,r.centerX(),r.centerY()-(paint.ascent()+paint.descent())/2,paint)
        }
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (sideWidth > 0 && event.actionMasked == MotionEvent.ACTION_DOWN &&
            event.x >= sideWidth && event.x < width - sideWidth) return false
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
