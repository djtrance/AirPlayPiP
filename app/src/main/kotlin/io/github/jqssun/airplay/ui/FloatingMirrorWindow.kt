package io.github.jqssun.airplay.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import io.github.jqssun.airplay.FloatingRecoveryPolicy
import io.github.jqssun.airplay.FloatingWindowPolicy
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.VideoCounters
import io.github.jqssun.airplay.VideoHealth
import io.github.jqssun.airplay.VideoHealthMonitor
import io.github.jqssun.airplay.renderer.VideoPipeline

/** Service-owned output, independent of Activity visibility. Call only on the main thread. */
class FloatingMirrorWindow(
    context: Context,
    private val attach: (Surface) -> Unit,
    private val detach: (Surface) -> Unit,
    private val inputFrames: () -> Long,
    private val displayedFrames: () -> Long,
    private val decodedFrames: () -> Long,
    private val consumedFrames: () -> Long,
    private val decoderDetails: () -> String,
    private val softwareFrame: () -> VideoPipeline.SoftwareFrame?,
    private val softwareOutput: (Boolean) -> Unit,
    private val onExpand: () -> Unit,
    private val onHome: () -> Unit,
    private val onMove: (String) -> Unit,
    private val onStateChanged: () -> Unit,
    private val onDiagnostics: (String) -> Unit,
    private val onDiagnosticEvent: (String) -> Unit,
    private val onExit: () -> Unit
) {
    // A window context follows display configuration changes, including HDMI resolution/rate changes.
    private val windowContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.createDisplayContext(context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY))
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    } else context
    private val manager = windowContext.getSystemService(WindowManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var caption: TextView? = null
    private var videoView: View? = null
    private var output: Surface? = null
    private var ownsOutput = false
    private var aspect = 16f / 9f
    private var corner = "bottom_right"
    private var renderer = "surface"
    private var textureFrames = 0L
    private var recovery = FloatingRecoveryPolicy()
    private var health = VideoHealthMonitor()
    private var previousHealth: VideoHealth? = null
    private var scheduledControlsAt = 0L
    private val showScheduledControls = Runnable { scheduledControlsAt = 0; openControls() }
    private val panel = FloatingControlPanel(windowContext,
        onNextCorner = { nextCorner() }, onArrows = { select(true) },
        onRepair = { repairVideo() }, onExpand = onExpand, onHome = onHome, onExit = onExit, onStateChanged = onStateChanged)
    private var listening = false
    val visible: Boolean get() = root != null
    var controlsActive = false; private set
    val hasControlsFocus: Boolean get() = controlsActive || panel.isExpanded

    private val releaseControls = Runnable { select(false) }
    private val refreshDisplay = Runnable {
        if (visible) {
            updateLayout()
            recreateOutput("display configuration changed")
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == (root?.display?.displayId ?: Display.DEFAULT_DISPLAY)) {
                handler.removeCallbacks(refreshDisplay)
                handler.postDelayed(refreshDisplay, 500)
            }
        }
    }
    private val watchdog = object : Runnable {
        override fun run() {
            if (!visible) return
            val incoming = inputFrames()
            val presented = when (renderer) {
                "texture" -> textureFrames
                "software" -> (videoView as? SoftwareMirrorView)?.drawnFrames ?: 0L
                else -> displayedFrames()
            }
            val now = SystemClock.elapsedRealtime()
            val sample = health.sample(now, VideoCounters(incoming, decodedFrames(), consumedFrames(), presented))
            val stateLabel = windowContext.getString(when (sample.state) {
                VideoHealth.CHECKING -> R.string.health_checking
                VideoHealth.SOURCE_IDLE -> R.string.health_source_idle
                VideoHealth.DECODER_STALLED -> R.string.health_decoder_stalled
                VideoHealth.GPU_STALLED -> R.string.health_gpu_stalled
                VideoHealth.OUTPUT_STALLED -> R.string.health_output_stalled
                VideoHealth.OUTPUT_ADVANCING -> R.string.health_output_advancing
            })
            var diagnostics = windowContext.getString(R.string.floating_health_format,
                renderer, sample.rxFps, sample.decodeFps, sample.outputFps, stateLabel)
            diagnostics += "\n" + decoderDetails()
            if (scheduledControlsAt > now) diagnostics += "\n" + windowContext.getString(
                R.string.floating_controls_countdown, ((scheduledControlsAt - now + 999) / 1000).toInt())
            panel.setStatus(diagnostics)
            onDiagnostics(diagnostics)
            if (sample.state != previousHealth) {
                val event = "Video health ${sample.state}: rx=$incoming decoded=${decodedFrames()} consumed=${consumedFrames()} output=$presented backend=$renderer\n${decoderDetails()}"
                Log.i(TAG, event)
                onDiagnosticEvent(event)
                previousHealth = sample.state
            }
            if (sample.state == VideoHealth.OUTPUT_STALLED && recovery.shouldRecreate(now, incoming, presented)) {
                recreateOutput("stalled output: input=$incoming presented=$presented backend=$renderer")
            }
            handler.postDelayed(this, 1_000)
        }
    }

    fun show(aspect: Float, corner: String, renderer: String): Boolean {
        if (visible) return true
        if (!Settings.canDrawOverlays(windowContext)) return false
        this.aspect = aspect
        this.corner = corner
        this.renderer = renderer
        recovery = FloatingRecoveryPolicy()
        health = VideoHealthMonitor()
        previousHealth = null
        softwareOutput(renderer == "software")
        controlsActive = false
        val frame = object : FrameLayout(windowContext) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (!controlsActive) return super.dispatchKeyEvent(event)
                val direction = when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> FloatingWindowPolicy.Direction.UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> FloatingWindowPolicy.Direction.DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> FloatingWindowPolicy.Direction.LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT -> FloatingWindowPolicy.Direction.RIGHT
                    else -> null
                }
                val handled = direction != null || event.keyCode in intArrayOf(
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU)
                if (!handled) return super.dispatchKeyEvent(event)
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (direction != null) {
                        move(FloatingWindowPolicy.move(this@FloatingMirrorWindow.corner, direction))
                        scheduleRelease()
                    }
                    // Finish on key-up so HDMI never receives an orphaned OK/Back release.
                } else if (event.action == KeyEvent.ACTION_UP && direction == null) {
                    if (event.keyCode == KeyEvent.KEYCODE_MENU) onExpand()
                    select(false)
                }
                return true
            }
        }.apply {
            clipToOutline = true
            elevation = dp(16).toFloat()
            isFocusableInTouchMode = true
            descendantFocusability = FrameLayout.FOCUS_BLOCK_DESCENDANTS
            setOnClickListener { select(true) }
        }
        root = frame
        caption = TextView(windowContext).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(Color.rgb(16, 23, 39))
        }.also { frame.addView(it, FrameLayout.LayoutParams(-1, dp(28), Gravity.BOTTOM)) }
        installVideoView()
        return try {
            manager.addView(frame, params())
            updateCaption()
            displays.registerDisplayListener(displayListener, handler)
            listening = true
            panel.show(corner)
            // In floating mode the remote operates the PiP control menu, not the HDMI source.
            panel.expand(true, autoRelease = false)
            handler.postDelayed(watchdog, 1_000)
            onStateChanged()
            Log.i(TAG, "Overlay shown: backend=$renderer hardwareFlag=${renderer != "software"} device=${Build.MANUFACTURER} ${Build.MODEL} API=${Build.VERSION.SDK_INT}")
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cannot add overlay window", e)
            hide()
            false
        }
    }

    fun update(aspect: Float, corner: String, renderer: String) {
        if (visible && this.renderer != renderer) {
            hide() // Hardware acceleration is fixed when ViewRoot is created, not switchable in-place.
            show(aspect, corner, renderer)
            return
        }
        this.aspect = aspect
        this.corner = corner
        this.renderer = renderer
        if (!visible) return
        updateLayout()
        panel.move(corner)
    }

    fun openControls(delaySeconds: Int = 0) {
        if (!visible) return
        if (controlsActive) select(false)
        handler.removeCallbacks(showScheduledControls)
        scheduledControlsAt = 0L
        if (delaySeconds > 0) {
            panel.expand(false)
            scheduledControlsAt = SystemClock.elapsedRealtime() + delaySeconds.coerceIn(1, 30) * 1_000L
            handler.postDelayed(showScheduledControls, delaySeconds.coerceIn(1, 30) * 1_000L)
        } else panel.expand(true)
    }

    fun releaseControls() {
        handler.removeCallbacks(showScheduledControls)
        scheduledControlsAt = 0L
        select(false)
        panel.expand(false)
    }

    fun repairVideo() {
        health = VideoHealthMonitor()
        recovery = FloatingRecoveryPolicy()
        recreateOutput("explicit repair requested")
    }

    fun select(enabled: Boolean) {
        val frame = root ?: return
        controlsActive = enabled
        handler.removeCallbacks(releaseControls)
        updateCaption()
        updateLayout()
        panel.move(corner)
        if (enabled && visible) {
            frame.requestFocus()
            scheduleRelease()
        }
        onStateChanged()
    }

    fun move(corner: String) {
        this.corner = corner
        updateLayout()
        panel.move(corner)
        onMove(corner)
    }

    fun nextCorner() = move(FloatingWindowPolicy.nextCorner(corner))

    fun hide() {
        panel.hide()
        handler.removeCallbacks(showScheduledControls)
        scheduledControlsAt = 0L
        softwareOutput(false)
        val frame = root ?: return
        root = null
        controlsActive = false
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(releaseControls)
        handler.removeCallbacks(refreshDisplay)
        if (listening) displays.unregisterDisplayListener(displayListener)
        listening = false
        releaseOutput()
        try {
            if (frame.isAttachedToWindow) manager.removeViewImmediate(frame)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Overlay removal failed", e)
        }
        videoView = null
        caption = null
        onStateChanged()
        onDiagnostics("")
    }

    private fun scheduleRelease() {
        handler.removeCallbacks(releaseControls)
        handler.postDelayed(releaseControls, 15_000)
    }

    private fun updateCaption() {
        caption?.text = windowContext.getString(if (controlsActive) R.string.floating_remote_hint else R.string.floating_idle_hint)
        caption?.maxLines = if (controlsActive) 4 else 1
        caption?.ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun recreateOutput(reason: String) {
        if (!visible) return
        Log.w(TAG, "Recreating output surface only: $reason")
        releaseOutput()
        videoView?.let { root?.removeView(it) }
        installVideoView()
        updateLayout()
    }

    private fun installVideoView() {
        val frame = root ?: return
        val view: View = if (renderer == "software") {
            SoftwareMirrorView(windowContext, softwareFrame)
        } else if (renderer == "texture") {
            TextureView(windowContext).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        if (videoView === this@apply && visible) {
                            releaseOutput()
                            output = Surface(texture)
                            ownsOutput = true
                            output?.let(attach)
                        }
                    }
                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                        if (videoView === this@apply) output?.let(attach)
                    }
                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) { textureFrames++ }
                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        if (videoView === this@apply) releaseOutput()
                        return true
                    }
                }
            }
        } else {
            SurfaceView(windowContext).apply {
                // A separate compositor surface avoids depending on background HWUI TextureView draws.
                setZOrderMediaOverlay(true)
                holder.addCallback(object : SurfaceHolder.Callback {
                    private fun publish(holder: SurfaceHolder) {
                        if (videoView === this@apply && visible) {
                            if (output !== holder.surface) releaseOutput()
                            output = holder.surface // Owned by SurfaceHolder, never release it ourselves.
                            ownsOutput = false
                            attach(holder.surface)
                        }
                    }
                    override fun surfaceCreated(holder: SurfaceHolder) = publish(holder)
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = publish(holder)
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        if (output === holder.surface) releaseOutput()
                    }
                })
            }
        }
        videoView = view
        frame.addView(view, 0, FrameLayout.LayoutParams(-1, dimensions().second))
    }

    private fun releaseOutput() {
        output?.let { detach(it); if (ownsOutput) it.release() }
        output = null
        ownsOutput = false
    }

    private fun updateLayout() {
        val frame = root ?: return
        videoView?.layoutParams = FrameLayout.LayoutParams(-1, dimensions().second)
        caption?.layoutParams = FrameLayout.LayoutParams(-1, dp(if (controlsActive) 64 else 28), Gravity.BOTTOM)
        try {
            manager.updateViewLayout(frame, params())
            frame.invalidate()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Overlay update failed", e)
            hide()
        }
    }

    private fun dimensions(): Pair<Int, Int> {
        val metrics = windowContext.resources.displayMetrics
        val safeAspect = if (aspect.isFinite() && aspect > 0) aspect else 16f / 9f
        val maxHeight = (metrics.heightPixels * 0.6f - dp(64)).toInt().coerceAtLeast(1)
        val width = minOf((metrics.widthPixels * 0.36f).toInt(), (maxHeight * safeAspect).toInt()).coerceAtLeast(1)
        return width to (width / safeAspect).toInt().coerceAtLeast(1)
    }

    private fun params(): WindowManager.LayoutParams {
        val (width, height) = dimensions()
        root?.background = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = dp(18).toFloat()
            setStroke(dp(if (controlsActive) 3 else 1), if (controlsActive) Color.rgb(140, 191, 255) else Color.DKGRAY)
        }
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (renderer != "software") flags = flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (!controlsActive) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return WindowManager.LayoutParams(width, height + dp(if (controlsActive) 64 else 28),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = (if (corner.startsWith("top")) Gravity.TOP else Gravity.BOTTOM) or
                (if (corner.endsWith("left")) Gravity.LEFT else Gravity.RIGHT)
            x = dp(20)
            y = dp(20)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alpha = 0.8f
        }
    }

    private fun dp(value: Int): Int = (value * windowContext.resources.displayMetrics.density).toInt()

    companion object { private const val TAG = "FloatingMirror" }
}
