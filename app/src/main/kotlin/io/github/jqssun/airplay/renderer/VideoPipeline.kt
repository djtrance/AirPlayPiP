package io.github.jqssun.airplay.renderer

import android.graphics.SurfaceTexture
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.SystemClock
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

// decodes into an app-owned SurfaceTexture that outlives display surface
// gl thread blits it to any surface attached, so fullscreen toggles re-point display without restarting codec
class VideoPipeline {

    private val lock = Object()
    private var thread: Thread? = null
    @Volatile private var running = false

    private var egl: EglCore? = null
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var winW = 0
    private var winH = 0

    private var oesTex = 0
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uTexMatrix = 0
    private val texMatrix = FloatArray(16)
    private var hasFrame = false
    @Volatile var displayedFrames = 0L; private set
    @Volatile var consumedFrames = 0L; private set
    data class SoftwareFrame(val bitmap: Bitmap, val sequence: Long)
    @Volatile var softwareFrame: SoftwareFrame? = null; private set
    @Volatile private var softwareEnabled = false
    private var softwareSequence = 0L
    private var snapshotFbo = 0
    private var snapshotTexture = 0
    private var snapshotWidth = 0
    private var snapshotHeight = 0
    private var snapshotBuffer: ByteBuffer? = null
    private var lastSnapshotAt = 0L
    private var nextBindAt = 0L

    private var surfaceTexture: SurfaceTexture? = null
    private var frameEvents: HandlerThread? = null
    var inputSurface: Surface? = null; private set

    @Volatile private var frameAvailable = false
    private var pendingDisplay: Surface? = null
    private var displayDirty = false
    @Volatile private var videoW = 0
    @Volatile private var videoH = 0

    fun start() = synchronized(lock) {
        if (running) return@synchronized
        check(thread?.isAlive != true) { "Previous GPU worker has not stopped; refusing a second pipeline" }
        running = true
        thread = Thread({ _loop() }, "VideoPipeline").also { it.start() }
        while (inputSurface == null && running) lock.wait()
    }

    fun setDisplaySurface(surface: Surface?) = synchronized(lock) {
        pendingDisplay = surface
        displayDirty = true
        lock.notifyAll()
    }

    fun setSoftwareOutputEnabled(enabled: Boolean) {
        softwareEnabled = enabled
        if (!enabled) softwareFrame = null
    }

    fun setVideoSize(w: Int, h: Int) {
        videoW = w
        videoH = h
        surfaceTexture?.setDefaultBufferSize(w, h)
    }

    fun release() {
        synchronized(lock) {
            if (!running) return
            running = false
            lock.notifyAll()
        }
        val worker = thread
        worker?.join(1_500)
        if (worker?.isAlive == true) Log.e(TAG, "GPU worker did not stop within 1500 ms; no second pipeline will be started")
        else thread = null
    }

    private fun _loop() {
        try {
            egl = EglCore()
            _initGl()
            hasFrame = false
            Matrix.setIdentityM(texMatrix, 0)
            synchronized(lock) {
                inputSurface = Surface(surfaceTexture)
                displayDirty = true // Reattach the current output after a pipeline restart too.
                lock.notifyAll()
            }
            while (true) {
                var newDisplay: Surface? = null
                var displayChanged = false
                var doFrame = false
                synchronized(lock) {
                    while (running && !frameAvailable && !displayDirty &&
                        !(pendingDisplay != null && window == EGL14.EGL_NO_SURFACE)) lock.wait()
                    if (running && !frameAvailable && !displayDirty && window == EGL14.EGL_NO_SURFACE &&
                        SystemClock.elapsedRealtime() < nextBindAt) lock.wait(250)
                    if (running && (displayDirty || (window == EGL14.EGL_NO_SURFACE &&
                            pendingDisplay != null && SystemClock.elapsedRealtime() >= nextBindAt))) {
                        newDisplay = pendingDisplay
                        displayChanged = true
                        displayDirty = false
                    }
                    if (running && frameAvailable) {
                        frameAvailable = false
                        doFrame = true
                    }
                }
                if (!running) break
                if (displayChanged) _bindDisplay(newDisplay)
                if (doFrame) _consumeAndDraw()
            }
        } catch (e: Exception) {
            Log.e(TAG, "GL pipeline failed (including possible context loss)", e)
        } finally {
            try {
                _releaseGl()
            } finally {
                synchronized(lock) {
                    running = false
                    lock.notifyAll()
                }
            }
        }
    }

