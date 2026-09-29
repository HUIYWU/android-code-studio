package com.tom.rv2ide.fragments.terminal

import android.graphics.Color
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.google.android.material.button.MaterialButton
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.termux.view.TerminalView

class ExtraKeysHandler(
    private val rootView: View,
    private val terminalView: TerminalView?
) {
    
    private var extraKeysLayout: View? = null
    private var ctrlButton: MaterialButton? = null
    private var altButton: MaterialButton? = null
    
    private var ctrlPressed = false
    private var altPressed = false
    
    var currentSession: TermuxSession? = null
    
    fun setup() {
        extraKeysLayout = rootView.findViewById(com.tom.rv2ide.R.id.extra_keys_include)
        
        ctrlButton = extraKeysLayout?.findViewById(com.tom.rv2ide.R.id.key_ctrl)
        altButton = extraKeysLayout?.findViewById(com.tom.rv2ide.R.id.key_alt)
        
        setupKeyCodeButtons()
        setupTextButtons()
        setupModifierButtons()
    }
    
    private fun setupKeyCodeButtons() {
        // Direction and page keys repeat while held, like the original Termux extra keys.
        mapOf(
            com.tom.rv2ide.R.id.key_up to KeyEvent.KEYCODE_DPAD_UP,
            com.tom.rv2ide.R.id.key_down to KeyEvent.KEYCODE_DPAD_DOWN,
            com.tom.rv2ide.R.id.key_left to KeyEvent.KEYCODE_DPAD_LEFT,
            com.tom.rv2ide.R.id.key_right to KeyEvent.KEYCODE_DPAD_RIGHT,
            com.tom.rv2ide.R.id.key_pgup to KeyEvent.KEYCODE_PAGE_UP,
            com.tom.rv2ide.R.id.key_pgdn to KeyEvent.KEYCODE_PAGE_DOWN
        ).forEach { (id, keyCode) ->
            rootView.findViewById<MaterialButton>(id)?.setOnTouchListener(
                keyRepeatTouchListener {
                    sendKeyCode(keyCode)
                    resetModifiers()
                }
            )
        }

        mapOf(
            com.tom.rv2ide.R.id.key_esc to KeyEvent.KEYCODE_ESCAPE,
            com.tom.rv2ide.R.id.key_tab to KeyEvent.KEYCODE_TAB,
            com.tom.rv2ide.R.id.key_home to KeyEvent.KEYCODE_MOVE_HOME,
            com.tom.rv2ide.R.id.key_end to KeyEvent.KEYCODE_MOVE_END
        ).forEach { (id, keyCode) ->
            rootView.findViewById<MaterialButton>(id)?.setOnClickListener { 
                sendKeyCode(keyCode)
                resetModifiers()
            }
        }
    }
    
    /** Fires [onKey] immediately on touch-down, then repeats it while the key is held. */
    private fun keyRepeatTouchListener(onKey: () -> Unit): View.OnTouchListener {
        return object : View.OnTouchListener {
            private var repeat: Runnable? = null

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        repeat?.let { v.removeCallbacks(it) }
                        v.isPressed = true
                        onKey()
                        val runnable =
                            object : Runnable {
                                override fun run() {
                                    onKey()
                                    v.postDelayed(this, KEY_REPEAT_DELAY)
                                }
                            }
                        repeat = runnable
                        v.postDelayed(runnable, KEY_REPEAT_INITIAL_DELAY)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        repeat?.let { v.removeCallbacks(it) }
                        repeat = null
                        v.isPressed = false
                    }
                }
                return true
            }
        }
    }
    
    private fun setupTextButtons() {
        mapOf(
            com.tom.rv2ide.R.id.key_slash to "/",
            com.tom.rv2ide.R.id.key_minus to "-",
            com.tom.rv2ide.R.id.key_pipe to "|"
        ).forEach { (id, text) ->
            rootView.findViewById<MaterialButton>(id)?.setOnClickListener { 
                sendText(text)
                resetModifiers()
            }
        }
    }
    
    private fun setupModifierButtons() {
        ctrlButton?.setOnClickListener {
            ctrlPressed = !ctrlPressed
            updateModifierButton(ctrlButton, ctrlPressed)
        }
        
        altButton?.setOnClickListener {
            altPressed = !altPressed
            updateModifierButton(altButton, altPressed)
        }
    }
    
    private fun resetModifiers() {
        if (ctrlPressed || altPressed) {
            ctrlPressed = false
            altPressed = false
            updateModifierButton(ctrlButton, false)
            updateModifierButton(altButton, false)
        }
    }
    
    private fun updateModifierButton(button: MaterialButton?, pressed: Boolean) {
        button?.apply {
            if (pressed) {
                setBackgroundColor(Color.parseColor("#64B5F6"))
                setTextColor(Color.WHITE)
            } else {
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(context.getColor(android.R.color.darker_gray))
            }
        }
    }
    
    private fun sendKeyCode(keyCode: Int) {
        val eventTime = System.currentTimeMillis()
        var metaState = 0
        if (ctrlPressed) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (altPressed) metaState = metaState or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        
        terminalView?.onKeyDown(keyCode, KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        terminalView?.onKeyUp(keyCode, KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0, metaState))
    }
    
    private fun sendText(text: String) {
        currentSession?.terminalSession?.write(text)
    }
    
    fun setVisibility(visible: Boolean) {
        extraKeysLayout?.visibility = if (visible) View.VISIBLE else View.GONE
    }
    
    fun isCtrlPressed() = ctrlPressed
    fun isAltPressed() = altPressed

    private companion object {
        // Long-press delay before a key starts repeating, then the interval between repeats.
        const val KEY_REPEAT_INITIAL_DELAY = 500L
        const val KEY_REPEAT_DELAY = 100L
    }
}