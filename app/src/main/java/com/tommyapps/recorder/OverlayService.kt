package com.tommyapps.recorder

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.*
import android.view.WindowManager.LayoutParams as LP
import android.widget.*
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.*

class OverlayService : Service() {
    companion object {
        /** Called by EyeDropperActivity with the picked colour (or null if cancelled). */
        var onPicked: ((Int?) -> Unit)? = null
    }

    private lateinit var wm: WindowManager
    private lateinit var drawView: DrawView
    private lateinit var root: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var penBtn: ImageView
    private lateinit var hueBar: SeekBar
    private lateinit var lightBar: SeekBar
    private lateinit var drawParams: LP
    private lateinit var rootParams: LP
    private var penState = 0   // 0 = off, 1 = drawing, 2 = colour picker open
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= 26) LP.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") LP.TYPE_PHONE
    private fun send(action: String) = startService(Intent(this, RecorderService::class.java).setAction(action))
    private fun round(color: Int, r: Int) = GradientDrawable().apply { cornerRadius = dp(r).toFloat(); setColor(color) }

    private fun icon(res: Int, onClick: (() -> Unit)? = null) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        if (onClick != null) setOnClickListener { onClick() }
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val base = LP.FLAG_NOT_FOCUSABLE or LP.FLAG_LAYOUT_IN_SCREEN

        // Full-screen drawing layer (touch-through until the pen is on)
        drawView = DrawView(this).also { it.color = Prefs.penColor }
        drawParams = LP(LP.MATCH_PARENT, LP.MATCH_PARENT, overlayType(), base or LP.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT)
        wm.addView(drawView, drawParams)

        // Floating bar + colour panel
        val handle = icon(R.drawable.ic_drag)
        val pause = icon(R.drawable.ic_pause) {
            send(if (RecorderService.state.value == RecorderService.PAUSED) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE)
        }
        val stop = icon(R.drawable.ic_stop) { send(RecorderService.ACTION_STOP) }
        penBtn = icon(R.drawable.ic_pen) { penState = (penState + 1) % 3; applyPen() }
        val erase = icon(R.drawable.ic_eraser) { drawView.clear() }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = round(0xF2201B2E.toInt(), 28)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            listOf(handle, pause, stop, penBtn, erase).forEach { addView(it) }
        }
        panel = buildPanel()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            alpha = Prefs.overlayAlpha / 100f
            addView(row)
            addView(panel)
        }
        rootParams = LP(LP.WRAP_CONTENT, LP.WRAP_CONTENT, overlayType(), base, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.START; x = dp(16); y = dp(120) }
        wm.addView(root, rootParams)

        // Drag the bar by its handle
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = rootParams.x; oy = rootParams.y }
                MotionEvent.ACTION_MOVE -> {
                    rootParams.x = ox + (e.rawX - sx).toInt()
                    rootParams.y = oy + (e.rawY - sy).toInt()
                    wm.updateViewLayout(root, rootParams)
                }
            }
            true
        }

        scope.launch {
            RecorderService.state.collect {
                pause.setImageResource(if (it == RecorderService.PAUSED) R.drawable.ic_play else R.drawable.ic_pause)
            }
        }

        // Result from the Android 17 eyedropper
        onPicked = { c ->
            if (c != null) { setPenColor(c); syncBars(c) }
            penState = 1
            applyPen()
        }
    }

    private fun applyPen() {
        setDrawTouchable(penState != 0)
        panel.visibility = if (penState == 2) View.VISIBLE else View.GONE
        penBtn.background = if (penState != 0)
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF6B4EFF.toInt()) } else null
    }

    private fun setDrawTouchable(on: Boolean) {
        drawParams.flags = if (on) drawParams.flags and LP.FLAG_NOT_TOUCHABLE.inv()
        else drawParams.flags or LP.FLAG_NOT_TOUCHABLE
        wm.updateViewLayout(drawView, drawParams)
    }

    private fun setPenColor(c: Int) { drawView.color = c; Prefs.penColor = c }

    private fun seek(max: Int, onChange: () -> Unit) = SeekBar(this).apply {
        this.max = max
        layoutParams = LinearLayout.LayoutParams(dp(220), dp(32)).apply { topMargin = dp(8) }
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) onChange() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun buildPanel(): LinearLayout {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(0xF2201B2E.toInt(), 24)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LP.WRAP_CONTENT, LP.WRAP_CONTENT).apply { topMargin = dp(8) }
        }

        // Preset colours (2 rows of 5)
        val presets = listOf(
            0xFFFF4D8D, 0xFFFF3B30, 0xFFFF9500, 0xFFFFD60A, 0xFF34C759,
            0xFF00C7FF, 0xFF3D5AFE, 0xFFAF52DE, 0xFFFFFFFF, 0xFF000000
        ).map { it.toInt() }
        presets.chunked(5).forEach { rowColors ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowColors.forEach { c ->
                r.addView(View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(2), 0x66FFFFFF)
                    }
                    layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                    setOnClickListener { setPenColor(c); syncBars(c); penState = 1; applyPen() }
                })
            }
            p.addView(r)
        }

        // Custom colour: hue + lightness sliders
        hueBar = seek(360) { applyHsl() }
        hueBar.progressDrawable = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            IntArray(7) { ColorUtils.HSLToColor(floatArrayOf(it * 60f, 1f, 0.5f)) }
        ).apply { cornerRadius = dp(8).toFloat() }
        lightBar = seek(100) { applyHsl() }
        lightBar.progressDrawable = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.BLACK, Color.RED, Color.WHITE)
        ).apply { cornerRadius = dp(8).toFloat() }
        p.addView(hueBar)
        p.addView(lightBar)
        syncBars(Prefs.penColor)

        // Android 17+: system eyedropper
        if (Build.VERSION.SDK_INT >= 37) {
            p.addView(TextView(this).apply {
                text = "Pick colour from screen"
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                background = round(0xFF6B4EFF.toInt(), 20)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                layoutParams = LinearLayout.LayoutParams(LP.MATCH_PARENT, LP.WRAP_CONTENT).apply { topMargin = dp(12) }
                setOnClickListener {
                    setDrawTouchable(false) // so the pen layer doesn't block the eyedropper
                    startActivity(Intent(this@OverlayService, EyeDropperActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            })
        }
        return p
    }

    private fun applyHsl() {
        val h = hueBar.progress.toFloat()
        setPenColor(ColorUtils.HSLToColor(floatArrayOf(h, 1f, lightBar.progress / 100f)))
        (lightBar.progressDrawable as GradientDrawable).colors =
            intArrayOf(Color.BLACK, ColorUtils.HSLToColor(floatArrayOf(h, 1f, 0.5f)), Color.WHITE)
    }

    private fun syncBars(c: Int) {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(c, hsl)
        hueBar.progress = hsl[0].toInt()
        lightBar.progress = (hsl[2] * 100).toInt()
        (lightBar.progressDrawable as GradientDrawable).colors =
            intArrayOf(Color.BLACK, ColorUtils.HSLToColor(floatArrayOf(hsl[0], 1f, 0.5f)), Color.WHITE)
    }

    override fun onDestroy() {
        onPicked = null
        scope.cancel()
        try { wm.removeView(root) } catch (_: Exception) {}
        try { wm.removeView(drawView) } catch (_: Exception) {}
        super.onDestroy()
    }
}

class DrawView(context: Context) : View(context) {
    private class Stroke(val path: Path, val color: Int)
    private val strokes = mutableListOf<Stroke>()
    var color: Int = Color.RED
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * context.resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        strokes.forEach { paint.color = it.color; canvas.drawPath(it.path, paint) }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> strokes += Stroke(Path().apply { moveTo(e.x, e.y); lineTo(e.x + 0.1f, e.y) }, color)
            MotionEvent.ACTION_MOVE -> strokes.lastOrNull()?.path?.lineTo(e.x, e.y)
        }
        invalidate()
        return true
    }

    fun clear() { strokes.clear(); invalidate() }
}