package com.nes.template

import android.content.Context
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** One in-window overlay. Page changes never detach or resize the game Surface. */
class GameMenu(private val context: Context, root: FrameLayout, private val onClose: () -> Unit) {
    companion object {
        val PAPER = Color.rgb(231,228,219)
        val INK = Color.rgb(44,47,49)
        val MUTED = Color.rgb(98,100,97)
        val RED = Color.rgb(157,42,48)
        val FIELD = Color.rgb(247,245,239)

        fun button(context: Context, label: String, primary: Boolean = false, action: () -> Unit): TextView {
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value*density).toInt()
            fun shape(color: Int, border: Int) = GradientDrawable().apply {
                setColor(color); cornerRadius = dp(8).toFloat(); setStroke(dp(2),border)
            }
            return object : TextView(context) {
                // TextView's touch handler calls performClick; this only changes focus timing.
                @SuppressLint("ClickableViewAccessibility")
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    // Acquire focus on DOWN so Android does not consume the first UP only to focus.
                    if(event.actionMasked == MotionEvent.ACTION_DOWN) requestFocus()
                    return super.onTouchEvent(event)
                }
                override fun performClick(): Boolean = super.performClick()
            }.apply {
                text = label; textSize = 16f; gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL)
                setTextColor(if(primary) Color.WHITE else INK)
                minHeight = dp(50); setPadding(dp(16),dp(12),dp(16),dp(12))
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed),shape(if(primary) Color.rgb(121,29,35) else Color.rgb(209,205,194),RED))
                    addState(intArrayOf(android.R.attr.state_focused),shape(if(primary) RED else FIELD,if(primary) INK else RED))
                    addState(intArrayOf(),shape(if(primary) RED else FIELD,if(primary) RED else Color.rgb(207,204,195)))
                }
                isClickable = true; isFocusable = true; isFocusableInTouchMode = true
                setOnClickListener { action() }
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host,info)
                        info.className = "android.widget.Button"
                    }
                }
            }
        }
    }

    private fun dp(value: Int) = (value*context.resources.displayMetrics.density).toInt()
    private val overlay = FrameLayout(context).apply {
        setBackgroundColor(0xb31c1e20.toInt())
        visibility = View.GONE
        isClickable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private var back: (() -> Unit)? = null
    private var capture: ((KeyEvent) -> Boolean)? = null
    private var rebuild: (() -> Unit)? = null
    private var joystickDirection = 0
    private val fields = mutableListOf<EditText>()
    var isShowing = false
        private set

    init { root.addView(overlay,FrameLayout.LayoutParams(-1,-1)) }

    fun show(title: String, subtitle: String = "", onBack: (() -> Unit)? = null, build: Page.() -> Unit) {
        hideKeyboard()
        isShowing = true
        overlay.visibility = View.VISIBLE
        overlay.removeAllViews()
        fields.clear()
        back = onBack ?: ::dismiss
        capture = null
        joystickDirection = 0
        rebuild = { show(title,subtitle,onBack,build) }
        val maxWidth = minOf(context.resources.displayMetrics.widthPixels-dp(32),dp(760))
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24),dp(20),dp(24),dp(20))
            background = GradientDrawable().apply { setColor(PAPER); cornerRadius = dp(14).toFloat() }
            isClickable = true
        }
        val scroll = ScrollView(context).apply {
            isFocusable = false
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(card,ViewGroup.LayoutParams(-1,-2))
        }
        overlay.addView(scroll,FrameLayout.LayoutParams(maxWidth,-2,Gravity.CENTER).apply {
            setMargins(dp(16),dp(16),dp(16),dp(16))
        })
        val page = Page(card)
        page.heading(title)
        if(subtitle.isNotEmpty()) page.text(subtitle)
        page.build()
        page.firstButton?.requestFocus()
    }

    fun resize() {
        if(!isShowing) return
        val values = fields.map { it.text.toString() }
        val focused = fields.indexOfFirst { it.hasFocus() }
        val handler = capture
        rebuild?.invoke()
        fields.zip(values).forEach { (field,value) -> field.setText(value) }
        fields.getOrNull(focused)?.apply { requestFocus(); setSelection(text.length) }
        capture = handler
    }
    fun onKey(handler: (KeyEvent) -> Boolean) { capture = handler }
    fun setKeyboardInset(bottom: Int) { overlay.setPadding(0,0,0,bottom) }
    fun back() { back?.invoke() }
    fun dismiss() {
        if(!isShowing) return
        hideKeyboard()
        isShowing = false; capture = null; back = null; rebuild = null
        overlay.visibility = View.GONE
        overlay.removeAllViews()
        fields.clear()
        onClose()
    }
    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(overlay.windowToken,0)
    }
    fun key(event: KeyEvent): Boolean {
        if(!isShowing) return false
        if(capture?.invoke(event) == true) return true
        val editing = overlay.findFocus() is EditText
        val direction = when(event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            else -> 0
        }
        if(!editing && direction != 0) {
            if(event.action == KeyEvent.ACTION_DOWN) moveFocus(direction)
            return true
        }
        if(event.keyCode in intArrayOf(KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_ESCAPE,KeyEvent.KEYCODE_BUTTON_B)) {
            if(event.action == KeyEvent.ACTION_UP && !event.isCanceled) back()
            return true
        }
        if(!editing && event.keyCode in intArrayOf(KeyEvent.KEYCODE_BUTTON_A,KeyEvent.KEYCODE_BUTTON_START,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER)) {
            if(event.action == KeyEvent.ACTION_UP && !event.isCanceled) overlay.findFocus()?.performClick()
            return true
        }
        return false
    }
    fun motion(event: MotionEvent): Boolean {
        if(!isShowing || !ControllerInput.isController(event.source)) return false
        val x = event.getAxisValue(MotionEvent.AXIS_X)+event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)+event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val direction = when {
            y < -0.5f -> View.FOCUS_UP
            y > 0.5f -> View.FOCUS_DOWN
            x < -0.5f -> View.FOCUS_LEFT
            x > 0.5f -> View.FOCUS_RIGHT
            else -> 0
        }
        if(direction != 0 && direction != joystickDirection) moveFocus(direction)
        joystickDirection = direction
        return true
    }
    private fun moveFocus(direction: Int) {
        val current = overlay.findFocus()
        if(current == null) { overlay.findViewWithTag<View>("first-action")?.requestFocus(); return }
        val next = current.focusSearch(direction) ?: return
        var ancestor: android.view.ViewParent? = next.parent
        while(ancestor != null && ancestor !== overlay) ancestor = ancestor.parent
        if(ancestor === overlay) next.requestFocus()
    }

    inner class Page(private val column: LinearLayout) {
        internal var firstButton: TextView? = null
        fun heading(value: String) {
            column.addView(TextView(context).apply {
                text = value; textSize = 24f; setTextColor(INK)
                typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL)
                setPadding(0,0,0,dp(12))
            })
        }
        fun text(value: String): TextView = TextView(context).apply {
            text = value; textSize = 14f; setTextColor(MUTED)
            setPadding(0,0,0,dp(12))
            column.addView(this,LinearLayout.LayoutParams(-1,-2))
        }
        fun section(value: String) {
            column.addView(TextView(context).apply {
                text = value; textSize = 13f; setTextColor(MUTED)
                setPadding(0,dp(14),0,dp(8))
            })
        }
        fun action(label: String, primary: Boolean = false, action: () -> Unit): TextView {
            val button = button(context,label,primary,action)
            if(firstButton == null) { firstButton = button; button.tag = "first-action" }
            column.addView(button,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(8) })
            return button
        }
        fun pair(first: Pair<String,() -> Unit>, second: Pair<String,() -> Unit>) {
            if(context.resources.configuration.screenWidthDp < 600) {
                action(first.first,action=first.second); action(second.first,action=second.second)
            } else {
                val row = LinearLayout(context)
                listOf(first,second).forEachIndexed { index,item ->
                    val button = button(context,item.first,action=item.second)
                    if(firstButton == null) { firstButton = button; button.tag = "first-action" }
                    row.addView(button,LinearLayout.LayoutParams(0,-2,1f).apply { if(index == 0) marginEnd = dp(10) })
                }
                column.addView(row,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(8) })
            }
        }
        fun field(hint: String, multiline: Boolean = false): EditText = EditText(context).apply {
            fields.add(this)
            this.hint = hint; textSize = 16f; setTextColor(INK); setHintTextColor(MUTED)
            background = GradientDrawable().apply {
                setColor(FIELD); cornerRadius = dp(6).toFloat(); setStroke(dp(1),MUTED)
            }
            setPadding(dp(12),dp(12),dp(12),dp(12))
            if(multiline) {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                minLines = 3; maxLines = 8; gravity = Gravity.TOP
            } else setSingleLine()
            column.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(12) })
        }
    }
}
