package com.vnsants.turbolite

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import java.net.InetAddress
import java.util.concurrent.Executors
import rikka.shizuku.Shizuku

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var panel: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var pingText = "Ping: medindo…"
    @Volatile private var fpsText = "FPS: medindo…"
    @Volatile private var cpuText = "CPU: medindo…"
    @Volatile private var tempText = "Temp: indisponível"
    private var previousCpuTotal = 0L
    private var previousCpuIdle = 0L

    private val refresh = object : Runnable {
        override fun run() {
            updateRam()
            panel?.text = buildText()
            measureDeviceStats()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(12, 8, 12, 8)
            setBackgroundColor(0xDD101827.toInt())
            setOnTouchListener(object : android.view.View.OnTouchListener {
                private var startX = 0
                private var startY = 0
                private var touchX = 0f
                private var touchY = 0f
                override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
                    val params = v.layoutParams as WindowManager.LayoutParams
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            startX = params.x
                            startY = params.y
                            touchX = event.rawX
                            touchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = startX + (event.rawX - touchX).toInt()
                            params.y = startY + (event.rawY - touchY).toInt()
                            windowManager.updateViewLayout(v, params)
                            return true
                        }
                    }
                    return false
                }
            })
        }
        panel = view
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 120
        }
        try {
            windowManager.addView(view, params)
            handler.post(refresh)
            measurePing()
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun updateRam() {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val used = (info.totalMem - info.availMem) / (1024 * 1024)
        val total = info.totalMem / (1024 * 1024)
        getSharedPreferences("turbolite_overlay", MODE_PRIVATE).edit()
            .putString("ram_value", "${used}/${total} MB").apply()
    }

    private fun measurePing() {
        worker.execute {
            pingText = try {
                if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    "Ping: requer Shizuku"
                } else {
                    val process = Shizuku.newProcess(arrayOf("sh", "-c", "ping -c 1 -W 1 1.1.1.1"), null, null)
                    val output = process.inputStream.bufferedReader().use { it.readText() }
                    process.waitFor()
                    val match = Regex("time[=<]([0-9.]+)").find(output)
                    if (match != null) "Ping: ${match.groupValues[1]} ms" else "Ping: sem resposta"
                }
            } catch (_: Exception) {
                "Ping: indisponível"
            }
            handler.post { panel?.text = buildText() }
            handler.postDelayed({ if (panel != null) measurePing() }, 5000)
        }
    }

    private fun measureDeviceStats() {
        worker.execute {
            try {
                if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    val proc = Shizuku.newProcess(arrayOf("sh", "-c", "cat /proc/stat | head -n 1"), null, null)
                    val line = proc.inputStream.bufferedReader().use { it.readLine() ?: "" }
                    proc.waitFor()
                    val values = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                    if (values.size >= 4) {
                        val idle = values[3] + (values.getOrNull(4) ?: 0L)
                        val total = values.sum()
                        if (previousCpuTotal > 0L && total > previousCpuTotal) {
                            val usage = ((total - previousCpuTotal - (idle - previousCpuIdle)).toDouble() * 100.0 / (total - previousCpuTotal)).toInt().coerceIn(0, 100)
                            cpuText = "CPU: $usage%"
                        }
                        previousCpuTotal = total
                        previousCpuIdle = idle
                    }
                    val tempCmd = "for f in /sys/class/thermal/thermal_zone*/temp; do v=\$(cat \$f 2>/dev/null); case \$v in ''|*[!0-9]*) ;; *) if [ \$v -gt 1000 ]; then echo \$v; exit; fi ;; esac; done"
                    val tp = Shizuku.newProcess(arrayOf("sh", "-c", tempCmd), null, null)
                    val rawTemp = tp.inputStream.bufferedReader().use { it.readText().trim() }
                    tp.waitFor()
                    val milli = rawTemp.toIntOrNull()
                    tempText = if (milli != null) "Temp: ${"%.1f".format(java.util.Locale.US, milli / 1000.0)}°C" else "Temp: indisponível"
                    fpsText = measureSurfaceFlingerFps()
                }
            } catch (_: Exception) {
                if (fpsText == "FPS: medindo…") fpsText = "FPS: indisponível"
            }
            handler.post { panel?.text = buildText() }
        }
    }

    private fun measureSurfaceFlingerFps(): String {
        return try {
            val top = Shizuku.newProcess(arrayOf("sh", "-c", "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -n 1"), null, null)
            val topLine = top.inputStream.bufferedReader().use { it.readText() }
            top.waitFor()
            val pkg = Regex("u0\\s+([A-Za-z0-9_.]+)").find(topLine)?.groupValues?.get(1)
                ?: Regex("([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/").find(topLine)?.groupValues?.get(1)
                ?: return "FPS: sem app ativo"
            val list = Shizuku.newProcess(arrayOf("sh", "-c", "dumpsys SurfaceFlinger --list | grep -F '${pkg}' | head -n 6"), null, null)
            val layers = list.inputStream.bufferedReader().use { it.readLines() }
            list.waitFor()
            val now = SystemClock.elapsedRealtimeNanos()
            for (layer in layers) {
                val safeLayer = layer.replace("'", "'\\''")
                val p = Shizuku.newProcess(arrayOf("sh", "-c", "dumpsys SurfaceFlinger --latency '${safeLayer}'"), null, null)
                val output = p.inputStream.bufferedReader().use { it.readText() }
                p.waitFor()
                val timestamps = output.lineSequence().drop(1).mapNotNull { row ->
                    val cols = row.trim().split(Regex("\\s+"))
                    cols.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
                }.toList()
                if (timestamps.size >= 2) {
                    val recent = timestamps.distinct().count { it <= now && it >= now - 1_000_000_000L }
                    if (recent > 0) return "FPS: $recent"
                }
            }
            "FPS: indisponível"
        } catch (_: Exception) {
            "FPS: indisponível"
        }
    }

    private fun buildText(): String {
        val prefs = getSharedPreferences("turbolite_overlay", MODE_PRIVATE)
        val lines = mutableListOf<String>()
        if (prefs.getBoolean("show_fps", true)) lines.add(fpsText)
        if (prefs.getBoolean("show_ram", true)) lines.add("RAM: ${prefs.getString("ram_value", "lendo…")}")
        if (prefs.getBoolean("show_ping", false)) lines.add(pingText)
        if (prefs.getBoolean("show_cpu", false)) lines.add(cpuText)
        if (prefs.getBoolean("show_temp", false)) lines.add(tempText)
        return (lines.ifEmpty { listOf("TurboLite • sem métricas") }).joinToString("\n")
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        panel?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        panel = null
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
