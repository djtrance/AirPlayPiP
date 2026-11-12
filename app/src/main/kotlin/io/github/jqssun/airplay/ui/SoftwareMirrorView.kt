package io.github.jqssun.airplay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import io.github.jqssun.airplay.renderer.VideoPipeline

/** CPU Canvas output, not another MediaCodec or SurfaceView/TextureView video layer. */
class SoftwareMirrorView(context: Context, private val latest: () -> VideoPipeline.SoftwareFrame?) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var lastSequence = -1L
    var drawnFrames = 0L; private set
    private val tick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            invalidate()
            postDelayed(this, 100)
        }
    }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        if (width <= 0 || height <= 0) return
        val frame = latest() ?: return
        val bitmap = frame.bitmap
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val left = (width - bitmap.width * scale) / 2f
        val bottom = (height + bitmap.height * scale) / 2f
        canvas.save()
        canvas.translate(left, bottom)
        canvas.scale(scale, -scale) // glReadPixels starts at the lower left; Bitmap rows start at the top.
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        canvas.restore()
        if (frame.sequence != lastSequence) {
            lastSequence = frame.sequence
            drawnFrames++
        }
    }
}
