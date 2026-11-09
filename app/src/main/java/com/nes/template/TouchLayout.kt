package com.nes.template

data class TouchButton(val key: Int, val label: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

fun touchLayout(width: Float, height: Float, density: Float): List<TouchButton> {
    val s = minOf(48f*density,height/3.4f,width/6.5f)
    val cy = height/2f
    val cx = s*1.7f
    fun button(key: Int,label: String,x: Float,y: Float,w: Float=s,h: Float=s) =
        TouchButton(key,label,x-w/2,y-h/2,x+w/2,y+h/2)
    return listOf(
        button(19,"▲",cx,cy-s),button(20,"▼",cx,cy+s),
        button(21,"◀",cx-s,cy),button(22,"▶",cx+s,cy),
        button(96,"A",width-s,cy-s/2),button(97,"B",width-s*2.3f,cy+s/2),
        button(109,"SELECT",width*0.45f,height-s*0.25f,s*1.35f,s*0.5f),
        button(108,"START",width*0.7f,height-s*0.25f,s*1.35f,s*0.5f)
    )
}
