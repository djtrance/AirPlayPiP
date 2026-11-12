package io.github.jqssun.airplay.ui

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.core.content.ContextCompat
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.jqssun.airplay.R

/** A separate software-drawn window: controls must not disappear behind a hardware video layer. */
class FloatingControlPanel(
    private val context: Context,
    private val onNextCorner: () -> Unit,
    private val onArrows: () -> Unit,
    private val onRepair: () -> Unit,
    private val onExpand: () -> Unit,
    private val onHome: () -> Unit,
    private val onExit: () -> Unit,
    private val onStateChanged: () -> Unit
) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var status: TextView? = null
    private var actions: LinearLayout? = null
    private var first: Button? = null
    private var toggleButton: Button? = null
    private var corner = "bottom_right"
    private var expanded = false
    private var focusHeld = false
    private var autoRelease = true
    private var observingSystemDialogs = false
    private val systemDialogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val reason = intent.getStringExtra("reason").orEmpty()
            if (reason == "homekey" || reason == "recentapps") {
                // Android has already handled the system key; stop the app overlay retaining D-pad focus.
                expand(false, autoRelease = false)
            }
        }
    }
    val isExpanded: Boolean get() = expanded
    private val release = Runnable { expand(false) }

    fun show(corner: String) {
        if (root != null) return
        this.corner = corner
        val frame = object : LinearLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (focusHeld) {
                    if (autoRelease) scheduleRelease()
                    if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                        if (event.action == KeyEvent.ACTION_UP) {
                            if (expanded) collapseKeepingFocus() else expand(false, autoRelease = false)
                        }
                        return true
                    }
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.rgb(16, 23, 39)); cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.rgb(140, 191, 255))
            }
        }
        root = frame
        toggleButton = button(R.string.floating_hide_menu) { if (expanded) collapseKeepingFocus() else expand(true, autoRelease = false) }
            .also { frame.addView(it) }
        status = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }.also { frame.addView(it) }
        actions = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }.also { frame.addView(it) }
        first = button(R.string.floating_next_corner, onNextCorner).also { actions?.addView(it) }
        actions?.addView(button(R.string.floating_hide_menu, ::collapseKeepingFocus))
        actions?.addView(button(R.string.floating_arrows) { expand(false); onArrows() })
        actions?.addView(button(R.string.floating_repair, onRepair))
        actions?.addView(button(R.string.cd_fullscreen, onExpand))
        actions?.addView(button(R.string.floating_android_home) { expand(false, autoRelease = false); onHome() })
        actions?.addView(button(R.string.floating_release_focus) { expand(false, autoRelease = false) })
        actions?.addView(button(R.string.close_application, onExit))
        actions?.visibility = android.view.View.GONE
        status?.visibility = android.view.View.GONE
        try {
            manager.addView(frame, params())
            try {
                ContextCompat.registerReceiver(context, systemDialogReceiver,
                    IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), ContextCompat.RECEIVER_NOT_EXPORTED)
                observingSystemDialogs = true
            } catch (e: RuntimeException) {
                Log.i("FloatingControls", "System Home focus release broadcast unavailable", e)
            }
        } catch (e: RuntimeException) {
            Log.w("FloatingControls", "Cannot show independent controls", e)
            hide()
        }
    }

    fun setStatus(text: String) {
        status?.text = text
        status?.maxLines = if (expanded) 4 else 0
    }

    fun move(corner: String) {
        this.corner = corner
        update()
    }

    fun expand(enabled: Boolean, autoRelease: Boolean = true, keepFocus: Boolean = false) {
        if (root == null) return
        expanded = enabled
        focusHeld = enabled || keepFocus
        this.autoRelease = autoRelease
        actions?.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
        status?.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
        toggleButton?.setText(if (enabled) R.string.floating_hide_menu else R.string.floating_show_menu)
        handler.removeCallbacks(release)
        update()
        if (enabled) {
            first?.requestFocus()
            if (autoRelease) scheduleRelease()
        } else if (focusHeld) {
            toggleButton?.requestFocus()
        }
        onStateChanged()
    }

    private fun collapseKeepingFocus() = expand(enabled = false, autoRelease = false, keepFocus = true)

    fun hide() {
        handler.removeCallbacks(release)
        val frame = root ?: return
        root = null
        if (observingSystemDialogs) {
            try { context.unregisterReceiver(systemDialogReceiver) } catch (_: RuntimeException) { }
            observingSystemDialogs = false
        }
        try {
            if (frame.isAttachedToWindow) manager.removeViewImmediate(frame)
        } catch (e: RuntimeException) { Log.w("FloatingControls", "Cannot remove controls", e) }
        status = null
        actions = null
        first = null
        toggleButton = null
        expanded = false
        focusHeld = false
    }

    private fun update() {
        val frame = root ?: return
        try { manager.updateViewLayout(frame, params()) }
        catch (e: RuntimeException) { Log.w("FloatingControls", "Controls update failed", e); hide() }
    }

    private fun scheduleRelease() {
        handler.removeCallbacks(release)
        handler.postDelayed(release, 15_000)
    }

    private fun button(label: Int, action: () -> Unit) = Button(context).apply {
        setText(label)
        isAllCaps = false
        textSize = 12f
        setTextColor(Color.WHITE)
        minimumHeight = 0
        minHeight = 0
        setPadding(dp(6), 0, dp(6), 0)
        layoutParams = LinearLayout.LayoutParams(-1, dp(36)).apply { setMargins(0, dp(2), 0, dp(2)) }
        fun decorate(focused: Boolean) {
            background = GradientDrawable().apply {
                setColor(if (focused) Color.rgb(30, 75, 120) else Color.rgb(30, 40, 60))
                cornerRadius = dp(8).toFloat()
                if (focused) setStroke(dp(2), Color.rgb(140, 191, 255))
            }
        }
        decorate(false)
        setOnFocusChangeListener { _, hasFocus -> decorate(hasFocus) }
        setOnClickListener { if (autoRelease) scheduleRelease(); action() }
    }

    private fun params() = WindowManager.LayoutParams(
        dp(230), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            (if (focusHeld) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
        PixelFormat.TRANSLUCENT
    ).apply {
        // Intentionally no FLAG_HARDWARE_ACCELERATED: this is a software UI window.
        gravity = Gravity.TOP or if (corner.endsWith("right")) Gravity.LEFT else Gravity.RIGHT
        x = dp(12)
        y = dp(12)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alpha = 0.8f
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
