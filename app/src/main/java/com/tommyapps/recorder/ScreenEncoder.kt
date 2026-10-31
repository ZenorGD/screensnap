package com.tommyapps.recorder

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class ScreenEncoder(
    private val projection: MediaProjection,
    private val muxer: MediaMuxer,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val useMic: Boolean,
    private val useDevice: Boolean
) {
    private val rate = 44100
    private val video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private var audio: MediaCodec? = null
    private var micRec: AudioRecord? = null
    private var devRec: AudioRecord? = null
    private var display: VirtualDisplay? = null
    private var vThread: Thread? = null
    private var aThread: Thread? = null

    private val muxLock = Any()
    private var tracksAdded = 0
    @Volatile private var expectedTracks = 1
    @Volatile private var started = false

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var needKeyFrame = false
    @Volatile private var pausedTotalUs = 0L
    private var pauseStartUs = 0L
    private var t0 = 0L

    @Volatile var failure: String? = null
        private set

    private fun nowUs() = System.nanoTime() / 1000

    fun start() {
        t0 = nowUs()

        if (useMic) micRec = makeMic()
        if (useDevice) devRec = makeDevice()
        val hasAudio = micRec != null || devRec != null
        if (hasAudio) {
            expectedTracks = 2
            val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                it.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
            micRec?.startRecording()
            devRec?.startRecording()
        }

        val vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps) // steady FPS on static screens
            setFloat("max-fps-to-encoder", fps.toFloat())                          // caps FPS (Android 10+)
        }
        video.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = video.createInputSurface()
        video.start()
        display = projection.createVirtualDisplay(
            "ScreenSnap", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )

        running = true
        vThread = Thread { videoLoop() }.also { it.start() }
        if (hasAudio) aThread = Thread { audioLoop() }.also { it.start() }
    }

    @SuppressLint("MissingPermission")
    private fun makeMic(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16384)
        )
        return if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null }
    }

    @SuppressLint("MissingPermission")
    private fun makeDevice(): AudioRecord? {
        if (Build.VERSION.SDK_INT < 29) return null
        val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val r = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(cfg)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(32768)
            .build()
        return if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null }
    }

    /* ---------- muxer helpers ---------- */
    private fun addTrack(f: MediaFormat): Int = synchronized(muxLock) {
        val i = muxer.addTrack(f)
        tracksAdded++
        if (tracksAdded >= expectedTracks && !started) { muxer.start(); started = true }
        i
    }

    private fun dropAudioTrack() {
        synchronized(muxLock) {
            if (expectedTracks == 2) {
                expectedTracks = 1
                if (!started && tracksAdded >= 1) { muxer.start(); started = true }
            }
        }
    }

    private fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        while (!started && running) Thread.sleep(2)
        if (started) synchronized(muxLock) { muxer.writeSampleData(track, buf, info) }
    }

    /* ---------- video thread ---------- */
    private fun videoLoop() {
        val info = MediaCodec.BufferInfo()
        var track = -1
        var lastPts = -1L
        var base = Long.MIN_VALUE
        var idle = 0
        try {
            while (true) {
                val i = video.dequeueOutputBuffer(info, 10_000)
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = addTrack(video.outputFormat)
                } else if (i >= 0) {
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (!config && info.size > 0 && track >= 0 && !paused) {
                        val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        if (key) needKeyFrame = false
                        if (!needKeyFrame) {
                            if (base == Long.MIN_VALUE) {
                                val now = nowUs()
                                base = if (abs(info.presentationTimeUs - now) < 10_000_000) t0
                                else info.presentationTimeUs - (now - t0)
                            }
                            var pts = info.presentationTimeUs - base - pausedTotalUs
                            if (pts < 0) pts = 0
                            if (pts <= lastPts) pts = lastPts + 1
                            lastPts = pts
                            info.presentationTimeUs = pts
                            val buf = video.getOutputBuffer(i)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            write(track, buf, info)
                        }
                    }
                    video.releaseOutputBuffer(i, false)
                    if (eos) break
                } else if (i == MediaCodec.INFO_TRY_AGAIN_LATER && !running && ++idle > 150) {
                    break
                }
            }
        } catch (e: Exception) {
            failure = "Video: ${e.message}"
        }
    }

    /* ---------- audio thread (mic and/or device audio, mixed) ---------- */
    private fun audioLoop() {
        val enc = audio ?: return
        val info = MediaCodec.BufferInfo()
        val stereo = ShortArray(2048)
        val mono = ShortArray(1024)
        var track = -1
        var lastPts = -1L
        var anchorPending = true
        var anchor = 0L
        var samples = 0L
        var eos = false

        fun drain(wait: Boolean): Boolean {
            while (true) {
                val o = enc.dequeueOutputBuffer(info, if (wait) 10_000 else 0)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track = addTrack(enc.outputFormat)
                    o >= 0 -> {
                        val end = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        val config = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        if (!config && info.size > 0 && track >= 0) {
                            var pts = info.presentationTimeUs
                            if (pts <= lastPts) pts = lastPts + 1
                            lastPts = pts
                            info.presentationTimeUs = pts
                            val buf = enc.getOutputBuffer(o)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            write(track, buf, info)
                        }
                        enc.releaseOutputBuffer(o, false)
                        if (end) return true
                    }
                    else -> return false
                }
            }
        }

        try {
            while (running) {
                val dev = devRec
                val mic = micRec
                val devN = if (dev != null) maxOf(dev.read(stereo, 0, stereo.size), 0) / 2 else 0
                val micN = if (mic != null) maxOf(mic.read(mono, 0, mono.size), 0) else 0
                val frames = maxOf(devN, micN)
                if (frames == 0) { Thread.sleep(5); continue }
                if (paused) { anchorPending = true; continue }   // keep reading, throw the audio away

                for (k in 0 until frames) {
                    val d0 = if (k < devN) stereo[2 * k].toInt() else 0
                    val d1 = if (k < devN) stereo[2 * k + 1].toInt() else 0
                    val m = if (k < micN) mono[k].toInt() else 0
                    stereo[2 * k] = (d0 + m).coerceIn(-32768, 32767).toShort()
                    stereo[2 * k + 1] = (d1 + m).coerceIn(-32768, 32767).toShort()
                }
                if (anchorPending) {
                    anchor = maxOf(0L, nowUs() - t0 - pausedTotalUs - frames * 1_000_000L / rate)
                    samples = 0
                    anchorPending = false
                }
                val pts = anchor + samples * 1_000_000L / rate
                samples += frames

                val idx = enc.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    val ib = enc.getInputBuffer(idx)!!
                    ib.clear()
                    ib.order(ByteOrder.LITTLE_ENDIAN)
                    ib.asShortBuffer().put(stereo, 0, frames * 2)
                    enc.queueInputBuffer(idx, 0, frames * 4, pts, 0)
                }
                drain(false)
            }
            // finish: send end-of-stream and flush
            var idx = -1
            repeat(20) { if (idx < 0) idx = enc.dequeueInputBuffer(50_000) }
            if (idx >= 0) enc.queueInputBuffer(idx, 0, 0, nowUs() - t0 - pausedTotalUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            var tries = 0
            while (!eos && tries++ < 200) eos = drain(true)
        } catch (e: Exception) {
            failure = "Audio: ${e.message}"
            dropAudioTrack()
        }
    }

    /* ---------- controls ---------- */
    fun pause() {
        if (paused) return
        pauseStartUs = nowUs()
        paused = true
    }

    fun resume() {
        if (!paused) return
        pausedTotalUs += nowUs() - pauseStartUs
        needKeyFrame = true
        try {
            video.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {}
        paused = false
    }

    fun stop() {
        running = false
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { video.signalEndOfInputStream() } catch (_: Exception) {}
        vThread?.join(3000)
        aThread?.join(4000)
        try { video.stop() } catch (_: Exception) {}
        try { video.release() } catch (_: Exception) {}
        try { audio?.stop() } catch (_: Exception) {}
        try { audio?.release() } catch (_: Exception) {}
        listOf(micRec, devRec).forEach {
            try { it?.stop() } catch (_: Exception) {}
            try { it?.release() } catch (_: Exception) {}
        }
        try { if (started) muxer.stop() } catch (e: Exception) { failure = failure ?: "Save: ${e.message}" }
        try { muxer.release() } catch (_: Exception) {}
    }
}