    private fun _bindDisplay(surface: Surface?) {
        val egl = egl ?: return
        _dropOutput()
        nextBindAt = SystemClock.elapsedRealtime() + 250
        if (surface == null || !surface.isValid) return
        window = egl.windowSurface(surface)
        if (window == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "eglCreateWindowSurface failed: ${EGL14.eglGetError()}")
            return
        }
        if (!egl.makeCurrent(window)) {
            _outputFailed("eglMakeCurrent")
            return
        }
        winW = egl.query(window, EGL14.EGL_WIDTH)
        winH = egl.query(window, EGL14.EGL_HEIGHT)
        // an idle source sends no new frames, so repaint last one or new surface stays black
        if (hasFrame) _render()
    }

    private fun _consumeAndDraw() {
        val st = surfaceTexture ?: return
        val egl = egl ?: return
        if (window != EGL14.EGL_NO_SURFACE && !egl.makeCurrent(window)) {
            _outputFailed("eglMakeCurrent")
        }
        // Keep the persistent decoder input draining even if HDMI invalidated the output window.
        if (window == EGL14.EGL_NO_SURFACE) check(egl.makeCurrent()) { "EGL input context unavailable" }
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        consumedFrames++
        hasFrame = true
        if (softwareEnabled) _captureSoftwareFrame()
        if (window != EGL14.EGL_NO_SURFACE) _render()
    }

    private fun _captureSoftwareFrame() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSnapshotAt < 100 || videoW <= 0 || videoH <= 0) return // <= 10 FPS, <= 480 px per edge.
        lastSnapshotAt = now
        val scale = minOf(1f, 480f / maxOf(videoW, videoH))
        val width = (videoW * scale).toInt().coerceAtLeast(1)
        val height = (videoH * scale).toInt().coerceAtLeast(1)
        if (snapshotFbo == 0 || snapshotWidth != width || snapshotHeight != height) {
            _releaseSnapshotTarget()
            val id = IntArray(1)
            GLES20.glGenTextures(1, id, 0)
            snapshotTexture = id[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, snapshotTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glGenFramebuffers(1, id, 0)
            snapshotFbo = id[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, snapshotFbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, snapshotTexture, 0)
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.e(TAG, "CPU compatibility readback framebuffer unavailable")
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                _releaseSnapshotTarget()
                return
            }
            snapshotWidth = width
            snapshotHeight = height
            snapshotBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        }
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, snapshotFbo)
            GLES20.glViewport(0, 0, width, height)
            _drawTexture()
            val pixels = snapshotBuffer ?: return
            pixels.clear()
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                Log.w(TAG, "CPU compatibility readback failed")
                return
            }
            pixels.rewind()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(pixels)
            if (softwareEnabled) softwareFrame = SoftwareFrame(bitmap, ++softwareSequence)
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
    }

    private fun _releaseSnapshotTarget() {
        if (snapshotFbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(snapshotFbo), 0)
        if (snapshotTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(snapshotTexture), 0)
        snapshotFbo = 0
        snapshotTexture = 0
        snapshotBuffer = null
    }

    private fun _dropOutput() {
        val core = egl ?: return
        core.makeCurrent()
        if (window != EGL14.EGL_NO_SURFACE) core.destroySurface(window)
        window = EGL14.EGL_NO_SURFACE
    }

    private fun _outputFailed(operation: String) {
        val error = EGL14.eglGetError()
        Log.w(TAG, "$operation failed: EGL 0x${error.toString(16)}; rebinding output without restarting decoder")
        _dropOutput()
        nextBindAt = SystemClock.elapsedRealtime() + 250
        check(error != EGL14.EGL_CONTEXT_LOST) { "EGL context lost" }
    }

    private fun _render() {
        // A TV input/mode change can resize the same native window without replacing its Surface.
        winW = egl?.query(window, EGL14.EGL_WIDTH) ?: 0
        winH = egl?.query(window, EGL14.EGL_HEIGHT) ?: 0
        if (winW <= 0 || winH <= 0) {
            _outputFailed("eglQuerySurface")
            return
        }
        GLES20.glViewport(0, 0, winW, winH)
        _drawTexture()
        if (egl?.swap(window) == true) displayedFrames++
        else _outputFailed("eglSwapBuffers")
    }

    private fun _drawTexture() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, POS)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, TEX)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }

    private fun _initGl() {
        program = _buildProgram()
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aTex = GLES20.glGetAttribLocation(program, "aTex")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        oesTex = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val events = HandlerThread("AirPlayFrameEvents").also { it.start() }
        frameEvents = events
        surfaceTexture = SurfaceTexture(oesTex).also { texture ->
            if (videoW > 0 && videoH > 0) texture.setDefaultBufferSize(videoW, videoH)
            // Never route decoder frame notifications through the Activity/main UI Looper.
            texture.setOnFrameAvailableListener({ source ->
                if (surfaceTexture === source) synchronized(lock) { frameAvailable = true; lock.notifyAll() }
            }, Handler(events.looper))
        }
    }

    private fun _buildProgram(): Int {
        val vs = _shader(GLES20.GL_VERTEX_SHADER, VERT)
        val fs = _shader(GLES20.GL_FRAGMENT_SHADER, FRAG)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
        }
    }

    private fun _shader(type: Int, src: String): Int {
        return GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "shader compile failed: ${GLES20.glGetShaderInfoLog(it)}")
        }
    }

    private fun _releaseGl() {
        _releaseSnapshotTarget()
        softwareFrame = null
        surfaceTexture?.setOnFrameAvailableListener(null)
        surfaceTexture?.release()
        surfaceTexture = null
        frameEvents?.quitSafely()
        frameEvents = null
        inputSurface?.release()
        inputSurface = null
        egl?.let {
            if (window != EGL14.EGL_NO_SURFACE) it.destroySurface(window)
            it.close()
        }
        egl = null
        window = EGL14.EGL_NO_SURFACE
    }

    companion object {
        private const val TAG = "VideoPipeline"

        private val POS = _fb(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        private val TEX = _fb(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

        private fun _fb(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(a); position(0) }

        private const val VERT =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aTex;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "  vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;\n" +
            "}\n"

        private const val FRAG =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "uniform samplerExternalOES sTex;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTex, vTex);\n" +
            "}\n"
    }
}
