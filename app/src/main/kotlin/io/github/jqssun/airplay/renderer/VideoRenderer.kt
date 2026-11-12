package io.github.jqssun.airplay.renderer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.os.SystemClock
import android.view.Surface
import io.github.jqssun.airplay.renderer.DecoderSelector.Companion.videoCaps

class VideoRenderer(ctx: Context) {

    private val lock = Object()
    private val pipeline = VideoPipeline()
    val displayedFrames: Long get() = pipeline.displayedFrames
    val consumedFrames: Long get() = pipeline.consumedFrames
    val softwareFrame: VideoPipeline.SoftwareFrame? get() = pipeline.softwareFrame
    fun setSoftwareOutputEnabled(enabled: Boolean) = pipeline.setSoftwareOutputEnabled(enabled)
    val selector = DecoderSelector(ctx)
    private var avcDecoder: MediaCodecInfo? = null
    private var hevcDecoder: MediaCodecInfo? = null
    private var maxFps = 0
    private var codec: MediaCodec? = null
    private var displaySurface: Surface? = null
    private var currentH265 = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var firstFrameQueued = false
    private var forceSoftware = false
    private var forceHardware = false
    private var pipSoftwareDecoder = false
    @Volatile private var incomingH265: Boolean? = null
    @Volatile private var operation = "idle"
    @Volatile private var codecStartedAt = 0L
    @Volatile private var lastDecodedAt = 0L
    @Volatile private var errorAt = 0L
    @Volatile private var generation = 0L
    @Volatile private var generationInput = 0L
    @Volatile private var generationReferences = 0L
    var decoderEventCallback: ((String) -> Unit)? = null
    @Volatile var acceptedInputFrames = 0L; private set
    @Volatile var decoderErrors = 0L; private set
    @Volatile var decoderState = "idle"; private set
    @Volatile private var selectedDecoder = "unselected"
    @Volatile private var decoderMode = "auto"
    @Volatile private var lastDecoderError = ""

    fun decoderDiagnostic(): String {
        val now = SystemClock.elapsedRealtime()
        val state = when {
            decoderState == "started" && now - codecStartedAt >= 4_000 -> "started_no_output"
            decoderState == "decoding" && now - lastDecodedAt >= 4_000 -> "stalled_after_output"
            else -> decoderState
        }
        return "decoder=$decoderMode · $selectedDecoder\n${videoWidth}x${videoHeight} @${maxFps} · state=$state" +
            "\nIN=$acceptedInputFrames gen=$generation inGen=$generationInput IDRg=$generationReferences ERR=$decoderErrors op=$operation" +
            if (lastDecoderError.isNotBlank()) "\nlast error ${(now - errorAt).coerceAtLeast(0) / 1000}s ago: ${lastDecoderError.take(240)}" else ""
    }

    // stats
    @Volatile var fps = 0; private set
    @Volatile var bitrateBps = 0L; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var decodedFrames = 0L; private set
    @Volatile var codecName = ""; private set
    @Volatile var droppedFrames = 0L; private set
    @Volatile var framePacingJitterUs = 0L; private set

    var enforceSdr = true
    var keyAllowFrameDrop = true
    var scheduledOutputBufferRelease = true
    var benchmarkLog = false
    var benchmarkLogCallback: ((String) -> Unit)? = null
    private var _framesThisSec = 0
    private var _bytesThisSec = 0L
    private var _lastStatReset = 0L
    private val _frameIntervalsNs = LongArray(120)
    private var _frameIntervalIdx = 0
    private var _frameIntervalCount = 0
    private var _lastOutputFrameNs = 0L
    // anchors that map decoder PTS (us) to System.nanoTime() for scheduled rendering
    private var _ptsBaseUs = Long.MIN_VALUE
    private var _wallBaseNs = 0L

    fun setResolution(w: Int, h: Int) = synchronized(lock) {
        if (w == videoWidth && h == videoHeight) return@synchronized
        videoWidth = w
        videoHeight = h
        pipeline.setVideoSize(w, h)
        stopCodec()
    }

    // doesn't restart codec; decoder renders into pipeline's own persistent surface
    fun setSurface(surface: Surface) = synchronized(lock) {
        displaySurface = surface
        pipeline.setDisplaySurface(surface)
    }

    fun clearSurface(surface: Surface) = synchronized(lock) {
        if (displaySurface !== surface) return@synchronized
        displaySurface = null
        pipeline.setDisplaySurface(null)
    }

