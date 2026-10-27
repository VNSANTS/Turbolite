package com.vnsants.turbolite

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.LinearLayout
import java.util.concurrent.Executors
import rikka.shizuku.Shizuku

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var panel: LinearLayout? = null
    private var metricsText: TextView? = null
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
    @Volatile private var statsMeasureBusy = false
    @Volatile private var pingMeasureBusy = false

    private val refresh = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("turbolite_overlay", MODE_PRIVATE)
            if (prefs.getBoolean("show_ram", true)) updateRam()
            if (prefs.getBoolean("show_network", true)) updateNetwork() else networkText = "Rede: desativada"
            renderMetrics()
            val nowMs = SystemClock.elapsedRealtime()
            val wantsDeviceStats = prefs.getBoolean("show_cpu", false) ||
                prefs.getBoolean("show_temp", false) || prefs.getBoolean("show_fps", false)
            if (wantsDeviceStats && nowMs - lastStatsMeasureMs >= 5000L && !statsMeasureBusy) {
                lastStatsMeasureMs = nowMs
                measureDeviceStats()
            }
            if (prefs.getBoolean("show_ping", false) && nowMs - lastPingMeasureMs >= 15000L && !pingMeasureBusy) {
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

        val overlayPrefs = getSharedPreferences("turbolite_overlay", MODE_PRIVATE)
        val appearance = overlayPrefs.getString("appearance", "glass") ?: "glass"
        val minimal = appearance == "minimal"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(if (minimal) dp(9) else dp(12), if (minimal) dp(6) else dp(10), if (minimal) dp(9) else dp(12), if (minimal) dp(7) else dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(if (minimal) 10 else 18).toFloat()
                setColor(if (minimal) 0xE6101724.toInt() else 0xF20B1220.toInt())
                setStroke(dp(1), withAlpha(accentColor(), if (minimal) 0x45 else 0x75))
            }
            elevation = dp(if (minimal) 4 else 8).toFloat()
            minimumWidth = minOf(dp(if (minimal) 120 else 220), (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(120)))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = false
            isFocusable = false
        }
        val mark = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_turbolite)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = null
        }
        header.addView(mark, LinearLayout.LayoutParams(dp(30), dp(30)))
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, 0, 0)
        }
        brand.addView(TextView(this).apply {
            text = "TurboLite"
            textSize = 14f
            setTextColor(0xFFF2F7FC.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        brand.addView(TextView(this).apply {
            text = "AO VIVO • ARRASTE PARA MOVER"
            textSize = 9f
            letterSpacing = 0.08f
            setTextColor(accentColor())
        })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val close = TextView(this).apply {
            text = "×"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(0xFFB8C7D8.toInt())
            contentDescription = "Fechar painel flutuante"
            isClickable = true
            isFocusable = true
            minWidth = dp(48)
            minHeight = dp(48)
            setOnClickListener { stopSelf() }
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(withAlpha(accentColor(), 0x50)),
                GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0x00000000) },
                null
            )
        }
        header.addView(close, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(48)))
        if (minimal) header.visibility = android.view.View.GONE

        val divider = android.view.View(this).apply { setBackgroundColor(0xFF26384B.toInt()) }
        root.addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(7)
            bottomMargin = dp(8)
        })
        if (minimal) divider.visibility = android.view.View.GONE
        metricsText = TextView(this).apply {
            textSize = if (minimal) 11f else 12f
            setTextColor(if (minimal) 0xFFF4F8FF.toInt() else 0xFFDDE8F2.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(3), 0, dp(3), 0)
        }
        root.addView(metricsText, LinearLayout.LayoutParams(-1, -2))

        root.setOnTouchListener(object : android.view.View.OnTouchListener {
            private var resizing = false
            private var downRawX = 0f
            private var downRawY = 0f
            private var initialWidth = 0
            private var initialHeight = 0
            private var initialX = 0
            private var initialY = 0
            override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
                val params = root.layoutParams as? WindowManager.LayoutParams ?: return false
                val edge = dp(22)
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        val localX = event.x
                        val localY = event.y
                        resizing = localX >= root.width - edge || localY >= root.height - edge
                        if (!resizing) return false
                        downRawX = event.rawX; downRawY = event.rawY
                        initialWidth = root.width.coerceAtLeast(dp(140))
                        initialHeight = root.height.coerceAtLeast(dp(50))
                        initialX = params.x; initialY = params.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!resizing) return false
                        val maxWidth = (resources.displayMetrics.widthPixels - dp(12)).coerceAtLeast(dp(140))
                        val maxHeight = (resources.displayMetrics.heightPixels - dp(80)).coerceAtLeast(dp(70))
                        params.width = (initialWidth + (event.rawX - downRawX).toInt()).coerceIn(dp(140), maxWidth)
                        params.height = (initialHeight + (event.rawY - downRawY).toInt()).coerceIn(dp(70), maxHeight)
                        params.x = initialX.coerceIn(0, (resources.displayMetrics.widthPixels - params.width).coerceAtLeast(0))
                        params.y = initialY.coerceIn(0, (resources.displayMetrics.heightPixels - params.height).coerceAtLeast(0))
                        try { windowManager.updateViewLayout(root, params) } catch (_: Exception) { return false }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { val wasResizing = resizing; resizing = false; return wasResizing }
                }
                return resizing
            }
        })
        header.setOnTouchListener(object : android.view.View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
                val params = root.layoutParams as? WindowManager.LayoutParams ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val maxX = (resources.displayMetrics.widthPixels - root.width).coerceAtLeast(0)
                        val maxY = (resources.displayMetrics.heightPixels - root.height).coerceAtLeast(0)
                        params.x = (startX + (event.rawX - touchX).toInt()).coerceIn(0, maxX)
                        params.y = (startY + (event.rawY - touchY).toInt()).coerceIn(0, maxY)
                        try { windowManager.updateViewLayout(root, params) } catch (_: Exception) { return false }
                        return true
                    }
                }
                return false
            }
        })
        panel = root

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
            width = minOf(dp(if (minimal) 180 else 280), (resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(140)))
            x = dp(12)
            y = dp(100)
        }
        try {
            windowManager.addView(root, params)
            handler.post(refresh)
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun accentColor(): Int = when (
        getSharedPreferences("turbolite_settings", MODE_PRIVATE).getString("accent_theme", "cyan")
    ) {
        "violet" -> 0xFFB388FF.toInt()
        "green" -> 0xFF54E6A5.toInt()
        else -> 0xFF00D4FF.toInt()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

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
        if (pingMeasureBusy) return
        pingMeasureBusy = true
        try {
            worker.execute {
                try {
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
                } finally {
                    pingMeasureBusy = false
                }
                handler.post { renderMetrics() }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            pingMeasureBusy = false
        }
    }

    private fun measureDeviceStats() {
        if (statsMeasureBusy) return
        statsMeasureBusy = true
        try {
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

                    if (prefs.getBoolean("show_fps", false)) {
                        fpsText = if (hasShizuku) measureSurfaceFlingerFps() else "FPS: requer Shizuku"
                    }
                } catch (_: Exception) {
                    if (prefs.getBoolean("show_cpu", false)) cpuText = "CPU: indisponível"
                    if (prefs.getBoolean("show_temp", false)) tempText = "Temp: indisponível"
                    if (prefs.getBoolean("show_fps", false)) fpsText = "FPS: indisponível"
                } finally {
                    statsMeasureBusy = false
                }
                handler.post { renderMetrics() }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            statsMeasureBusy = false
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
        if (prefs.getBoolean("show_fps", false)) lines.add(fpsText)
        if (prefs.getBoolean("show_ram", true)) lines.add("RAM: $ramText")
        if (prefs.getBoolean("show_network", true)) lines.add(networkText)
        if (prefs.getBoolean("show_ping", false)) lines.add(pingText)
        if (prefs.getBoolean("show_cpu", false)) lines.add(cpuText)
        if (prefs.getBoolean("show_temp", false)) lines.add(tempText)
        return (lines.ifEmpty { listOf("Ative as métricas desejadas nas configurações.") }).joinToString("\n")
    }

    private fun renderMetrics() {
        val text = metricsText ?: return
        text.text = buildText()
        val minimal = getSharedPreferences("turbolite_overlay", MODE_PRIVATE).getString("appearance", "glass") == "minimal"
        text.gravity = if (minimal) Gravity.CENTER else Gravity.START
        text.typeface = Typeface.create(if (minimal) "sans-serif" else "sans-serif-medium", if (minimal) Typeface.NORMAL else Typeface.BOLD)
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
