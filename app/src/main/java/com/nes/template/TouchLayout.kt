package com.nes.template

data class TouchButton(val key: Int, val label: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

fun touchLayout(width: Float, height: Float, density: Float, sideWidth: Float = 0f): List<TouchButton> {
    val s = minOf(48f*density,height/4.5f,width/7.5f,
        if (sideWidth > 0f) sideWidth / 3.3f else Float.MAX_VALUE)
    val cy = height*0.44f
    val cx = if (sideWidth > 0f) sideWidth/2 else s*1.7f
    fun button(key: Int,label: String,x: Float,y: Float,w: Float=s,h: Float=s) =
        TouchButton(key,label,x-w/2,y-h/2,x+w/2,y+h/2)
    return listOf(
        button(19,"▲",cx,cy-s),button(20,"▼",cx,cy+s),
        button(21,"◀",cx-s,cy),button(22,"▶",cx+s,cy),
        button(96,"A",width-s*0.75f,cy-s/2),button(97,"B",width-s*1.85f,cy+s/2),
        button(109,"SELECT",if (sideWidth > 0f) cx else width/2-s*0.8f,height-s*0.55f,s*1.35f,s*0.5f),
        button(108,"START",if (sideWidth > 0f) width-cx else width/2+s*0.8f,height-s*0.55f,s*1.35f,s*0.5f)
    )
}