    fun selectDecoders(w: Int, h: Int, fps: Int, h265: Boolean?, software: Boolean = false,
                       hardwareCompatibility: Boolean = false): Boolean = synchronized(lock) {
        require(!(software && hardwareCompatibility))
        forceSoftware = software
        forceHardware = hardwareCompatibility
        pipSoftwareDecoder = false
        decoderMode = if (software) "software" else if (hardwareCompatibility) "hardware_compat" else "auto"
        decoderState = "selecting"
        avcDecoder = when {
            software -> selector.software(DecoderSelector.AVC, w, h)
            hardwareCompatibility -> selector.hardwareAvc(w, h)
            else -> selector.avc()
        }
        selectedDecoder = avcDecoder?.name ?: "unavailable"
        check(!software || avcDecoder != null) { "No software H.264 decoder supports ${w}x${h}; hardware fallback is disabled" }
        check(!hardwareCompatibility || avcDecoder != null) { "No hardware H.264 decoder supports ${w}x${h}; software fallback is disabled" }
        hevcDecoder = if (!software && !hardwareCompatibility && h265 != false) selector.hevc(avcDecoder, w, h, fps, force = h265 == true) else null
        maxFps = fps
        decoderState = "waiting_idr"
        Log.i(TAG, "decoders: mode=$decoderMode avc=${avcDecoder?.name} hevc=${hevcDecoder?.name}")
        hevcDecoder != null
    }

    // unknown limits default to 1080p
    fun maxResolution(): Pair<Int, Int> =
        listOfNotNull(avcDecoder?.let { it to DecoderSelector.AVC }, hevcDecoder?.let { it to DecoderSelector.HEVC })
            .map { (info, mime) ->
                runCatching { info.videoCaps(mime).let { it.supportedWidths.upper to it.supportedHeights.upper } }
                    .getOrDefault(1920 to 1080)
            }
            .reduceOrNull { (w1, h1), (w2, h2) -> minOf(w1, w2) to minOf(h1, h2) } ?: (1920 to 1080)

    // codec per mirror session; pipeline persists across sessions
    fun startSession() = synchronized(lock) { _resetStats() }

    fun stopSession() = synchronized(lock) { stopCodec() }

    private fun _resetStats() {
        fps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        decodedFrames = 0; acceptedInputFrames = 0; decoderErrors = 0; lastDecoderError = ""
        generation = 0; generationInput = 0; generationReferences = 0
        decoderState = if (codec == null) "waiting_idr" else "started"
        droppedFrames = 0; framePacingJitterUs = 0
        _framesThisSec = 0; _bytesThisSec = 0
    }

    private fun _updateStats(size: Int) {
        val now = System.currentTimeMillis()
        if (now - _lastStatReset >= 1000) {
            fps = _framesThisSec
            bitrateBps = _bytesThisSec * 8
            framePacingJitterUs = _computeFramePacingJitterUs()
            _framesThisSec = 0
            _bytesThisSec = 0
            _lastStatReset = now
            if (benchmarkLog) _emitBenchmarkLine()
        }
        _framesThisSec++
        _bytesThisSec += size
        frameCount++
    }

