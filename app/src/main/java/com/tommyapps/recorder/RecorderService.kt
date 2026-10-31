package com.tommyapps.recorder

import android.app.*
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecorderService : Service() {
    companion object {
        const val IDLE = 0
        const val RECORDING = 1
        const val PAUSED = 2
        const val ACTION_START = "com.tommyapps.recorder.START"
        const val ACTION_STOP = "com.tommyapps.recorder.STOP"
        const val ACTION_PAUSE = "com.tommyapps.recorder.PAUSE"
        const val ACTION_RESUME = "com.tommyapps.recorder.RESUME"
        val state = MutableStateFlow(IDLE)
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 1
    }

    private var projection: MediaProjection? = null
    private var encoder: ScreenEncoder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var pendingUri: Uri? = null
    private var oldTouches = 0
    private var touchesChanged = false
    private var finishing = false

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); Prefs.init(this) }
    override fun onDestroy() { if (state.value != IDLE) stopRecording(); super.onDestroy() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_PAUSE -> if (state.value == RECORDING) { encoder?.pause(); state.value = PAUSED; refreshNotification() }
            ACTION_RESUME -> if (state.value == PAUSED) { encoder?.resume(); state.value = RECORDING; refreshNotification() }
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (state.value != IDLE) return
        finishing = false

        val micOk = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val src = Prefs.audio
        val wantMic = micOk && (src == 1 || src == 3)
        val wantDev = micOk && (src == 2 || src == 3) && Build.VERSION.SDK_INT >= 29
        if (src != 0 && !micOk) toast("Microphone permission missing, recording without audio")
        else if ((src == 2 || src == 3) && Build.VERSION.SDK_INT < 29) toast("Device audio needs Android 10 or newer")

        val type = if (Build.VERSION.SDK_INT >= 29) {
            var t = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (wantMic && Build.VERSION.SDK_INT >= 30) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            t
        } else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)

        try {
            val code = intent.getIntExtra("code", 0)
            val data = IntentCompat.getParcelableExtra(intent, "data", Intent::class.java)!!
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val proj = mpm.getMediaProjection(code, data)!!
            projection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopRecording() }
            }, Handler(Looper.getMainLooper()))

            val (w, h, dpi) = captureSize()
            val enc = ScreenEncoder(proj, createMuxer(), w, h, dpi, Prefs.fps, Prefs.bitrate * 1_000_000, wantMic, wantDev)
            encoder = enc
            enc.start()

            state.value = RECORDING
            applyTouches(true)
            if (Prefs.overlay && Settings.canDrawOverlays(this)) startService(Intent(this, OverlayService::class.java))
            refreshNotification()
        } catch (e: Exception) {
            toast("Couldn't start recording: ${e.message}")
            pendingUri?.let { contentResolver.delete(it, null, null) }
            pendingUri = null
            stopRecording()
        }
    }

    private fun stopRecording() {
        if (finishing) return
        finishing = true
        val wasRecording = state.value != IDLE
        encoder?.stop()
        val err = encoder?.failure
        encoder = null
        projection?.stop(); projection = null
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        pendingUri?.let {
            contentResolver.update(it, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        }
        pendingUri = null
        applyTouches(false)
        stopService(Intent(this, OverlayService::class.java))
        state.value = IDLE
        if (wasRecording) toast(if (err == null) "Recording saved ✅" else "Saved, but: $err")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun captureSize(): Triple<Int, Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(WindowManager::class.java).defaultDisplay.getRealMetrics(dm)
        var w = dm.widthPixels
        var h = dm.heightPixels
        val short = minOf(w, h)
        val target = Prefs.height
        if (target in 1 until short) {
            val s = target.toFloat() / short
            w = (w * s).toInt(); h = (h * s).toInt()
        }
        return Triple(w and 1.inv(), h and 1.inv(), dm.densityDpi)
    }

    private fun createMuxer(): MediaMuxer {
        val name = "ScreenSnap_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val fmt = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        val custom = Prefs.saveUri
        return when {
            custom.isNotEmpty() && Build.VERSION.SDK_INT >= 26 -> {
                val tree = Uri.parse(custom)
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val doc = DocumentsContract.createDocument(contentResolver, parent, "video/mp4", name)!!
                pfd = contentResolver.openFileDescriptor(doc, "rw")
                MediaMuxer(pfd!!.fileDescriptor, fmt)
            }
            Build.VERSION.SDK_INT >= 29 -> {
                val v = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ScreenSnap")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                pendingUri = contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v)
                pfd = contentResolver.openFileDescriptor(pendingUri!!, "rw")
                MediaMuxer(pfd!!.fileDescriptor, fmt)
            }
            else -> {
                val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!.also { it.mkdirs() }
                MediaMuxer(File(dir, name).absolutePath, fmt)
            }
        }
    }

    private fun applyTouches(on: Boolean) {
        if (!Prefs.showTouches) return
        try {
            if (on) {
                oldTouches = Settings.System.getInt(contentResolver, "show_touches", 0)
                Settings.System.putInt(contentResolver, "show_touches", 1)
                touchesChanged = true
            } else if (touchesChanged) {
                Settings.System.putInt(contentResolver, "show_touches", oldTouches)
                touchesChanged = false
            }
        } catch (_: Exception) {}
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        fun pi(action: String) = PendingIntent.getService(
            this, action.hashCode(), Intent(this, RecorderService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val paused = state.value == PAUSED
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle("ScreenSnap")
            .setContentText(if (paused) "Paused" else "Recording…")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                if (paused) R.drawable.ic_play else R.drawable.ic_pause,
                if (paused) "Resume" else "Pause",
                pi(if (paused) ACTION_RESUME else ACTION_PAUSE)
            )
            .addAction(R.drawable.ic_stop, "Stop", pi(ACTION_STOP))
            .build()
    }

    private fun refreshNotification() =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
}