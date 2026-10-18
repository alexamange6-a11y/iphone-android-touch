package com.example.iphonebridge

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Transparent controller layer intended to be opened above an AirPlay receiver.
 * The AirPlay video remains visible underneath; touches on the video are converted
 * to iPhone coordinates and sent through the Android Bluetooth HID service.
 */
class ControlOverlayActivity : Activity() {
    private lateinit var touch: TouchSurface
    private lateinit var status: TextView
    private var serviceStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDimAmount(0f)
        makeUi()
        startHid()
    }

    private fun startHid() {
        try {
            val i = Intent(this, HidBridgeService::class.java).apply { action = HidBridgeService.ACTION_REGISTER_HID }
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            serviceStarted = true
            status.text = "HID: запуск • подключи iPhone по Bluetooth"
        } catch (e: Exception) {
            status.text = "HID ошибка: ${e.message}"
        }
    }

    private fun makeUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        touch = TouchSurface(this)
        touch.setBackgroundColor(Color.TRANSPARENT)
        touch.onTap = { x, y ->
            sendTap(x, y, touch.width, touch.height)
        }
        root.addView(touch, LinearLayout.LayoutParams(-1, 0, 1f))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(0xCC111111.toInt())
        }

        status = TextView(this).apply {
            text = "HID: запускается…"
            textSize = 12f
            setTextColor(Color.WHITE)
        }
        panel.addView(status, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val keyboard = EditText(this).apply {
            hint = "Печатать на iPhone…"
            textSize = 15f
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(0x99FFFFFF.toInt())
            setBackgroundColor(0x55333333.toInt())
            setPadding(12, 0, 12, 0)
            addTextChangedListener(HidTextWatcher(this@ControlOverlayActivity, this))
        }
        row.addView(keyboard, LinearLayout.LayoutParams(0, 46, 1f))

        fun keyButton(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 11f
            setPadding(4, 0, 4, 0)
            setOnClickListener { action() }
        }
        row.addView(keyButton("⌫") { sendKey(HidKey.BACKSPACE) }, LinearLayout.LayoutParams(48, 46))
        row.addView(keyButton("↵") { sendKey(HidKey.ENTER) }, LinearLayout.LayoutParams(48, 46))
        row.addView(keyButton("⌨") { keyboard.requestFocus(); (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(keyboard, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }, LinearLayout.LayoutParams(48, 46))
        panel.addView(row)

        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER }
        buttons.addView(keyButton("HOME") { sendKey(HidKey.HOME) }, LinearLayout.LayoutParams(0, 42, 1f))
        buttons.addView(keyButton("←") { sendKey(HidKey.LEFT) }, LinearLayout.LayoutParams(0, 42, 1f))
        buttons.addView(keyButton("→") { sendKey(HidKey.RIGHT) }, LinearLayout.LayoutParams(0, 42, 1f))
        buttons.addView(keyButton("↑") { sendKey(HidKey.UP) }, LinearLayout.LayoutParams(0, 42, 1f))
        buttons.addView(keyButton("↓") { sendKey(HidKey.DOWN) }, LinearLayout.LayoutParams(0, 42, 1f))
        buttons.addView(keyButton("ESC") { sendKey(HidKey.ESC) }, LinearLayout.LayoutParams(0, 42, 1f))
        panel.addView(buttons)

        root.addView(panel, LinearLayout.LayoutParams(-1, 136))
        setContentView(root)
    }

    private fun sendTap(viewX: Float, viewY: Float, viewW: Int, viewH: Int) {
        if (viewW <= 0 || viewH <= 0) return
        // iPhone 11 logical screen: 828x1792. Fit the actual mirrored surface while
        // preserving aspect ratio; black/empty bars are ignored.
        val targetAspect = 828f / 1792f
        val viewAspect = viewW.toFloat() / viewH.toFloat()
        val contentW: Float
        val contentH: Float
        val offX: Float
        val offY: Float
        if (viewAspect > targetAspect) {
            contentH = viewH.toFloat(); contentW = contentH * targetAspect
            offX = (viewW - contentW) / 2f; offY = 0f
        } else {
            contentW = viewW.toFloat(); contentH = contentW / targetAspect
            offX = 0f; offY = (viewH - contentH) / 2f
        }
        val nx = ((viewX - offX) / contentW).coerceIn(0f, 1f)
        val ny = ((viewY - offY) / contentH).coerceIn(0f, 1f)
        val px = (nx * 828f).toInt()
        val py = (ny * 1792f).toInt()
        sendServiceTap(px, py)
        status.text = "Tap iPhone: $px,$py"
    }

    private fun sendServiceTap(x: Int, y: Int) {
        val i = Intent(this, HidBridgeService::class.java).apply {
            action = HidBridgeService.ACTION_TAP_ABSOLUTE
            putExtra(HidBridgeService.EXTRA_X, x)
            putExtra(HidBridgeService.EXTRA_Y, y)
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun sendKey(code: Int) {
        val i = Intent(this, HidBridgeService::class.java).apply {
            action = HidBridgeService.ACTION_KEY
            putExtra(HidBridgeService.EXTRA_KEY, code)
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    class TouchSurface(context: android.content.Context) : View(context) {
        var onTap: ((Float, Float) -> Unit)? = null
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; moved = false; return true }
                MotionEvent.ACTION_MOVE -> { if (kotlin.math.hypot((event.x-downX).toDouble(), (event.y-downY).toDouble()) > 18) moved = true; return true }
                MotionEvent.ACTION_UP -> { if (!moved) onTap?.invoke(event.x, event.y); performClick(); return true }
            }
            return true
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }

    private object HidKey {
        const val ENTER = 0x28; const val BACKSPACE = 0x2A; const val ESC = 0x29
        const val HOME = 0x4A; const val LEFT = 0x50; const val RIGHT = 0x4F; const val UP = 0x52; const val DOWN = 0x51
    }

    private class HidTextWatcher(private val activity: ControlOverlayActivity, private val edit: EditText) : android.text.TextWatcher {
        private var changing = false
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) {
            if (changing || s.isNullOrEmpty()) return
            val text = s.toString()
            changing = true
            edit.setText("")
            changing = false
            text.forEach { activity.sendUnicode(it) }
        }
    }

    private fun sendUnicode(c: Char) {
        val pair = HidKeyMap.map(c) ?: return
        val i = Intent(this, HidBridgeService::class.java).apply {
            action = HidBridgeService.ACTION_KEY_CODE
            putExtra(HidBridgeService.EXTRA_USAGE, pair.first)
            putExtra(HidBridgeService.EXTRA_MODIFIER, pair.second)
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }
}

private object HidKeyMap {
    fun map(c: Char): Pair<Int, Int>? {
        val upper = c.isUpperCase()
        val base = c.lowercaseChar()
        if (base in 'a'..'z') return Pair(0x04 + (base.code - 'a'.code), if (upper) 0x02 else 0)
        if (base in '1'..'9') return Pair(0x1E + (base.code - '1'.code), 0)
        if (base == '0') return Pair(0x27, 0)
        return when (c) {
            ' ' -> Pair(0x2C, 0); '.' -> Pair(0x37, 0); ',' -> Pair(0x36, 0); '!' -> Pair(0x1E, 0x02)
            '?' -> Pair(0x38, 0x02); '-' -> Pair(0x2D, 0); '_' -> Pair(0x2D, 0x02); '/' -> Pair(0x38, 0)
            ':' -> Pair(0x33, 0x02); ';' -> Pair(0x33, 0); '\n' -> Pair(0x28, 0); '\t' -> Pair(0x2B, 0)
            '(' -> Pair(0x26, 0x02); ')' -> Pair(0x27, 0x02); '+' -> Pair(0x2E, 0x02); '=' -> Pair(0x2E, 0)
            else -> null
        }
    }
}