    private fun _emitBenchmarkLine() {
        val msg = "fps=$fps bitrate=${bitrateBps / 1000}kbps " +
            "jitter=${framePacingJitterUs}us frames=$frameCount " +
            "dropped=$droppedFrames codec=$codecName " +
            "res=${videoWidth}x${videoHeight}"
        Log.i(BENCH_TAG, msg)
        benchmarkLogCallback?.invoke(msg)
    }

    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        synchronized(lock) {
            _updateStats(data.size)
            incomingH265 = isH265
            if (videoWidth == 0 || videoHeight == 0) return

            if (codec == null || isH265 != currentH265) {
                // a stale reference frame decodes to corruption, so wait for a keyframe to (re)start
                if (!_isKeyframe(data, isH265)) {
                    if (codec != null) stopCodec()
                    decoderState = "waiting_idr"
                    return
                }
                stopCodec()
            }

            try {
                if (codec == null) startCodec(isH265)
                _feedToCodec(data, ntpTimeNs)
                drainOutput()
            } catch (e: Exception) {
                decoderErrors++
                errorAt = SystemClock.elapsedRealtime()
                val details = if (e is MediaCodec.CodecException) {
                    "${e.diagnosticInfo} code=${e.errorCode} recoverable=${e.isRecoverable} transient=${e.isTransient}"
                } else ""
                lastDecoderError = DecoderFailure.summary(e, operation, selectedDecoder, details)
                decoderState = "failed_waiting_idr"
                Log.w(TAG, "Codec error: $lastDecoderError", e)
                decoderEventCallback?.invoke("Codec error: $lastDecoderError\n${Log.getStackTraceString(e)}")
                stopCodec()
            }
        }
    }

    private fun _feedToCodec(data: ByteArray, ntpTimeNs: Long) {
        val c = codec ?: return
        // dropping a frame desyncs decoder until the next keyframe, but source would only send one on (re)connect
        val retries = if (firstFrameQueued) FEED_RETRIES else FIRST_FEED_RETRIES
        repeat(retries) {
            operation = "dequeue_input"
            val idx = c.dequeueInputBuffer(FEED_WAIT_US)
            if (idx >= 0) {
                operation = "get_input"
                val buf = c.getInputBuffer(idx) ?: return
                operation = "fill_input"
                buf.clear()
                buf.put(data)
                operation = "queue_input"
                c.queueInputBuffer(idx, 0, data.size, ntpTimeNs / 1000, 0)
                acceptedInputFrames++
                generationInput++
                if (AnnexB.hasRandomAccessPicture(data, currentH265)) generationReferences++
                firstFrameQueued = true
                return
            }
            drainOutput()
        }
        droppedFrames++
        Log.w(TAG, "Decoder input queue full; dropping frame. drops=$droppedFrames")
    }

    private fun _isKeyframe(data: ByteArray, isH265: Boolean): Boolean = AnnexB.canBootstrap(data, isH265)

    private fun startCodec(h265: Boolean) {
        operation = "validate_stream"
        val softwareForSession = forceSoftware || pipSoftwareDecoder
        check(!(softwareForSession || forceHardware) || !h265) { "Selected decoder profile requires a fresh H.264 connection; reconnect mirroring" }
        operation = "pipeline_start"
        pipeline.start()
        pipeline.setVideoSize(videoWidth, videoHeight)
        val s = pipeline.inputSurface ?: return
        currentH265 = h265
        val mime = if (h265) DecoderSelector.HEVC else DecoderSelector.AVC
        operation = "select_component"
        val info = (if (softwareForSession) selector.software(mime, videoWidth, videoHeight)
            else if (forceHardware) selector.hardwareAvc(videoWidth, videoHeight)
            else if (h265) hevcDecoder else avcDecoder) ?: error("no $decoderMode decoder for $mime ${videoWidth}x${videoHeight}")
        selectedDecoder = info.name

        firstFrameQueued = false
        try {
            _startWithLadder(info, mime, s, h265)
        } catch (e: Exception) {
            if (softwareForSession || forceHardware) throw e // Strict modes must not change decoder type silently.
            // strict hw decoders reject configs beyond their real limits
            val sw = selector.software(mime, videoWidth, videoHeight) ?: throw e
            Log.w(TAG, "Hardware decoder failed, trying software fallback", e)
            _startWithLadder(sw, mime, s, h265)
        }
        val started = "Video codec started: mode=$decoderMode $mime ${videoWidth}x${videoHeight} ($codecName) conservative=${VideoDecoderPolicy.conservativeHardware(decoderMode, info.name)}"
        Log.i(TAG, started)
        decoderEventCallback?.invoke(started)
    }

    /** Switches on the next sender IDR without restarting RAOP/audio or fabricating a frame. */
    fun setPipSoftwareDecoder(enabled: Boolean): Boolean = synchronized(lock) {
        if (forceSoftware || forceHardware) return@synchronized !enabled || forceSoftware
        if (pipSoftwareDecoder == enabled) return@synchronized true
        if (enabled) {
            val h265 = incomingH265 ?: currentH265
            val mime = if (h265) DecoderSelector.HEVC else DecoderSelector.AVC
            if (videoWidth <= 0 || videoHeight <= 0 || selector.software(mime, videoWidth, videoHeight) == null) {
                val message = "No software decoder for active PiP stream $mime ${videoWidth}x${videoHeight}; keeping configured decoder"
                Log.w(TAG, message)
                decoderEventCallback?.invoke(message)
                return@synchronized false
            }
        }
        pipSoftwareDecoder = enabled
        decoderMode = if (pipSoftwareDecoder) "software_pip" else "auto"
        stopCodec()
        decoderState = "waiting_idr"
        val message = "PiP decoder mode changed to $decoderMode; waiting for sender IDR/IRAP"
        Log.i(TAG, message)
        decoderEventCallback?.invoke(message)
        true
    }

    private fun _startWithLadder(info: MediaCodecInfo, mime: String, s: Surface, h265: Boolean) {
        selectedDecoder = info.name
        if (selector.isSoftware(info) || VideoDecoderPolicy.conservativeHardware(decoderMode, info.name)) {
            // Plain MediaCodec config for software and Realtek; no blind vendor latency keys.
            operation = "build_format"
            val format = _format(mime, info)
            operation = "create_codec"
            val c = MediaCodec.createByCodecName(info.name)
            _startDecoder(c, format, s, h265)
            return
        }
        var tryNum = 0
        while (true) {
            val format = _format(mime, info)
            val more = selector.lowLatencyOptions(format, info, mime, tryNum)
            try {
                operation = "create_codec"
                val c = MediaCodec.createByCodecName(info.name)
                _startDecoder(c, format, s, h265)
                return
            } catch (e: Exception) {
                if (!more) throw e
                Log.w(TAG, "configure try $tryNum failed: $format", e)
                tryNum++
            }
        }
    }

    private fun _format(mime: String, info: MediaCodecInfo) = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight).apply {
        setInteger(MediaFormat.KEY_FRAME_RATE, maxFps)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(videoWidth * videoHeight * 3 / 4, 1024 * 1024))
        if (VideoDecoderPolicy.conservativeHardware(decoderMode, info.name)) return@apply
        if (!selector.isSoftware(info) && selector.adaptive(info, mime)) {
            setInteger(MediaFormat.KEY_MAX_WIDTH, videoWidth)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, videoHeight)
        }
        if (enforceSdr) {
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        if (!selector.isSoftware(info) && android.os.Build.VERSION.SDK_INT >= 29) {
            setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, if (keyAllowFrameDrop) 1 else 0)
        }
    }

    private fun _startDecoder(c: MediaCodec, format: MediaFormat, surface: Surface, h265: Boolean) {
        try {
            operation = "configure"
            c.configure(format, surface, null, 0)
            operation = "start"
            c.start()
        } catch (e: Exception) {
            try { c.release() } catch (_: Exception) {}
            throw e
        }
        codec = c
        selectedDecoder = c.name
        decoderState = "started"
        codecStartedAt = SystemClock.elapsedRealtime()
        lastDecodedAt = 0
        generation++
        generationInput = 0
        generationReferences = 0
        codecName = (if (h265) "H.265" else "H.264") + " (${c.name})"
    }

    private fun stopCodec() {
        _frameIntervalIdx = 0
        _frameIntervalCount = 0
        _lastOutputFrameNs = 0L
        _ptsBaseUs = Long.MIN_VALUE
        _wallBaseNs = 0L
        codec?.let {
            try {
                operation = "stop"
                it.stop()
            } catch (_: Exception) { } finally {
                operation = "release"
                try { it.release() } catch (_: Exception) { }
            }
        }
        codec = null
        if (decoderState != "failed_waiting_idr") decoderState = "waiting_idr"
    }

    private fun drainOutput() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            operation = "dequeue_output"
            val idx = c.dequeueOutputBuffer(info, 0)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
            if (idx < 0) break
            decodedFrames++
            decoderState = "decoding"
            lastDecodedAt = SystemClock.elapsedRealtime()
            _recordOutputFrameTime()
            if (scheduledOutputBufferRelease) {
                // schedule frame at VSYNC matching its NTP presentation time
                val ptsUs = info.presentationTimeUs
                if (_ptsBaseUs == Long.MIN_VALUE) {
                    _ptsBaseUs = ptsUs
                    _wallBaseNs = System.nanoTime()
                }
                operation = "release_output_timed"
                c.releaseOutputBuffer(idx, _wallBaseNs + (ptsUs - _ptsBaseUs) * 1000L)
            } else {
                operation = "release_output"
                c.releaseOutputBuffer(idx, true)
            }
        }
    }

    fun release() = synchronized(lock) {
        stopCodec()
        pipeline.release()
        _resetStats()
    }

    private fun _recordOutputFrameTime() {
        val now = System.nanoTime()
        if (_lastOutputFrameNs > 0) {
            _frameIntervalsNs[_frameIntervalIdx % _frameIntervalsNs.size] = now - _lastOutputFrameNs
            _frameIntervalIdx++
            _frameIntervalCount++
        }
        _lastOutputFrameNs = now
    }

    private fun _computeFramePacingJitterUs(): Long {
        val count = _frameIntervalCount.coerceAtMost(_frameIntervalsNs.size)
        if (count < 2) return 0

        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until count) {
            val interval = _frameIntervalsNs[i].toDouble()
            sum += interval
            sumSq += interval * interval
        }
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return (kotlin.math.sqrt(variance.coerceAtLeast(0.0)) / 1000.0).toLong()
    }

    companion object {
        private const val TAG = "VideoRenderer"
        private const val BENCH_TAG = "BENCHMARK"
        private const val FEED_WAIT_US = 20_000L
        private const val FEED_RETRIES = 10
        private const val FIRST_FEED_RETRIES = 50
    }
}
