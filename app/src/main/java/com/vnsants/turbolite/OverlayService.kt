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
import android.net.TrafficStats
import android.content.IntentFilter
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
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
    @Volatile private var ramText = "lendo…"
    @Volatile private var networkText = "Rede: medindo…"
    private var previousRxBytes = -1L
    private var previousTxBytes = -1L
    private var previousNetworkAtMs = 0L
    private var lastPingMeasureMs = 0L
    private var previousCpuTotal = 0L
    private var previousCpuIdle = 0L
    private var lastStatsMeasureMs = 0L

    private val refresh = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("turbolite_overlay", MODE_PRIVATE)
            if (prefs.getBoolean("show_ram", true)) updateRam()
            if (prefs.getBoolean("show_network", true)) updateNetwork() else networkText = "Rede: desativada"
            panel?.text = buildText()
            val nowMs = SystemClock.elapsedRealtime()
            val wantsDeviceStats = prefs.getBoolean("show_cpu", false) ||
                prefs.getBoolean("show_temp", false) || prefs.getBoolean("show_fps", true)
            if (wantsDeviceStats && nowMs - lastStatsMeasureMs >= 5000L) {
                lastStatsMeasureMs = nowMs
                measureDeviceStats()
            }
            if (prefs.getBoolean("show_ping", false) && nowMs - lastPingMeasureMs >= 15000L) {
                lastPingMeasureMs = nowMs
                measurePing()
            } else if (!prefs.getBoolean("show_ping", false)) {
                pingText = "Ping: desativado"
            }
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
        ramText = "${used}/${total} MB"
    }


    private fun updateNetwork() {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val now = SystemClock.elapsedRealtime()
        if (rx < 0L || tx < 0L) {
            networkText = "Rede: indisponível"
            return
        }
        val elapsed = now - previousNetworkAtMs
        if (previousRxBytes >= 0L && previousTxBytes >= 0L && elapsed > 0L) {
            val rxRate = ((rx - previousRxBytes).coerceAtLeast(0L) * 1000L) / elapsed
            val txRate = ((tx - previousTxBytes).coerceAtLeast(0L) * 1000L) / elapsed
            networkText = "Rede ↓ ${formatRate(rxRate)}  ↑ ${formatRate(txRate)}"
        } else {
            networkText = "Rede: medindo…"
        }
        previousRxBytes = rx
        previousTxBytes = tx
        previousNetworkAtMs = now
    }

    private fun formatRate(bytesPerSecond: Long): String {
        return when {
            bytesPerSecond < 1024L -> "$bytesPerSecond B/s"
            bytesPerSecond < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f KB/s", bytesPerSecond / 1024.0)
            else -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
        }
    }

    private fun runShell(command: String): Pair<Int, String> =
        ShizukuCommandClient.run(this, command, 8_000L)

    private fun measurePing() {
        worker.execute {
            pingText = try {
                if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    "Ping: requer Shizuku"
                } else {
                    val (exitCode, output) = runShell("ping -c 1 -W 1 1.1.1.1")
                    val match = Regex("time[=<]([0-9.]+)").find(output)
                    if (exitCode == 0 && match != null) "Ping: ${match.groupValues[1]} ms" else "Ping: sem resposta"
                }
            } catch (_: Exception) {
                "Ping: indisponível"
            }
            handler.post { panel?.text = buildText() }
        }
    }

    private fun measureDeviceStats() {
        worker.execute {
            val prefs = getSharedPreferences("turbolite_overlay", MODE_PRIVATE)
            val hasShizuku = try {
                Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) { false }
            try {
                if (prefs.getBoolean("show_cpu", false)) {
                    val line = java.io.File("/proc/stat").bufferedReader().use { it.readLine() ?: "" }
                    val values = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                    if (values.size >= 4) {
                        val idle = values[3] + (values.getOrNull(4) ?: 0L)
                        val total = values.sum()
                        if (previousCpuTotal > 0L && total > previousCpuTotal) {
                            val delta = total - previousCpuTotal
                            val usage = ((delta - (idle - previousCpuIdle)).toDouble() * 100.0 / delta)
                                .toInt().coerceIn(0, 100)
                            cpuText = "CPU: $usage%"
                        } else {
                            cpuText = "CPU: calculando…"
                        }
                        previousCpuTotal = total
                        previousCpuIdle = idle
                    }
                }

                if (prefs.getBoolean("show_temp", false)) {
                    var temperature: Float? = null
                    if (hasShizuku) {
                        val tempCmd = "for f in /sys/class/thermal/thermal_zone*/temp; do cat \$f 2>/dev/null; done | awk '\$1 ~ /^[0-9]+(\\.[0-9]+)?$/ { v=\$1+0; if (v>max) max=v } END { if (max>0) printf \"%.1f\\n\", max }'"
                        val (tempExitCode, rawTemp) = runShell(tempCmd)
                        temperature = if (tempExitCode == 0) rawTemp.toFloatOrNull()?.let { raw ->
                            when { raw >= 1000f -> raw / 1000f; raw >= 100f -> raw / 10f; raw in 0f..120f -> raw; else -> null }
                        } else null
                    }
                    if (temperature == null) {
                        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                        val tenths = battery?.getIntExtra("temperature", -1) ?: -1
                        if (tenths > 0) temperature = tenths / 10f
                    }
                    tempText = if (temperature != null && temperature in 0f..120f) {
                        "Temp: ${String.format(java.util.Locale.US, "%.1f", temperature)}°C (bateria/sensor)"
                    } else {
                        "Temp: indisponível"
                    }
                }

                if (prefs.getBoolean("show_fps", true)) {
                    fpsText = if (hasShizuku) measureSurfaceFlingerFps() else "FPS: requer Shizuku"
                }
            } catch (_: Exception) {
                if (prefs.getBoolean("show_cpu", false)) cpuText = "CPU: indisponível"
                if (prefs.getBoolean("show_temp", false)) tempText = "Temp: indisponível"
                if (prefs.getBoolean("show_fps", true)) fpsText = "FPS: indisponível"
            }
            handler.post { panel?.text = buildText() }
        }
    }

    private fun measureSurfaceFlingerFps(): String {
        return try {
            val (topExit, topLine) = runShell("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -n 1")
            if (topExit != 0) return "FPS: indisponível"
            val pkg = Regex("u0\\s+([A-Za-z0-9_.]+)").find(topLine)?.groupValues?.get(1)
                ?: Regex("([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/").find(topLine)?.groupValues?.get(1)
                ?: return "FPS: sem app ativo"
            val (listExit, listOutput) = runShell("dumpsys SurfaceFlinger --list | grep -F '${pkg}' | head -n 6")
            if (listExit != 0) return "FPS: indisponível"
            val layers = listOutput.lines()
            val now = System.nanoTime()
            for (layer in layers) {
                val safeLayer = layer.replace("'", "'\\''")
                val (latencyExit, output) = runShell("dumpsys SurfaceFlinger --latency '${safeLayer}'")
                if (latencyExit != 0) continue
                val timestamps = output.lineSequence().drop(1).mapNotNull { row ->
                    val cols = row.trim().split(Regex("\\s+"))
                    cols.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
                }.toList()
                if (timestamps.size >= 2) {
                    val recent = timestamps.distinct().count { it <= now && it >= now - 1_000_000_000L }
                    if (recent > 0) return "FPS≈ $recent (estimativa)"
                }
            }
            "FPS≈ indisponível"
        } catch (_: Exception) {
            "FPS≈ indisponível"
        }
    }

    private fun buildText(): String {
        val prefs = getSharedPreferences("turbolite_overlay", MODE_PRIVATE)
        val lines = mutableListOf<String>()
        if (prefs.getBoolean("show_fps", true)) lines.add(fpsText)
        if (prefs.getBoolean("show_ram", true)) lines.add("RAM: $ramText")
        if (prefs.getBoolean("show_network", true)) lines.add(networkText)
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
