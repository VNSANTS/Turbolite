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
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import java.net.InetAddress
import java.util.concurrent.Executors

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var panel: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var pingText = "Ping: medindo…"

    private val refresh = object : Runnable {
        override fun run() {
            updateRam()
            panel?.text = buildText()
            handler.postDelayed(this, 1800)
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
            val start = System.nanoTime()
            pingText = try {
                if (InetAddress.getByName("1.1.1.1").isReachable(900)) {
                    "Ping: ${(System.nanoTime() - start) / 1_000_000} ms"
                } else "Ping: sem resposta"
            } catch (_: Exception) {
                "Ping: indisponível"
            }
            handler.post { panel?.text = buildText() }
            handler.postDelayed({ if (panel != null) measurePing() }, 3000)
        }
    }

    private fun buildText(): String {
        val prefs = getSharedPreferences("turbolite_overlay", MODE_PRIVATE)
        val lines = mutableListOf<String>()
        if (prefs.getBoolean("show_fps", true)) lines.add("FPS: -- (não medido)")
        if (prefs.getBoolean("show_ram", true)) lines.add("RAM: ${prefs.getString("ram_value", "lendo…")}")
        if (prefs.getBoolean("show_ping", false)) lines.add(pingText)
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
