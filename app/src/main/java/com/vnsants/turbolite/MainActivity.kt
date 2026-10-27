package com.vnsants.turbolite

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.net.TrafficStats
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var actionButton: Button
    private lateinit var reloadButton: Button
    private lateinit var performanceButton: Button
    private lateinit var restoreButton: Button
    private lateinit var refreshRateButton: Button
    private lateinit var overlayButton: Button
    private lateinit var stopOverlayButton: Button
    private lateinit var dpiInput: EditText
    private lateinit var dpiApplyButton: Button
    private lateinit var dpiRestoreButton: Button
    private lateinit var gameFocusButton: Button
    private lateinit var keepSpinner: Spinner
    private var userApps: List<ApplicationInfo> = emptyList()
    private var keepPackages: List<String> = emptyList()
    private lateinit var autoThermalCheck: CheckBox
    private val thermalHandler = Handler(Looper.getMainLooper())
    private val monitorHandler = Handler(Looper.getMainLooper())
    private var selectedTabKey = "home"
    private var previousRxBytes = -1L
    private var previousTxBytes = -1L
    private var previousNetworkAtMs = 0L
    private lateinit var performanceCard: TextView
    private lateinit var monitorGauge: TextView
    private lateinit var monitorMetrics: TextView
    private lateinit var currentModeCard: TextView
    private lateinit var permissionCard: TextView
    private lateinit var monitorChart: MetricChartView
    private val metricHistory = mutableListOf<MetricSample>()
    private data class MetricSample(val cpu: Int?, val ram: Int, val temperature: Float?)
    private val monitorUpdater = object : Runnable {
        override fun run() {
            if (selectedTabKey != "monitor" || isFinishing) return
            updateMonitorDashboard()
            monitorHandler.postDelayed(this, 5_000L)
        }
    }
    @Volatile private var thermalCheckBusy = false
    @Volatile private var permissionRequestInFlight = false
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (::status.isInitialized) updatePermissionState()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        if (::status.isInitialized) {
            permissionRequestInFlight = false
            status.text = "Conexão do Shizuku perdida. Reinicie o serviço e tente novamente."
            if (::actionButton.isInitialized) actionButton.isEnabled = false
        }
    }
    private val thermalMonitor = object : Runnable {
        override fun run() {
            if (!::autoThermalCheck.isInitialized || !autoThermalCheck.isChecked || isFinishing) return
            if (!thermalCheckBusy && hasShizukuPermission()) {
                thermalCheckBusy = true
                executor.execute {
                    try {
                        val batteryDump = runShell("dumpsys battery").second
                        val raw = Regex("(?m)^\\s*temperature:\\s*(\\d+)").find(batteryDump)?.groupValues?.get(1)?.toIntOrNull()
                        val batteryC = raw?.let { if (it > 100) it / 10f else it.toFloat() }
                        val thermalDump = runShell("dumpsys thermalservice").second
                        val serviceValues = Regex("mValue=([0-9]+(?:\\.[0-9]+)?)")
                            .findAll(thermalDump).mapNotNull { it.groupValues[1].toFloatOrNull() }
                            .mapNotNull { raw ->
                                when {
                                    raw >= 1000f -> raw / 1000f
                                    raw >= 100f -> raw / 10f
                                    raw in 0f..120f -> raw
                                    else -> null
                                }
                            }.filter { it in 25f..120f }.toList()
                        val zoneDump = runShell("for f in /sys/class/thermal/thermal_zone*/temp; do cat \$f 2>/dev/null; done").second
                        val zoneValues = zoneDump.lineSequence().mapNotNull { it.trim().toFloatOrNull() }
                            .mapNotNull { raw ->
                                when {
                                    raw >= 1000f -> raw / 1000f
                                    raw >= 100f -> raw / 10f
                                    raw in 0f..120f -> raw
                                    else -> null
                                }
                            }.filter { it in 25f..120f }.toList()
                        val thermalValues = serviceValues + zoneValues
                        val hot = (batteryC != null && batteryC >= 42f) || thermalValues.any { it >= 45f }
                        val hasThermalReading = batteryC != null || thermalValues.isNotEmpty()
                        val cool = hasThermalReading &&
                            (batteryC == null || batteryC <= 39f) && thermalValues.none { it >= 42f }
                        runOnUiThread {
                            if (!::autoThermalCheck.isInitialized || !autoThermalCheck.isChecked) return@runOnUiThread
                            val prefs = getSharedPreferences("turbolite_settings", MODE_PRIVATE)
                            if (hot && !prefs.getBoolean("auto_thermal_cooling_active", false)) {
                                prefs.edit().putBoolean("auto_thermal_cooling_active", true).apply()
                                status.text = "Temperatura elevada detectada. Ativando perfil térmico automático…"
                                applyUsageProfile("auto_cooling")
                            } else if (cool && prefs.getBoolean("auto_thermal_cooling_active", false)) {
                                status.text = "Temperatura voltou a um nível menor. Restaurando configurações anteriores…"
                                restoreUsageProfile()
                            } else if (batteryC != null) {
                                status.text = "Monitor térmico ativo • bateria: ${String.format(java.util.Locale.US, "%.1f", batteryC)} °C"
                            } else if (thermalValues.isNotEmpty()) {
                                status.text = "Monitor térmico ativo • sensor: ${String.format(java.util.Locale.US, "%.1f", thermalValues.maxOrNull() ?: 0f)} °C"
                            } else {
                                status.text = "Temperatura indisponível: a XOS não expôs sensores ao Shizuku. O monitor automático não consegue detectar aquecimento neste momento."
                                if (prefs.getBoolean("auto_thermal_cooling_active", false)) {
                                    // Não mantém um perfil térmico aplicado quando já não há leitura confiável.
                                    restoreUsageProfile()
                                }
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        thermalCheckBusy = false
                    }
                }
            }
            thermalHandler.postDelayed(this, 20_000L)
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        runOnUiThread {
            if (requestCode != 1001) return@runOnUiThread
            permissionRequestInFlight = false
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                status.text = "Shizuku autorizado. Pronto para otimizar."
                actionButton.isEnabled = true
            } else {
                status.text = "Permissão negada. Autorize o TurboLite no Shizuku."
                actionButton.isEnabled = false
            }
            if (::permissionCard.isInitialized) permissionCard.text = "SHIZUKU / PERMISSÕES\n${status.text}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        buildUi()
        val overlayPrefs = getSharedPreferences("turbolite_overlay", MODE_PRIVATE)
        if (!overlayPrefs.contains("show_fps")) {
            overlayPrefs.edit().putBoolean("show_fps", true).putBoolean("show_ram", true).putBoolean("show_ping", false).apply()
        }
        loadAppsToKeep()
        updatePermissionState()
    }

    private val profileActions = linkedMapOf(
        "close_apps" to "Fechar outros apps de usuário",
        "performance" to "Ativar modo de desempenho/animações",
        "max_refresh" to "Solicitar taxa máxima da tela",
        "dpi" to "Aplicar DPI recomendada",
        "game" to "Solicitar modo de jogo: performance",
        "streaming" to "Perfil integrado: streaming/equilíbrio",
        "efficiency" to "Perfil integrado: economia moderada",
        "cooling" to "Perfil integrado: resfriamento",
        "hard" to "Perfil integrado: HARD / economia extrema",
        "clean_ram" to "Fechar apps selecionáveis (sem limpeza de RAM)",
        "thermal" to "Ativar monitor térmico automático",
        "overlay" to "Mostrar painel flutuante"
    )

    private fun readCustomProfiles(): JSONArray {
        val raw = getSharedPreferences("turbolite_profiles", MODE_PRIVATE).getString("profiles", "[]") ?: "[]"
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun addCustomProfilesSection(root: LinearLayout) {
        val heading = TextView(this).apply {
            text = "PERFIS PERSONALIZADOS"
            textSize = 12f
            letterSpacing = 0.12f
            setTextColor(accentColor())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(20), 0, dp(8))
        }
        root.addView(heading)
        val description = TextView(this).apply {
            text = "Monte combinações próprias: escolha as funções, dê um nome e emoji e salve quantos perfis quiser. As opções são aplicadas individualmente; alguns ajustes dependem do Shizuku/XOS."
            textSize = 13f
            setTextColor(0xFFB8C4D9.toInt())
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(description)
        val profileName = EditText(this).apply {
            hint = "Nome do perfil (ex.: Jogo equilibrado)"
            textSize = 15f
            setSingleLine(true)
        }
        root.addView(profileName, LinearLayout.LayoutParams(-1, dp(50)))
        val profileEmoji = EditText(this).apply {
            hint = "Emoji (ex.: 🎮, ⚡, 🔋)"
            textSize = 16f
            setSingleLine(true)
        }
        root.addView(profileEmoji, LinearLayout.LayoutParams(-1, dp(50)))

        val checkboxes = linkedMapOf<String, CheckBox>()
        val optionsTitle = TextView(this).apply {
            text = "ESCOLHA CADA AÇÃO"
            textSize = 12f
            setTextColor(0xFF7DD3FC.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, dp(4))
        }
        root.addView(optionsTitle)
        for ((key, label) in profileActions) {
            val cb = CheckBox(this).apply {
                text = label
                textSize = 14f
                setTextColor(0xFFE2E8F0.toInt())
                isChecked = false
            }
            checkboxes[key] = cb
            root.addView(cb)
        }

        val profileSpinner = Spinner(this)
        root.addView(profileSpinner, LinearLayout.LayoutParams(-1, dp(50)))
        fun refreshProfiles(selectIndex: Int = 0) {
            val profiles = readCustomProfiles()
            val labels = (0 until profiles.length()).map { i ->
                val p = profiles.optJSONObject(i) ?: JSONObject()
                (p.optString("emoji", "⚡") + " " + p.optString("name", "Perfil")).trim()
            }.ifEmpty { listOf("Nenhum perfil salvo") }
            profileSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            if (selectIndex in labels.indices) profileSpinner.setSelection(selectIndex)
        }
        refreshProfiles()

        val saveButton = Button(this).apply {
            text = "SALVAR / ATUALIZAR PERFIL"
            setOnClickListener {
                val name = profileName.text.toString().trim()
                val emoji = profileEmoji.text.toString().trim().ifBlank { "⚡" }
                if (name.isBlank()) {
                    profileName.error = "Dê um nome ao perfil"
                    Toast.makeText(this@MainActivity, "Digite o nome do perfil.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val actions = JSONArray()
                checkboxes.filterValues { it.isChecked }.keys.forEach { actions.put(it) }
                if (actions.length() == 0) {
                    Toast.makeText(this@MainActivity, "Selecione pelo menos uma função.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val profiles = readCustomProfiles()
                val selected = profileSpinner.selectedItemPosition
                val old = profiles.optJSONObject(selected)
                val profile = JSONObject().put("name", name).put("emoji", emoji).put("actions", actions)
                val updateExisting = old != null && selected in 0 until profiles.length() && old.optString("name") == name
                if (updateExisting) profiles.put(selected, profile) else profiles.put(profile)
                getSharedPreferences("turbolite_profiles", MODE_PRIVATE).edit().putString("profiles", profiles.toString()).apply()
                refreshProfiles(if (updateExisting) selected else profiles.length() - 1)
                Toast.makeText(this@MainActivity, if (updateExisting) "Perfil atualizado neste aparelho." else "Novo perfil criado neste aparelho.", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(saveButton, LinearLayout.LayoutParams(-1, dp(50)))

        val loadButton = Button(this).apply {
            text = "CARREGAR E EDITAR PERFIL SELECIONADO"
            setOnClickListener {
                val p = readCustomProfiles().optJSONObject(profileSpinner.selectedItemPosition)
                if (p == null) {
                    Toast.makeText(this@MainActivity, "Crie um perfil primeiro.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                profileName.setText(p.optString("name"))
                profileEmoji.setText(p.optString("emoji", "⚡"))
                val actions = p.optJSONArray("actions") ?: JSONArray()
                for ((key, cb) in checkboxes) cb.isChecked = (0 until actions.length()).any { actions.optString(it) == key }
            }
        }
        root.addView(loadButton, LinearLayout.LayoutParams(-1, dp(48)))

        val applyButton = Button(this).apply {
            text = "APLICAR PERFIL SELECIONADO"
            setOnClickListener {
                val p = readCustomProfiles().optJSONObject(profileSpinner.selectedItemPosition)
                if (p == null) {
                    Toast.makeText(this@MainActivity, "Crie ou selecione um perfil salvo.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val actions = p.optJSONArray("actions") ?: JSONArray()
                val selectedActions = (0 until actions.length()).map { actions.optString(it) }.toSet()
                val usageProfiles = selectedActions.intersect(setOf("streaming", "efficiency", "cooling", "hard"))
                if (usageProfiles.size > 1) {
                    status.text = "Este perfil combina modos de energia incompatíveis."
                    Toast.makeText(this@MainActivity, "Escolha apenas um perfil integrado: streaming, eficiência, resfriamento ou HARD. Combine-o com ações individuais, se forem compatíveis.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val keepPackage = keepPackages.getOrNull(keepSpinner.selectedItemPosition) ?: packageName
                if ("hard" in selectedActions && keepPackage == packageName) {
                    status.text = "Selecione primeiro o app que deve continuar aberto para usar HARD."
                    Toast.makeText(this@MainActivity, "Escolha o app que deseja manter.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val label = p.optString("emoji", "⚡") + " " + p.optString("name", "Perfil")
                status.text = "Aplicando perfil personalizado: $label"
                // Executa as ações na ordem definida abaixo; cada ação reaproveita a rotina já existente.
                if ("close_apps" in selectedActions) optimize()
                if ("performance" in selectedActions) setAnimationMode(true)
                if ("max_refresh" in selectedActions) setRefreshRateMode(true)
                if ("dpi" in selectedActions) applyRecommendedDpi()
                if ("game" in selectedActions) setGameMode("performance")
                if ("streaming" in selectedActions) applyUsageProfile("streaming")
                if ("efficiency" in selectedActions) applyUsageProfile("efficiency")
                if ("cooling" in selectedActions) applyUsageProfile("cooling")
                if ("hard" in selectedActions) applyUsageProfile("hard")
                if ("clean_ram" in selectedActions) cleanRamAndBackgroundApps()
                if ("thermal" in selectedActions && ::autoThermalCheck.isInitialized) autoThermalCheck.isChecked = true
                if ("overlay" in selectedActions) startOverlay()
                if (selectedActions.isEmpty()) status.text = "Esse perfil não tem ações."
            }
        }
        root.addView(applyButton, LinearLayout.LayoutParams(-1, dp(54)))

        val deleteButton = Button(this).apply {
            text = "EXCLUIR PERFIL SELECIONADO"
            setOnClickListener {
                val profiles = readCustomProfiles()
                val selected = profileSpinner.selectedItemPosition
                if (selected !in 0 until profiles.length()) {
                    Toast.makeText(this@MainActivity, "Nenhum perfil para excluir.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val updated = JSONArray()
                for (i in 0 until profiles.length()) if (i != selected) updated.put(profiles.getJSONObject(i))
                getSharedPreferences("turbolite_profiles", MODE_PRIVATE).edit().putString("profiles", updated.toString()).apply()
                refreshProfiles((selected - 1).coerceAtLeast(0))
                Toast.makeText(this@MainActivity, "Perfil excluído.", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(deleteButton, LinearLayout.LayoutParams(-1, dp(48)))
    }

    private var previousCpuTotal = 0L
    private var previousCpuIdle = 0L

    private fun updateMonitorDashboard() {
        if (::performanceCard.isInitialized) updatePerformanceCard(performanceCard)
        if (::currentModeCard.isInitialized) updateCurrentModeCard()
        if (::monitorMetrics.isInitialized) {
            monitorMetrics.text = performanceCard.text.toString()
                .replace("STATUS DO SISTEMA", "INDICADORES DO SISTEMA") +
                "\nFPS: estimativa disponível no painel flutuante com Shizuku"
        }
        if (::monitorGauge.isInitialized) {
            val battery = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val raw = battery?.getIntExtra("temperature", -1) ?: -1
            monitorGauge.text = if (raw > 0) {
                String.format(java.util.Locale.US, "🌡  TEMPERATURA\n%.1f°C\n\nTemperatura da bateria", raw / 10f)
            } else {
                "🌡  TEMPERATURA\nSensor de bateria indisponível"
            }
        }
    }

    private fun updatePerformanceCard(card: TextView) {
        try {
            val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val memory = android.app.ActivityManager.MemoryInfo()
            manager.getMemoryInfo(memory)
            val totalMb = memory.totalMem / (1024L * 1024L)
            val freeMb = memory.availMem / (1024L * 1024L)
            val usedMb = (totalMb - freeMb).coerceAtLeast(0L)
            val batteryIntent = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra("level", -1) ?: -1
            val scale = batteryIntent?.getIntExtra("scale", 100) ?: 100
            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val tempTenths = batteryIntent?.getIntExtra("temperature", -1) ?: -1
            val temp = if (tempTenths >= 0) String.format(java.util.Locale.US, "%.1f°C", tempTenths / 10f) else "indisponível"
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            val now = android.os.SystemClock.elapsedRealtime()
            val network = if (rx >= 0L && tx >= 0L && previousRxBytes >= 0L && previousTxBytes >= 0L && now > previousNetworkAtMs) {
                val seconds = (now - previousNetworkAtMs) / 1000.0
                "↓ ${formatNetworkRate(((rx - previousRxBytes).coerceAtLeast(0L) / seconds).toLong())}  ↑ ${formatNetworkRate(((tx - previousTxBytes).coerceAtLeast(0L) / seconds).toLong())}"
            } else "medindo…"
            if (rx >= 0L && tx >= 0L) {
                previousRxBytes = rx
                previousTxBytes = tx
                previousNetworkAtMs = now
            }
            var thermalStatus = "indisponível"
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val power = getSystemService(POWER_SERVICE) as android.os.PowerManager
                thermalStatus = when (power.currentThermalStatus) {
                    android.os.PowerManager.THERMAL_STATUS_NONE -> "normal"
                    android.os.PowerManager.THERMAL_STATUS_LIGHT -> "leve"
                    android.os.PowerManager.THERMAL_STATUS_MODERATE -> "moderado"
                    android.os.PowerManager.THERMAL_STATUS_SEVERE -> "alto"
                    android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "crítico"
                    android.os.PowerManager.THERMAL_STATUS_EMERGENCY -> "emergência"
                    android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> "desligamento iminente"
                    else -> "indisponível"
                }
            }
            var cpuText = "indisponível"
            var cpuPercent: Int? = null
            try {
                val line = java.io.File("/proc/stat").bufferedReader().use { it.readLine() ?: "" }
                val values = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                if (values.size >= 4) {
                    val idle = values[3] + (values.getOrNull(4) ?: 0L)
                    val total = values.sum()
                    if (previousCpuTotal > 0L && total > previousCpuTotal) {
                        val usage = (((total - previousCpuTotal - (idle - previousCpuIdle)).toDouble() * 100.0) / (total - previousCpuTotal)).toInt().coerceIn(0, 100)
                        cpuText = "$usage%"
                        cpuPercent = usage
                    } else cpuText = "calculando…"
                    previousCpuTotal = total
                    previousCpuIdle = idle
                }
            } catch (_: Exception) { }
            card.text = "STATUS DO SISTEMA\n" +
                "CPU   $cpuText       RAM   ${if (totalMb > 0) usedMb * 100 / totalMb else 0}%\n" +
                "Bateria   " + (if (batteryPct >= 0) "$batteryPct%" else "indisponível") + "       ${temp}\n" +
                "RAM   $usedMb / $totalMb MB  •  livre $freeMb MB\n" +
                "Rede   $network  •  térmico $thermalStatus"
            if (selectedTabKey == "monitor") {
                metricHistory.add(MetricSample(cpuPercent, if (totalMb > 0) (usedMb * 100 / totalMb).toInt() else 0, tempTenths.takeIf { it > 0 }?.div(10f)))
                if (metricHistory.size > 60) metricHistory.removeAt(0)
                if (::monitorChart.isInitialized) monitorChart.setSamples(metricHistory.toList())
            }
        } catch (e: Exception) {
            card.text = "STATUS DE DESEMPENHO\\nNão foi possível ler todas as métricas neste Android."
        }
    }

    private fun updateCurrentModeCard() {
        val prefs = getSharedPreferences("turbolite_settings", MODE_PRIVATE)
        val mode = when {
            prefs.getBoolean("auto_thermal_cooling_active", false) -> "Resfriamento automático"
            prefs.getBoolean("usage_profile_saved", false) -> when (prefs.getString("active_usage_profile", "")) {
                "streaming" -> "Streaming • equilíbrio"
                "efficiency" -> "Eficiência • economia"
                "cooling", "auto_cooling" -> "Resfriamento"
                "hard" -> "HARD • economia extrema"
                else -> "Perfil de energia ativo"
            }
            prefs.getBoolean("animations_saved", false) -> "Desempenho da interface"
            else -> "Normal • padrão do Android"
        }
        currentModeCard.text = "MODO ATUAL\n$mode\nToque em um perfil para alterar"
    }

    private fun buildUi() {
        monitorHandler.removeCallbacks(monitorUpdater)
        thermalHandler.removeCallbacks(thermalMonitor)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(24))
            setBackgroundColor(0xFF070B16.toInt())
        }

        // Hero da identidade TurboLite: gamer profissional, azul profundo e ciano.
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(withAlpha(accentColor(), 0x34), 0xFF07111F.toInt())).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), withAlpha(accentColor(), 0x88))
            }
            elevation = dp(3).toFloat()
        }
        val brandMark = android.widget.ImageView(this).apply {
            setImageResource(com.vnsants.turbolite.R.drawable.ic_turbolite)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        hero.addView(brandMark, LinearLayout.LayoutParams(dp(66), dp(66)))
        val brandCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        val title = TextView(this).apply {
            text = "TurboLite"
            textSize = 29f
            setTextColor(0xFFF2F8FF.toInt())
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            letterSpacing = -0.025f
        }
        brandCopy.addView(title)
        val subtitle = TextView(this).apply {
            text = "MAIS CONTROLE. MAIS DESEMPENHO."
            textSize = 10f
            letterSpacing = 0.07f
            setTextColor(accentColor())
            setPadding(0, dp(4), 0, dp(5))
        }
        brandCopy.addView(subtitle)
        val version = TextView(this).apply {
            text = "ANDROID 14  •  SHIZUKU  •  SEM ROOT"
            textSize = 10f
            setTextColor(0xFF9CAAC4.toInt())
        }
        brandCopy.addView(version)
        hero.addView(brandCopy, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        val generalSection = TextView(this).apply {
            text = "CONTROLE RÁPIDO"
            textSize = 12f
            letterSpacing = 0.12f
            setTextColor(accentColor())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(16), 0, dp(8))
        }
        root.addView(generalSection)

        val keepLabel = TextView(this).apply {
            text = "Aplicativo que deve continuar aberto:"
            textSize = 15f
            setTextColor(0xFFE2E8F0.toInt())
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(keepLabel)

        keepSpinner = Spinner(this)
        root.addView(keepSpinner, LinearLayout.LayoutParams(-1, dp(52)))

        status = TextView(this).apply {
            text = "Verificando Shizuku…"
            textSize = 15f
            setTextColor(0xFFE2E8F0.toInt())
            setPadding(0, dp(12), 0, dp(18))
        }
        status.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(0xFF121D30.toInt())
            setStroke(dp(1), 0xFF263B57.toInt())
        }
        status.setPadding(dp(16), dp(14), dp(16), dp(14))
        status.minimumHeight = dp(52)
        status.elevation = dp(1).toFloat()
        root.addView(status)

        performanceCard = TextView(this).apply {
            text = "STATUS DO SISTEMA\nLendo dados do aparelho…"
            textSize = 14f
            setTextColor(0xFFE2E8F0.toInt()
            )
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(0xFF101B2D.toInt())
                setStroke(dp(1), withAlpha(accentColor(), 0x70))
            }
            elevation = dp(2).toFloat()
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        root.addView(performanceCard, LinearLayout.LayoutParams(-1, dp(160)).apply {
            topMargin = dp(12)
            bottomMargin = dp(8)
        })
        currentModeCard = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFEAF7FF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(withAlpha(accentColor(), 0x32), 0xFF0D1B2B.toInt())).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), withAlpha(accentColor(), 0x75))
            }
        }
        root.addView(currentModeCard, LinearLayout.LayoutParams(-1, dp(74)).apply { bottomMargin = dp(10) })
        updateCurrentModeCard()

        val refreshStatusButton = Button(this).apply {
            text = "ATUALIZAR STATUS DO CELULAR"
            setOnClickListener { updatePerformanceCard(performanceCard) }
        }
        root.addView(refreshStatusButton, LinearLayout.LayoutParams(-1, dp(48)))
        updatePerformanceCard(performanceCard)

        actionButton = Button(this).apply {
            text = "FECHAR OS OUTROS APPS"
            isEnabled = false
            setOnClickListener { optimize() }
        }
        root.addView(actionButton, LinearLayout.LayoutParams(-1, dp(58)))

        performanceButton = Button(this).apply {
            text = "ATIVAR MODO DESEMPENHO"
            setOnClickListener { setAnimationMode(true) }
        }
        root.addView(performanceButton, LinearLayout.LayoutParams(-1, dp(54)))

        restoreButton = Button(this).apply {
            text = "RESTAURAR ANIMAÇÕES / TELA"
            setOnClickListener { setAnimationMode(false); setRefreshRateMode(false) }
        }
        root.addView(restoreButton, LinearLayout.LayoutParams(-1, dp(54)))

        refreshRateButton = Button(this).apply {
            text = "TENTAR TAXA MÁXIMA DA TELA"
            setOnClickListener { setRefreshRateMode(true) }
        }
        root.addView(refreshRateButton, LinearLayout.LayoutParams(-1, dp(54)))

        val quickModesTitle = TextView(this).apply {
            text = "AÇÕES RÁPIDAS"
            textSize = 12f
            letterSpacing = 0.10f
            setTextColor(accentColor())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(6))
        }
        root.addView(quickModesTitle)
        fun quickMode(label: String, profile: String, tint: Int) = Button(this).apply {
            text = label
            setTextColor(tint)
            setOnClickListener { applyUsageProfile(profile) }
        }
        root.addView(quickMode("STREAMING\nEquilíbrio", "streaming", 0xFF39C6FF.toInt()), LinearLayout.LayoutParams(-1, dp(58)))
        root.addView(quickMode("EFICIÊNCIA\nMais autonomia", "efficiency", 0xFF43D9A3.toInt()), LinearLayout.LayoutParams(-1, dp(58)))
        root.addView(quickMode("RESFRIAMENTO\nReduzir consumo", "cooling", 0xFF58B9FF.toInt()), LinearLayout.LayoutParams(-1, dp(58)))
        val gameQuickButton = Button(this).apply {
            text = "MODO JOGO\nPerformance"
            setTextColor(0xFFFF7FA6.toInt())
            setOnClickListener { setGameMode("performance") }
        }
        root.addView(gameQuickButton, LinearLayout.LayoutParams(-1, dp(58)))
        val gamesStartIndex = root.childCount
        val gameSection = TextView(this).apply {
            text = "JOGOS E INTERFACE"
            textSize = 12f
            letterSpacing = 0.12f
            setTextColor(accentColor())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(18), 0, dp(8))
        }
        root.addView(gameSection)

        val dpiTitle = TextView(this).apply {
            text = "\nDPI / DENSIDADE PARA JOGOS"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, dp(4))
        }
        root.addView(dpiTitle)

        val dpiNote = TextView(this).apply {
            text = "Perfil recomendado: 90% da densidade atual (interface menor). DPI não aumenta FPS diretamente; use apenas se a interface continuar confortável. O valor é editável."
            textSize = 13f
            setTextColor(0xFFB8C4D9.toInt())
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(dpiNote)

        dpiInput = EditText(this).apply {
            hint = "DPI desejada (ex.: 360)"
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 16f
            setSingleLine(true)
        }
        root.addView(dpiInput, LinearLayout.LayoutParams(-1, dp(54)))

        val dpiRecommendedButton = Button(this).apply {
            text = "USAR DPI RECOMENDADA"
            setOnClickListener { applyRecommendedDpi() }
        }
        root.addView(dpiRecommendedButton, LinearLayout.LayoutParams(-1, dp(50)))

        dpiApplyButton = Button(this).apply {
            text = "APLICAR DPI PERSONALIZADA"
            setOnClickListener { applyCustomDpi() }
        }
        root.addView(dpiApplyButton, LinearLayout.LayoutParams(-1, dp(50)))

        dpiRestoreButton = Button(this).apply {
            text = "RESTAURAR DPI ORIGINAL"
            setOnClickListener { restoreDpi() }
        }
        root.addView(dpiRestoreButton, LinearLayout.LayoutParams(-1, dp(50)))

        val gamePerformanceButton = Button(this).apply {
            text = "MODO DE JOGO: PERFORMANCE"
            setOnClickListener { setGameMode("performance") }
        }
        root.addView(gamePerformanceButton, LinearLayout.LayoutParams(-1, dp(54)))

        val gameStandardButton = Button(this).apply {
            text = "MODO DE JOGO: PADRÃO ANDROID"
            setOnClickListener { setGameMode("standard") }
        }
        root.addView(gameStandardButton, LinearLayout.LayoutParams(-1, dp(54)))

        val gameToolsTitle = TextView(this).apply {
            text = "\nCENTRAL DE JOGO"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(4))
        }
        root.addView(gameToolsTitle)
        val gameToolsHint = TextView(this).apply {
            text = "Atalhos para o app selecionado. Foco de jogo usa Não Perturbe e exige acesso especial; desligue-o aqui para restaurar o filtro anterior. Recursos do Android/XOS variam por modelo."
            textSize = 13f
            setTextColor(0xFFB8C4D9.toInt())
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(gameToolsHint)
        root.addView(Button(this).apply {
            text = "ABRIR JOGO SELECIONADO"
            setOnClickListener { launchSelectedGame() }
        }, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(Button(this).apply {
            text = "SESSÃO COMPETITIVA • PERFORMANCE + TELA"
            setOnClickListener {
                setAnimationMode(true)
                setRefreshRateMode(true)
                setGameMode("performance")
            }
        }, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(Button(this).apply {
            text = "SESSÃO LONGA • ECONOMIA + JOGO"
            setOnClickListener {
                applyUsageProfile("efficiency")
                setGameMode("battery")
            }
        }, LinearLayout.LayoutParams(-1, dp(52)))
        gameFocusButton = Button(this).apply {
            text = gameFocusButtonLabel()
            setOnClickListener { toggleGameFocus() }
        }
        root.addView(gameFocusButton, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(Button(this).apply {
            text = "MOSTRAR INDICADORES FLUTUANTES"
            setOnClickListener { startOverlay() }
        }, LinearLayout.LayoutParams(-1, dp(52)))

        val energyStartIndex = root.childCount
        val energySection = TextView(this).apply {
            text = "PERFIS DE DESEMPENHO"
            textSize = 12f
            letterSpacing = 0.12f
            setTextColor(accentColor())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(20), 0, dp(8))
        }
        root.addView(energySection)
        val mediaTitle = TextView(this).apply {
            text = "\nESCOLHA UM PERFIL PRONTO"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(4))
        }
        root.addView(mediaTitle)
        val mediaNote = TextView(this).apply {
            text = "Selecione um perfil para solicitar ajustes reversíveis. Streaming mantém apps abertos; eficiência solicita economia do Android; resfriamento também tenta limitar CPU quando o fabricante permite. A XOS pode ignorar ajustes e nenhum deles garante menor temperatura ou mais FPS."
            textSize = 13f
            setTextColor(0xFFB8C4D9.toInt())
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(mediaNote)
        val streamingButton = Button(this).apply {
            text = "MODO STREAMING • EQUILÍBRIO"
            setOnClickListener { applyUsageProfile("streaming") }
        }
        root.addView(streamingButton, LinearLayout.LayoutParams(-1, dp(52)))
        val efficiencyButton = Button(this).apply {
            text = "MODO EFICIÊNCIA • ECONOMIA MODERADA"
            setOnClickListener { applyUsageProfile("efficiency") }
        }
        root.addView(efficiencyButton, LinearLayout.LayoutParams(-1, dp(52)))
        val coolingButton = Button(this).apply {
            text = "RESFRIAMENTO RÁPIDO • ECONOMIA MÁXIMA"
            setOnClickListener { applyUsageProfile("cooling") }
        }
        root.addView(coolingButton, LinearLayout.LayoutParams(-1, dp(52)))
        val hardModeButton = Button(this).apply {
            text = "☠ MODO HARD • ECONOMIA EXTREMA"
            setOnClickListener { applyUsageProfile("hard") }
        }
        root.addView(hardModeButton, LinearLayout.LayoutParams(-1, dp(58)))
        autoThermalCheck = CheckBox(this).apply {
            text = "AUTOMÁTICO: reduzir consumo quando esquentar"
            textSize = 14f
            setTextColor(0xFFE2E8F0.toInt())
            isChecked = getSharedPreferences("turbolite_settings", MODE_PRIVATE).getBoolean("auto_thermal_monitor_enabled", false)
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences("turbolite_settings", MODE_PRIVATE).edit()
                    .putBoolean("auto_thermal_monitor_enabled", checked).apply()
                thermalHandler.removeCallbacks(thermalMonitor)
                if (checked) {
                    status.text = "Monitor térmico ativado. A XOS pode ocultar a temperatura real."
                    thermalHandler.post(thermalMonitor)
                } else {
                    val prefs = getSharedPreferences("turbolite_settings", MODE_PRIVATE)
                    if (prefs.getBoolean("auto_thermal_cooling_active", false)) {
                        status.text = "Monitor desativado. Restaurando o perfil térmico aplicado…"
                        restoreUsageProfile()
                    } else {
                        status.text = "Monitor térmico automático desativado."
                    }
                }
            }
        }
        root.addView(autoThermalCheck)
        val thermalHint = TextView(this).apply {
            text = "Verifica a temperatura a cada 20 s enquanto o TurboLite estiver aberto. Ao detectar calor elevado, ativa economia e tenta reduzir a CPU; quando esfria, restaura as configurações salvas. A leitura pode não estar disponível em todas as versões da XOS."
            textSize = 12f
            setTextColor(0xFF9CAAC4.toInt())
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(thermalHint)

        val cleanRamButton = Button(this).apply {
            text = "COMBO: FECHAR APPS SELECIONÁVEIS"
            setOnClickListener { cleanRamAndBackgroundApps() }
        }
        root.addView(cleanRamButton, LinearLayout.LayoutParams(-1, dp(52)))
        val restoreUsageButton = Button(this).apply {
            text = "RESTAURAR ENERGIA / CPU / TELA"
            setOnClickListener { restoreUsageProfile() }
        }
        root.addView(restoreUsageButton, LinearLayout.LayoutParams(-1, dp(52)))

        // Perfis ficam numa tela própria, separados das ações rápidas.
        val profilesStartIndex = root.childCount
        addCustomProfilesSection(root)
        val toolsStartIndex = root.childCount

        val toolsSection = TextView(this).apply {
            text = "PAINEL E MONITORAMENTO"
            textSize = 12f
            letterSpacing = 0.12f
            setTextColor(accentColor())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(20), 0, dp(8))
        }
        root.addView(toolsSection)
        val overlayTitle = TextView(this).apply {
            text = "\nPAINEL FLUTUANTE"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, dp(4))
        }
        root.addView(overlayTitle)

        overlayButton = Button(this).apply {
            text = "MOSTRAR PAINEL FLUTUANTE"
            setOnClickListener { startOverlay() }
        }
        root.addView(overlayButton, LinearLayout.LayoutParams(-1, dp(52)))

        stopOverlayButton = Button(this).apply {
            text = "FECHAR PAINEL FLUTUANTE"
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
        }
        root.addView(stopOverlayButton, LinearLayout.LayoutParams(-1, dp(48)))

        fun addMetricToggle(label: String, key: String, defaultValue: Boolean) {
            val prefs = getSharedPreferences("turbolite_overlay", MODE_PRIVATE)
            val toggle = CheckBox(this).apply {
                text = label
                textSize = 14f
                setTextColor(0xFFE2E8F0.toInt())
                isChecked = prefs.getBoolean(key, defaultValue)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean(key, checked).apply()
                }
            }
            root.addView(toggle)
        }
        addMetricToggle("Mostrar RAM", "show_ram", true)
        addMetricToggle("Mostrar ping (teste para 1.1.1.1)", "show_ping", false)
        addMetricToggle("Mostrar tráfego de rede do aparelho", "show_network", true)
        addMetricToggle("Mostrar FPS (estimativa; depende do Android)", "show_fps", true)
        addMetricToggle("Mostrar uso de CPU (Shizuku)", "show_cpu", false)
        addMetricToggle("Mostrar temperatura térmica (se o fabricante liberar)", "show_temp", false)

        val monitorHeading = TextView(this).apply {
            text = "MONITORAMENTO"
            textSize = 22f
            setTextColor(0xFFF2F8FF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(monitorHeading)
        val monitorSubtitle = TextView(this).apply {
            text = "Acompanhe os indicadores disponíveis no Android. Algumas métricas dependem do fabricante e do Shizuku."
            textSize = 13f
            setTextColor(0xFF9CAAC4.toInt())
            setPadding(0, 0, 0, dp(14))
        }
        root.addView(monitorSubtitle)

        monitorGauge = TextView(this).apply {
            text = "🌡  TEMPERATURA\nLendo sensores…"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(0xFFEAF7FF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(16), dp(20), dp(16), dp(20))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(withAlpha(accentColor(), 0x28), 0xFF0B192B.toInt())).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), withAlpha(accentColor(), 0x80))
            }
            elevation = dp(2).toFloat()
        }
        root.addView(monitorGauge, LinearLayout.LayoutParams(-1, dp(150)).apply { bottomMargin = dp(12) })

        monitorMetrics = TextView(this).apply {
            text = "◉ CPU   —\n▦ RAM   —\n▣ BATERIA   —\n◷ FPS   Não disponível pelo Android comum"
            textSize = 15f
            setTextColor(0xFFE2EAF5.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFF0E1B2D.toInt())
                setStroke(dp(1), 0xFF263B55.toInt())
            }
            elevation = dp(1).toFloat()
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        root.addView(monitorMetrics, LinearLayout.LayoutParams(-1, dp(150)).apply { bottomMargin = dp(10) })
        val chartTitle = TextView(this).apply {
            text = "HISTÓRICO AO VIVO • ATÉ 5 MIN"
            textSize = 12f
            letterSpacing = 0.08f
            setTextColor(accentColor())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), dp(12), dp(2), dp(6))
        }
        root.addView(chartTitle)
        monitorChart = MetricChartView(this)
        root.addView(monitorChart, LinearLayout.LayoutParams(-1, dp(190)).apply { bottomMargin = dp(12) })

        val monitorRefresh = Button(this).apply {
            text = "ATUALIZAR MONITORAMENTO"
            setOnClickListener {
                updateMonitorDashboard()
            }
        }
        root.addView(monitorRefresh, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(16) })

        val monitorNote = TextView(this).apply {
            text = "LIMITAÇÕES E LEITURAS\nCPU é o uso total do sistema; FPS por aplicativo não é exposto universalmente pelo Android comum. Temperatura é da bateria, pois a XOS pode ocultar outros sensores. O gráfico coleta enquanto Monitoramento está aberto: CPU/RAM em %, temperatura mapeada entre 25–50 °C."
            textSize = 12f
            setTextColor(0xFF91A6BF.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFF0B1422.toInt())
            }
        }
        root.addView(monitorNote)

        val settingsStartIndex = root.childCount
        val settingsHeading = TextView(this).apply {
            text = "CONFIGURAÇÕES"
            textSize = 22f
            setTextColor(0xFFF2F8FF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(settingsHeading)
        val settingsDescription = TextView(this).apply {
            text = "Permissões, atualização dos aplicativos e informações importantes sobre os recursos do TurboLite."
            textSize = 13f
            setTextColor(0xFF9CAAC4.toInt())
            setPadding(0, 0, 0, dp(14))
        }
        root.addView(settingsDescription)
        permissionCard = TextView(this).apply {
            text = "SHIZUKU / PERMISSÕES\nVerificando conexão e autorização…"
            textSize = 14f
            setTextColor(0xFFE5F0FF.toInt())
            setPadding(dp(15), dp(14), dp(15), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(0xFF101B2D.toInt())
                setStroke(dp(1), 0xFF263B55.toInt())
            }
        }
        root.addView(permissionCard, LinearLayout.LayoutParams(-1, dp(82)))
        val overlayPermissionButton = Button(this).apply {
            text = "PERMISSÃO DE PAINEL SOBRE OUTROS APPS"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        root.addView(overlayPermissionButton, LinearLayout.LayoutParams(-1, dp(52)))
        reloadButton = Button(this).apply {
            text = "ATUALIZAR LISTA / PERMISSÃO"
            setOnClickListener {
                loadAppsToKeep()
                updatePermissionState()
            }
        }
        root.addView(reloadButton, LinearLayout.LayoutParams(-1, dp(52)))
        val restoreAllButton = Button(this).apply {
            text = "RESTAURAR CONFIGURAÇÕES ORIGINAIS"
            setOnClickListener {
                setAnimationMode(false)
                setRefreshRateMode(false)
                restoreDpi()
                restoreUsageProfile()
            }
        }
        root.addView(restoreAllButton, LinearLayout.LayoutParams(-1, dp(54)))

        val accentButton = Button(this).apply {
            val names = listOf("Ciano", "Violeta", "Verde")
            val selected = getSharedPreferences("turbolite_settings", MODE_PRIVATE).getString("accent_theme", "cyan")
            text = "COR DE DESTAQUE: " + when (selected) { "violet" -> names[1]; "green" -> names[2]; else -> names[0] }
            setOnClickListener {
                val keys = arrayOf("cyan", "violet", "green")
                val labels = arrayOf("Ciano", "Violeta", "Verde")
                val current = getSharedPreferences("turbolite_settings", MODE_PRIVATE).getString("accent_theme", "cyan")
                val index = keys.indexOf(current).coerceAtLeast(0)
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Personalizar cor de destaque")
                    .setSingleChoiceItems(labels, index) { dialog, which ->
                        getSharedPreferences("turbolite_settings", MODE_PRIVATE).edit().putString("accent_theme", keys[which]).apply()
                        dialog.dismiss()
                        buildUi()
                        loadAppsToKeep()
                        updatePermissionState()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
        root.addView(accentButton, LinearLayout.LayoutParams(-1, dp(50)))

        val note = TextView(this).apply {
            text = "\nO que esta beta faz:\n" +
                "• Mantém o TurboLite e o app escolhido na lista.\n" +
                "• Força a parada de outros apps instalados pelo usuário.\n" +
                "• Não força a parada de apps de sistema.\n" +
                "• Não apaga cache, dados ou logins.\n" +
                "• Modo desempenho: reduz animações do Android para 0,5x; não aumenta diretamente o FPS.\n" +
                "• Modo de jogo: solicita performance ou padrão via cmd game para o app selecionado; a XOS pode não suportar o recurso.\n" +
                "• Padrão Android não recupera um perfil proprietário anterior da XOS; use-o apenas se quiser remover o modo Performance.\n" +
                "• Restaurar: volta às escalas de animação guardadas antes da primeira ativação.\n\n" +
                "Antes de fechar apps, selecione o jogo que quer manter. Apps fechados podem perder downloads, reprodução, VPN ou notificações temporariamente; o Android pode reiniciá-los. " +
                "A restauração recupera as animações, mas não reabre automaticamente os apps fechados. " +
                "Esta beta não ativa automaticamente o modo gamer da Infinix."
            textSize = 14f
            setTextColor(0xFFB8C4D9.toInt())
            setPadding(0, dp(16), 0, dp(10))
        }
        root.addView(note)

        // Cada destino reúne controles do mesmo propósito, preservando as ações existentes.
        val monitorStartIndex = root.indexOfChild(monitorHeading)
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            child.tag = when {
                index < gamesStartIndex -> "home"
                index < energyStartIndex -> "games"
                index < profilesStartIndex -> "profiles"
                index < toolsStartIndex -> "profiles"
                index < monitorStartIndex -> "settings"
                index < settingsStartIndex -> "monitor"
                else -> "settings"
            }
        }
        pairButtons(root, "FECHAR OS OUTROS APPS", "ATIVAR MODO DESEMPENHO")
        pairButtons(root, "STREAMING\nEQUILÍBRIO", "EFICIÊNCIA\nMAIS AUTONOMIA")
        pairButtons(root, "RESFRIAMENTO\nREDUZIR CONSUMO", "MODO JOGO\nPERFORMANCE")
        pairButtons(root, "MODO STREAMING • EQUILÍBRIO", "MODO EFICIÊNCIA • ECONOMIA MODERADA")
        pairButtons(root, "RESFRIAMENTO RÁPIDO • ECONOMIA MÁXIMA", "MODO HARD")
        pairButtons(root, "USAR DPI RECOMENDADA", "APLICAR DPI PERSONALIZADA")
        pairButtons(root, "MODO DE JOGO: PERFORMANCE", "MODO DE JOGO: PADRÃO ANDROID")
        pairButtons(root, "MOSTRAR PAINEL FLUTUANTE", "FECHAR PAINEL FLUTUANTE")
        pairButtons(root, "ATUALIZAR LISTA / PERMISSÃO", "RESTAURAR CONFIGURAÇÕES ORIGINAIS")

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            setBackgroundColor(0xFF0B1020.toInt())
            addView(root)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF070B16.toInt())
        }
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(7), dp(6), dp(7))
            background = GradientDrawable().apply {
                setColor(0xFF0B1423.toInt())
                setStroke(dp(1), 0xFF24364F.toInt())
            }
            elevation = dp(5).toFloat()
        }
        val navItems = listOf(
            Triple("⌂", "Início", "home"),
            Triple("◉", "Perfis", "profiles"),
            Triple("◷", "Monitor", "monitor"),
            Triple("✦", "Jogos", "games"),
            Triple("☷", "Mais", "settings")
        )
        val navButtons = mutableMapOf<String, TextView>()
        fun selectTab(tab: String) {
            selectedTabKey = tab
            getSharedPreferences("turbolite_settings", MODE_PRIVATE).edit().putString("selected_tab", tab).apply()
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                child.visibility = if (child.tag == tab) android.view.View.VISIBLE else android.view.View.GONE
            }
            navButtons.forEach { (key, button) ->
                val selected = key == tab
                button.setTextColor(if (selected) accentColor() else 0xFF8EA3BB.toInt())
                button.isSelected = selected
                button.background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(withAlpha(accentColor(), 0x48)),
                    GradientDrawable().apply {
                        cornerRadius = dp(15).toFloat()
                        setColor(if (selected) withAlpha(accentColor(), 0x24) else 0x00000000)
                        if (selected) setStroke(dp(1), withAlpha(accentColor(), 0xA0))
                    },
                    null
                )
            }
            scroll.scrollTo(0, 0)
            monitorHandler.removeCallbacks(monitorUpdater)
            if (tab == "monitor") {
                updateMonitorDashboard()
                monitorHandler.postDelayed(monitorUpdater, 5_000L)
            }
        }
        for ((symbol, label, tab) in navItems) {
            val button = TextView(this).apply {
                text = "$symbol\n$label"
                gravity = Gravity.CENTER
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF8EA3BB.toInt())
                setPadding(dp(3), dp(7), dp(3), dp(7))
                isClickable = true
                isFocusable = true
                contentDescription = label
                minHeight = dp(52)
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(withAlpha(accentColor(), 0x48)),
                    GradientDrawable().apply {
                        cornerRadius = dp(15).toFloat()
                        setColor(0x00000000)
                    },
                    null
                )
                setOnClickListener { selectTab(tab) }
            }
            navButtons[tab] = button
            nav.addView(button, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                leftMargin = dp(3)
                rightMargin = dp(3)
            })
        }
        page.addView(nav, LinearLayout.LayoutParams(-1, -2))
        setContentView(page)
        styleControls(root)
        selectTab(getSharedPreferences("turbolite_settings", MODE_PRIVATE).getString("selected_tab", "home") ?: "home")
        if (getSharedPreferences("turbolite_settings", MODE_PRIVATE).getBoolean("auto_thermal_monitor_enabled", false)) {
            thermalHandler.post(thermalMonitor)
        }
    }

    private fun pairButtons(root: LinearLayout, firstText: String, secondText: String) {
        fun findIndex(needle: String) = (0 until root.childCount).firstOrNull { index ->
            val child = root.getChildAt(index)
            child is Button && child.text.toString().uppercase(java.util.Locale.ROOT).contains(needle)
        }
        val firstIndex = findIndex(firstText) ?: return
        val secondIndex = findIndex(secondText) ?: return
        if (firstIndex == secondIndex) return
        val first = root.getChildAt(firstIndex) as Button
        val second = root.getChildAt(secondIndex) as Button
        if (first.tag != second.tag) return
        val insertAt = minOf(firstIndex, secondIndex)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = first.tag
        }
        root.removeViewAt(maxOf(firstIndex, secondIndex))
        root.removeViewAt(insertAt)
        row.addView(first, LinearLayout.LayoutParams(0, dp(64), 1f).apply {
            rightMargin = dp(4)
        })
        row.addView(second, LinearLayout.LayoutParams(0, dp(64), 1f).apply {
            leftMargin = dp(4)
        })
        root.addView(row, insertAt)
    }

    private inner class MetricChartView(context: Context) : View(context) {
        private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B1726.toInt() }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF263B55.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat()
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF26364A.toInt(); strokeWidth = dp(1).toFloat()
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(10).toFloat() }
        private val seriesColors = intArrayOf(0xFF00D4FF.toInt(), 0xFF54E6A5.toInt(), 0xFFFFB45E.toInt())
        private val seriesPaths = Array(3) { Path() }
        private val seriesPaints = Array(3) { index ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = seriesColors[index]; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat()
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
        }
        private var samples: List<MetricSample> = emptyList()

        fun setSamples(value: List<MetricSample>) {
            samples = value
            val latest = value.lastOrNull()
            contentDescription = latest?.let {
                "Medições recentes. CPU ${it.cpu?.let { cpu -> "$cpu por cento" } ?: "indisponível"}; RAM ${it.ram} por cento; temperatura ${it.temperature?.let { temp -> String.format(java.util.Locale.getDefault(), "%.1f graus", temp) } ?: "indisponível"}."
            } ?: "Gráfico de CPU, RAM e temperatura aguardando leituras."
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = dp(12).toFloat()
            val radius = dp(16).toFloat()
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, backgroundPaint)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, borderPaint)
            val left = inset
            val top = inset
            val right = width - inset
            val bottom = height - dp(32).toFloat()
            for (line in 0..3) {
                val y = top + (bottom - top) * line / 3f
                canvas.drawLine(left, y, right, y, gridPaint)
            }
            if (samples.size < 2) {
                textPaint.color = 0xFF91A6BF.toInt()
                canvas.drawText("Aguardando leituras para formar o histórico", left, height / 2f, textPaint)
            } else {
                for (series in 0..2) {
                    val path = seriesPaths[series]
                    path.reset()
                    var started = false
                    val denom = (samples.size - 1).coerceAtLeast(1)
                    samples.forEachIndexed { index, sample ->
                        val raw = when (series) {
                            0 -> sample.cpu?.toFloat()
                            1 -> sample.ram.toFloat()
                            else -> sample.temperature?.let { c -> (c - 25f) * 4f }
                        } ?: return@forEachIndexed
                        val value = raw.coerceIn(0f, 100f)
                        val x = left + (right - left) * index / denom.toFloat()
                        val y = bottom - (bottom - top) * value / 100f
                        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                    }
                    if (started) canvas.drawPath(path, seriesPaints[series])
                }
            }
            val legendY = height - dp(12).toFloat()
            textPaint.color = 0xFF00D4FF.toInt(); canvas.drawText("● CPU", left, legendY, textPaint)
            textPaint.color = 0xFF54E6A5.toInt(); canvas.drawText("● RAM", left + dp(68), legendY, textPaint)
            textPaint.color = 0xFFFFB45E.toInt(); canvas.drawText("● Temperatura", left + dp(136), legendY, textPaint)
        }
    }

    private fun styleControls(view: android.view.View) {
        if (view is Button) {
            val label = view.text.toString().uppercase(java.util.Locale.ROOT)
            val primary = label.contains("FECHAR OS OUTROS APPS") ||
                label.contains("APLICAR PERFIL SELECIONADO") ||
                label.contains("ATIVAR MODO HARD") ||
                label.contains("SALVAR / ATUALIZAR PERFIL")
            val caution = label.contains("HARD") || label.contains("EXCLUIR")
            val shape = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                if (primary) intArrayOf(lighten(accentColor(), 0.12f), accentColor())
                else intArrayOf(0xFF17263A.toInt(), 0xFF101A2A.toInt())
            ).apply {
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), if (primary) withAlpha(accentColor(), 0xD0) else 0xFF2A3D57.toInt())
            }
            view.background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(if (primary) 0x44FFFFFF else withAlpha(accentColor(), 0x35)),
                shape,
                shape.constantState?.newDrawable()?.mutate()
            )
            view.elevation = if (primary) dp(2).toFloat() else 0f
            view.minimumHeight = maxOf(view.minimumHeight, dp(48))
            val actionColor = when {
                label.contains("STREAMING") -> 0xFF65D2FF.toInt()
                label.contains("EFICIÊNCIA") -> 0xFF69E5B0.toInt()
                label.contains("RESFRIAMENTO") -> 0xFF83C9FF.toInt()
                label.contains("MODO JOGO") -> 0xFFFF8CA9.toInt()
                else -> 0xFFEAF0F8.toInt()
            }
            view.setTextColor(if (primary) 0xFF07111C.toInt() else if (caution) 0xFFFFB4C4.toInt() else actionColor)
            view.textSize = 13f
            view.typeface = Typeface.DEFAULT_BOLD
            view.isAllCaps = false
            view.gravity = Gravity.CENTER
            view.maxLines = 2
            view.setPadding(dp(8), dp(7), dp(8), dp(7))
            val params = view.layoutParams
            if (params is LinearLayout.LayoutParams) {
                params.bottomMargin = dp(7)
                view.layoutParams = params
            }
        } else if (view is TextView && view.text.toString().uppercase(java.util.Locale.ROOT) in setOf(
                "CONTROLE RÁPIDO", "JOGOS E INTERFACE", "PERFIS DE DESEMPENHO",
                "PERFIS PERSONALIZADOS", "PAINEL E MONITORAMENTO", "MONITORAMENTO",
                "CONFIGURAÇÕES", "JOGOS E INTERFACE", "AÇÕES RÁPIDAS", "HISTÓRICO AO VIVO • ATÉ 5 MIN"
            )) {
            view.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xFF101A2A.toInt())
                setStroke(dp(1), withAlpha(accentColor(), 0x35))
            }
            view.setPadding(dp(12), dp(12), dp(12), dp(12))
            view.textSize = 12f
            view.letterSpacing = 0.08f
            view.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            val params = view.layoutParams
            if (params is LinearLayout.LayoutParams) {
                params.topMargin = dp(14)
                params.bottomMargin = dp(8)
                view.layoutParams = params
            }
        } else if (view is CheckBox) {
            view.setTextColor(0xFFE2E8F0.toInt())
            view.buttonTintList = android.content.res.ColorStateList.valueOf(accentColor())
            view.minHeight = dp(48)
            view.setPadding(dp(10), dp(8), dp(10), dp(8))
        } else if (view is Spinner) {
            view.setBackground(GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFF121D30.toInt())
                setStroke(dp(1), 0xFF30415A.toInt())
            })
            view.minimumHeight = maxOf(view.minimumHeight, dp(48))
        } else if (view is EditText) {
            view.setTextColor(0xFFFFFFFF.toInt())
            view.setHintTextColor(0xFF8292AD.toInt())
            view.setPadding(dp(14), dp(8), dp(14), dp(8))
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(0xFF121D30.toInt())
                setStroke(dp(1), 0xFF263B57.toInt())
            }
        }
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) styleControls(view.getChildAt(index))
        }
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            Toast.makeText(this, "Autorize 'sobrepor a outros apps' e toque no botão novamente.", Toast.LENGTH_LONG).show()
            return
        }
        startService(Intent(this, OverlayService::class.java))
        Toast.makeText(this, "Painel flutuante iniciado", Toast.LENGTH_SHORT).show()
    }

    private fun loadAppsToKeep() {
        status.text = "Carregando lista de apps…"
        reloadButton.isEnabled = false
        executor.execute {
            try {
                val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    .asSequence()
                    .filter { app ->
                        (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                            packageManager.getLaunchIntentForPackage(app.packageName) != null
                    }
                    .map { app ->
                        app to packageManager.getApplicationLabel(app).toString()
                    }
                    .sortedBy { (_, label) -> label.lowercase(java.util.Locale.ROOT) }
                    .toList()

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    userApps = apps.map { it.first }
                    val labels = mutableListOf("TurboLite (manter aberto)")
                    val packages = mutableListOf(packageName)
                    apps.filter { it.first.packageName != packageName }.forEach { (app, label) ->
                        labels.add("$label • ${app.packageName}")
                        packages.add(app.packageName)
                    }
                    keepPackages = packages

                    val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
                        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    }
                    keepSpinner.adapter = adapter
                    val prefs = getSharedPreferences("turbolite_settings", MODE_PRIVATE)
                    val savedPackage = prefs.getString("keep_package", packageName)
                    val savedIndex = keepPackages.indexOf(savedPackage).takeIf { it >= 0 } ?: 0
                    keepSpinner.setSelection(savedIndex)
                    keepSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                            keepPackages.getOrNull(position)?.let { prefs.edit().putString("keep_package", it).apply() }
                        }
                    }
                    status.text = "Lista carregada: ${labels.size - 1} apps disponíveis para manter."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    status.text = "Não consegui listar os apps: ${e.message ?: "erro desconhecido"}"
                }
            } finally {
                runOnUiThread { if (!isFinishing && !isDestroyed) reloadButton.isEnabled = true }
            }
        }
    }

    private fun updatePermissionState() {
        try {
            if (Shizuku.isPreV11()) {
                status.text = "Versão do Shizuku não compatível. Atualize o Shizuku."
                actionButton.isEnabled = false
            } else if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    status.text = "Shizuku conectado e autorizado."
                    actionButton.isEnabled = true
                } else {
                    status.text = "Shizuku conectado, falta autorizar."
                    actionButton.isEnabled = false
                    if (!permissionRequestInFlight) {
                        permissionRequestInFlight = true
                        Shizuku.requestPermission(1001)
                    }
                }
            } else {
                status.text = "Abra o Shizuku, inicie o serviço (depuração sem fio) e volte aqui."
                actionButton.isEnabled = false
            }
        } catch (e: Exception) {
            permissionRequestInFlight = false
            status.text = "Shizuku indisponível: ${e.message ?: "verifique a configuração"}"
            actionButton.isEnabled = false
        }
        if (::permissionCard.isInitialized) permissionCard.text = "SHIZUKU / PERMISSÕES\n${status.text}"
    }


    private fun hasShizukuPermission(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    private fun runShell(command: String): Pair<Int, String> =
        ShizukuCommandClient.run(this, command, 10_000L)

    private fun setAnimationMode(enable: Boolean) {
        if (!hasShizukuPermission()) {
            updatePermissionState()
            Toast.makeText(this, "Inicie e autorize o Shizuku primeiro.", Toast.LENGTH_LONG).show()
            return
        }
        performanceButton.isEnabled = false
        restoreButton.isEnabled = false
        status.text = if (enable) "Salvando configurações e ativando modo desempenho…" else "Restaurando animações…"

        executor.execute {
            try {
                val prefs = getSharedPreferences("turbolite_settings", MODE_PRIVATE)
                val keys = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
                if (enable) {
                    if (!prefs.getBoolean("animations_saved", false)) {
                        val editor = prefs.edit()
                        for (key in keys) {
                            val result = runShell("settings get global $key")
                            if (result.first != 0) throw IllegalStateException("Não foi possível ler $key")
                            editor.putString(key, result.second.ifBlank { "1" })
                        }
                        editor.putBoolean("animations_saved", true).apply()
                    }
                    for (key in keys) {
                        val result = runShell("settings put global $key 0.5")
                        if (result.first != 0) throw IllegalStateException("Falha ao alterar $key")
                    }
                    runOnUiThread {
                        status.text = "Modo desempenho ativado: animações em 0,5x. Deixa a interface mais rápida, mas não garante mais FPS."
                        if (::currentModeCard.isInitialized) updateCurrentModeCard()
                        Toast.makeText(this, "Modo desempenho ativado", Toast.LENGTH_LONG).show()
                    }
                } else if (!prefs.getBoolean("animations_saved", false)) {
                    runOnUiThread {
                        status.text = "Nenhuma configuração salva. Ative o modo desempenho uma vez antes de restaurar."
                    }
                } else {
                    for (key in keys) {
                        val value = prefs.getString(key, "1") ?: "1"
                        val safeValue = if (value.matches(Regex("[0-9]+(\\.[0-9]+)?"))) value else "1"
                        val result = runShell("settings put global $key $safeValue")
                        if (result.first != 0) throw IllegalStateException("Falha ao restaurar $key")
                    }
                    prefs.edit().putBoolean("animations_saved", false).apply()
                    runOnUiThread {
                        status.text = "Animações originais restauradas."
                        if (::currentModeCard.isInitialized) updateCurrentModeCard()
                        Toast.makeText(this, "Configurações restauradas", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Não foi possível aplicar a alteração: ${e.message ?: "erro desconhecido"}. Verifique o Shizuku."
                }
            } finally {
                runOnUiThread {
                    performanceButton.isEnabled = true
                    restoreButton.isEnabled = true
                }
            }
        }
    }


    private fun setRefreshRateMode(enable: Boolean) {
        if (!hasShizukuPermission()) {
            updatePermissionState()
            Toast.makeText(this, "Inicie e autorize o Shizuku primeiro.", Toast.LENGTH_LONG).show()
            return
        }
        refreshRateButton.isEnabled = false
        executor.execute {
            try {
                val prefs = getSharedPreferences("turbolite_settings", MODE_PRIVATE)
                val savedKey = "refresh_rates_saved"
                if (enable) {
                    if (!prefs.getBoolean(savedKey, false)) {
                        val peakResult = runShell("settings get system peak_refresh_rate")
                        val minResult = runShell("settings get system min_refresh_rate")
                        if (peakResult.first != 0 || minResult.first != 0) {
                            throw IllegalStateException("Não foi possível ler as preferências atuais da tela")
                        }
                        prefs.edit()
                            .putString("peak_refresh_rate", peakResult.second.ifBlank { "null" })
                            .putString("min_refresh_rate", minResult.second.ifBlank { "null" })
                            .putBoolean(savedKey, true).apply()
                    }
                    val dump = runShell("dumpsys display")
                    if (dump.first != 0) throw IllegalStateException("O Android não permitiu consultar os modos de tela")
                    val rates = Regex("refreshRate=([0-9]+(?:\\.[0-9]+)?)")
                        .findAll(dump.second).mapNotNull { it.groupValues[1].toFloatOrNull() }
                        .filter { it in 30f..240f }.toList()
                    val maxRate = rates.maxOrNull()
                        ?: throw IllegalStateException("O Android não informou os modos de tela")
                    val value = String.format(java.util.Locale.US, "%.2f", maxRate)
                    val peakResult = runShell("settings put system peak_refresh_rate $value")
                    val minResult = runShell("settings put system min_refresh_rate $value")
                    if (peakResult.first != 0 || minResult.first != 0) {
                        throw IllegalStateException("O fabricante recusou a preferência; restaure para voltar ao estado salvo")
                    }
                    runOnUiThread {
                        status.text = "Solicitado modo $value Hz. A XOS pode ignorar a preferência; confirme nas configurações da tela."
                        Toast.makeText(this, "Preferência de $value Hz solicitada", Toast.LENGTH_LONG).show()
                    }
                } else if (prefs.getBoolean(savedKey, false)) {
                    val failures = mutableListOf<String>()
                    for ((prefKey, setting) in listOf("peak_refresh_rate" to "peak_refresh_rate", "min_refresh_rate" to "min_refresh_rate")) {
                        val old = prefs.getString(prefKey, "null") ?: "null"
                        val result = if (old == "null") runShell("settings delete system $setting")
                        else if (old.matches(Regex("[0-9]+(\\.[0-9]+)?"))) runShell("settings put system $setting $old")
                        else {
                            failures.add(setting)
                            continue
                        }
                        if (result.first != 0) failures.add(setting)
                    }
                    if (failures.isNotEmpty()) throw IllegalStateException("Falha ao restaurar ${failures.joinToString()}; os valores originais continuam salvos")
                    prefs.edit().putBoolean(savedKey, false).apply()
                    runOnUiThread { status.text = "Preferências de taxa de atualização restauradas." }
                } else {
                    runOnUiThread { status.text = "Nenhuma preferência de Hz salva para restaurar." }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Taxa de atualização não concluída: ${e.message ?: "recurso bloqueado pelo sistema"}" }
            } finally {
                runOnUiThread { refreshRateButton.isEnabled = true }
            }
        }
    }

    private fun readDpiState(): Pair<Int?, Boolean> {
        val result = runShell("wm density")
        if (result.first != 0) throw IllegalStateException(result.second.ifBlank { "Android não permitiu ler a densidade" })
        val output = result.second
        val override = Regex("Override density:\\s*(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull()
        val physical = Regex("Physical density:\\s*(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull()
        return Pair(override ?: physical, override != null)
    }

    private fun applyDpiOnWorker(value: Int) {
        val prefs = getSharedPreferences("turbolite_settings", MODE_PRIVATE)
        if (!prefs.getBoolean("dpi_saved", false)) {
            val state = readDpiState()
            prefs.edit()
                .putInt("dpi_original_value", state.first ?: -1)
                .putBoolean("dpi_original_override", state.second)
                .putBoolean("dpi_saved", true)
                .apply()
        }
        val result = runShell("wm density $value")
        if (result.first != 0) throw IllegalStateException(result.second.ifBlank { "Android recusou a alteração" })
        val confirmed = readDpiState()
        if (confirmed.first != value || !confirmed.second) throw IllegalStateException("O Android não confirmou a nova densidade")
    }

    private fun applyRecommendedDpi() {
        if (!hasShizukuPermission()) {
            updatePermissionState()
            Toast.makeText(this, "Inicie e autorize o Shizuku primeiro.", Toast.LENGTH_LONG).show()
            return
        }
        dpiApplyButton.isEnabled = false
        dpiRestoreButton.isEnabled = false
        executor.execute {
            try {
                val (current, _) = readDpiState()
                if (current == null) throw IllegalStateException("Não consegui ler a densidade atual")
                val recommended = (current * 0.90f).toInt().coerceIn(120, 1000)
                applyDpiOnWorker(recommended)
                runOnUiThread {
                    dpiInput.setText(recommended.toString())
                    status.text = "DPI definida para $recommended e confirmada pelo Android. Isso altera o tamanho da interface, não garante mais FPS."
                    Toast.makeText(this, "DPI $recommended aplicada", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Não foi possível aplicar a DPI recomendada: ${e.message}" }
            } finally {
                runOnUiThread {
                    dpiApplyButton.isEnabled = true
                    dpiRestoreButton.isEnabled = true
                }
            }
        }
    }

    private fun applyCustomDpi() {
        val value = dpiInput.text.toString().trim().toIntOrNull()
        if (value == null || value !in 120..1000) {
            Toast.makeText(this, "Digite uma DPI entre 120 e 1000.", Toast.LENGTH_LONG).show()
            return
        }
        applyDpi(value)
    }

    private fun applyDpi(value: Int) {
        if (!hasShizukuPermission()) {
            updatePermissionState()
            Toast.makeText(this, "Inicie e autorize o Shizuku primeiro.", Toast.LENGTH_LONG).show()
            return
        }
        dpiApplyButton.isEnabled = false
        dpiRestoreButton.isEnabled = false
        executor.execute {
            try {
                applyDpiOnWorker(value)
                runOnUiThread {
                    status.text = "DPI definida para $value e confirmada pelo Android. Isso altera o tamanho da interface, não garante mais FPS."
                    Toast.makeText(this, "DPI $value aplicada", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Falha ao aplicar DPI: ${e.message}" }
            } finally {
                runOnUiThread {
                    dpiApplyButton.isEnabled = true
                    dpiRestoreButton.isEnabled = true
                }
            }
        }
    }

    private fun restoreDpi() {
        if (!hasShizukuPermission()) {
            updatePermissionState()
            Toast.makeText(this, "Inicie e autorize o Shizuku primeiro.", Toast.LENGTH_LONG).show()
            return
        }
        dpiRestoreButton.isEnabled = false
        executor.execute {
            try {
                val prefs = getSharedPreferences("turbolite_settings", MODE_PRIVATE)
                if (!prefs.getBoolean("dpi_saved", false)) {
                    runOnUiThread { status.text = "Nenhuma DPI original foi salva. Nada foi alterado." }
                    return@execute
                }
                val hadOverride = prefs.getBoolean("dpi_original_override", false)
                val original = prefs.getInt("dpi_original_value", -1)
                val command = if (hadOverride && original > 0) "wm density $original" else "wm density reset"
                val result = runShell(command)
                if (result.first != 0) throw IllegalStateException(result.second.ifBlank { "Android recusou a restauração" })
                prefs.edit().putBoolean("dpi_saved", false).apply()
                runOnUiThread {
                    status.text = "DPI original restaurada."
                    Toast.makeText(this, "DPI original restaurada", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Falha ao restaurar DPI: ${e.message}" }
            } finally {
                runOnUiThread { dpiRestoreButton.isEnabled = true }
            }
        }
    }

    private fun launchSelectedGame() {
        val targetPackage = keepPackages.getOrNull(keepSpinner.selectedItemPosition) ?: packageName
        if (targetPackage == packageName) {
            status.text = "Escolha um jogo no seletor de app antes de abrir."
            Toast.makeText(this, "Selecione um jogo primeiro", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            if (launchIntent == null) {
                status.text = "Não há uma tela de abertura disponível para $targetPackage."
                return
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            status.text = "Abrindo $targetPackage."
        } catch (e: Exception) {
            status.text = "Não foi possível abrir o jogo: ${e.message ?: "atividade indisponível"}"
        }
    }

    private fun gameFocusButtonLabel(): String = if (
        getSharedPreferences("turbolite_settings", MODE_PRIVATE).getBoolean("game_dnd_active", false)
    ) "DESATIVAR FOCO DE JOGO • RESTAURAR ALERTAS" else "FOCO DE JOGO • NÃO PERTURBE"

    private fun toggleGameFocus() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!manager.isNotificationPolicyAccessGranted) {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                status.text = "Ative o acesso de Não Perturbe para o TurboLite nas configurações que abriram; depois retorne aqui."
            } catch (e: Exception) {
                status.text = "Não foi possível abrir o acesso de Não Perturbe: ${e.message ?: "configuração indisponível"}"
            }
            return
        }
        val prefs = getSharedPreferences("turbolite_settings", MODE_PRIVATE)
        if (prefs.getBoolean("game_dnd_active", false)) {
            val previous = prefs.getInt("game_dnd_previous_filter", NotificationManager.INTERRUPTION_FILTER_ALL)
            try {
                manager.setInterruptionFilter(previous)
                prefs.edit().putBoolean("game_dnd_active", false).apply()
                gameFocusButton.text = gameFocusButtonLabel()
                status.text = "Filtro de notificações anterior restaurado."
            } catch (e: Exception) {
                status.text = "Não foi possível restaurar Não Perturbe: ${e.message ?: "acesso recusado"}"
            }
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Ativar foco de jogo?")
            .setMessage("O Android vai usar o filtro Não Perturbe. Chamadas, alarmes e contatos permitidos seguem as regras configuradas no próprio Android. Ao terminar, use o botão de restauração aqui para voltar ao filtro anterior.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("ATIVAR FOCO") { _, _ ->
                try {
                    prefs.edit().putInt("game_dnd_previous_filter", manager.currentInterruptionFilter).apply()
                    manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    prefs.edit().putBoolean("game_dnd_active", true).apply()
                    gameFocusButton.text = gameFocusButtonLabel()
                    status.text = "Foco de jogo ativo. As regras de prioridade seguem as exceções de Não Perturbe do Android. Desative aqui para restaurar."
                } catch (e: Exception) {
                    status.text = "Não foi possível ativar Não Perturbe: ${e.message ?: "acesso recusado"}"
                }
            }
            .show()
    }

    private fun setGameMode(mode: String) {
        if (!hasShizukuPermission()) {
            updatePermissionState()
            Toast.makeText(this, "Inicie e autorize o Shizuku primeiro.", Toast.LENGTH_LONG).show()
            return
        }

        val targetPackage = keepPackages.getOrNull(keepSpinner.selectedItemPosition) ?: packageName
        if (targetPackage == packageName) {
            status.text = "Selecione o jogo na lista antes de aplicar o modo de jogo."
            Toast.makeText(this, "Selecione primeiro o jogo que deseja otimizar.", Toast.LENGTH_LONG).show()
            return
        }
        if (!targetPackage.matches(Regex("[A-Za-z0-9_.]+"))) {
            status.text = "Nome de pacote inválido; nenhuma alteração foi feita."
            return
        }

        status.text = "Solicitando modo de jogo $mode para $targetPackage…"
        executor.execute {
            try {
                val result = runShell("cmd game mode $mode $targetPackage 2>&1")
                runOnUiThread {
                    status.text = if (result.first == 0) {
                        "Comando de modo de jogo enviado para $targetPackage. Reinicie o jogo e compare o desempenho. A XOS pode ignorar ou não oferecer esse recurso."
                    } else {
                        "Modo de jogo não disponível ou rejeitado pelo sistema (código ${result.first}): ${result.second.take(220)}"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Falha ao solicitar modo de jogo: ${e.message ?: "erro desconhecido"}"
                }
            }
        }
    }

    private fun optimize() {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            updatePermissionState()
            return
        }

        val keepPackage = keepPackages.getOrNull(keepSpinner.selectedItemPosition) ?: packageName
        actionButton.isEnabled = false
        status.text = "Forçando parada dos outros apps…"

        executor.execute {
            val candidates = try {
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { app ->
                        app.packageName != packageName &&
                            app.packageName != keepPackage &&
                            (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                            packageManager.getLaunchIntentForPackage(app.packageName) != null
                    }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Não consegui listar apps: ${e.message}"
                    actionButton.isEnabled = true
                }
                return@execute
            }

            var stopped = 0
            val failures = mutableListOf<String>()
            for (app in candidates) {
                try {
                    val result = runShell("am force-stop '${app.packageName.replace("'", "")}'")
                    if (result.first == 0) stopped++ else failures.add(app.packageName)
                } catch (e: Exception) {
                    failures.add(app.packageName)
                }
            }

            runOnUiThread {
                status.text = "Concluído. Apps solicitados para parada: $stopped. Falhas: ${failures.size}.\n" +
                    "Mantidos: TurboLite + ${if (keepPackage == packageName) "nenhum app adicional" else keepPackage}.\n" +
                    "O Android pode reiniciar alguns apps automaticamente."
                actionButton.isEnabled = true
                Toast.makeText(this, "TurboLite: $stopped apps processados", Toast.LENGTH_LONG).show()
            }
        }
    }


    private fun applyUsageProfile(profile: String) {
        if (profile == "hard") {
            android.app.AlertDialog.Builder(this)
                .setTitle("Ativar modo HARD?")
                .setMessage("Este modo altera economia, tela e brilho e solicita parada de outros apps de usuário. Isso pode interromper música, downloads, VPN e notificações. O Android pode reabri-los; dados e logins não são apagados.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("ATIVAR") { _, _ -> applyUsageProfileNow(profile) }
                .show()
            return
        }
        applyUsageProfileNow(profile)
    }

    private fun applyUsageProfileNow(profile: String) {
        if (!hasShizukuPermission()) {
            updatePermissionState()
            Toast.makeText(this, "Inicie e autorize o Shizuku primeiro.", Toast.LENGTH_LONG).show()
            return
        }
        val keepPackage = keepPackages.getOrNull(keepSpinner.selectedItemPosition) ?: packageName
        if (profile == "hard" && keepPackage == packageName) {
            status.text = "Antes de ativar o MODO HARD, selecione na lista o único app que deseja manter aberto."
            Toast.makeText(this, "Selecione primeiro o app que vai continuar aberto.", Toast.LENGTH_LONG).show()
            return
        }
        status.text = "Aplicando perfil $profile…"
        executor.execute {
            try {
                val prefs = getSharedPreferences("turbolite_settings", MODE_PRIVATE)
                // Antes de trocar de perfil, remove qualquer limite de CPU aplicado pelo próprio TurboLite.
                val previousCpuCount = prefs.getInt("usage_cpu_policy_count", 0)
                for (index in 0 until previousCpuCount) {
                    val savedCpu = prefs.getString("usage_cpu_max_$index", null) ?: continue
                    val splitCpu = savedCpu.lastIndexOf('=')
                    if (splitCpu > 0) {
                        val savedPath = savedCpu.substring(0, splitCpu)
                        val savedValue = savedCpu.substring(splitCpu + 1).toLongOrNull()
                        if (savedValue != null && savedPath.matches(Regex("/sys/devices/system/cpu/cpufreq/policy[0-9]+/scaling_max_freq"))) {
                            runShell("echo $savedValue > $savedPath 2>&1")
                        }
                    }
                }
                if (!prefs.getBoolean("usage_profile_saved", false)) {
                    val lowPowerResult = runShell("settings get global low_power")
                    val peakResult = runShell("settings get system peak_refresh_rate")
                    val minResult = runShell("settings get system min_refresh_rate")
                    if (lowPowerResult.first != 0 || peakResult.first != 0 || minResult.first != 0) {
                        throw IllegalStateException("Não foi possível salvar as preferências originais; nenhuma alteração foi aplicada")
                    }
                    prefs.edit()
                        .putString("usage_original_low_power", lowPowerResult.second.ifBlank { "0" })
                        .putString("usage_original_peak", peakResult.second.ifBlank { "null" })
                        .putString("usage_original_min", minResult.second.ifBlank { "null" })
                        .putBoolean("usage_profile_saved", true).apply()
                }
                val isHard = profile == "hard"
                val isCooling = profile == "cooling" || profile == "auto_cooling" || isHard
                val isEfficiency = profile == "efficiency" || isCooling
                if (isHard) {
                    val extraSettings = listOf(
                        "system:screen_brightness",
                        "system:screen_off_timeout",
                        "system:haptic_feedback_enabled",
                        "global:window_animation_scale",
                        "global:transition_animation_scale",
                        "global:animator_duration_scale"
                    )
                    val editor = prefs.edit()
                    for (item in extraSettings) {
                        val (namespace, key) = item.split(":", limit = 2)
                        val result = runShell("settings get $namespace $key")
                        if (result.first != 0) throw IllegalStateException("Não foi possível salvar o valor original de $key")
                        editor.putString("hard_original_${namespace}_$key", result.second.trim().ifBlank { "null" })
                    }
                    editor.apply()
                    val hardCommands = listOf(
                        "settings put system screen_brightness 20",
                        "settings put system screen_off_timeout 15000",
                        "settings put system haptic_feedback_enabled 0",
                        "settings put global window_animation_scale 0",
                        "settings put global transition_animation_scale 0",
                        "settings put global animator_duration_scale 0"
                    )
                    val hardFailures = hardCommands.filter { runShell(it).first != 0 }
                    if (hardFailures.isNotEmpty()) {
                        throw IllegalStateException("A XOS recusou ${hardFailures.size} ajuste(s) do modo HARD; restaure os valores salvos")
                    }
                }
                val lowPowerResult = runShell("settings put global low_power " + if (isEfficiency) "1" else "0")
                if (lowPowerResult.first != 0) throw IllegalStateException("Não foi possível alterar economia do Android")
                val peakResult = runShell("settings put system peak_refresh_rate 60.0")
                val minResult = runShell("settings put system min_refresh_rate 60.0")
                if (peakResult.first != 0 || minResult.first != 0) {
                    throw IllegalStateException("Não foi possível aplicar a preferência de 60 Hz; os valores originais continuam salvos para restauração")
                }

                var cpuPoliciesChanged = 0
                if (isCooling) {
                    val paths = runShell("ls -d /sys/devices/system/cpu/cpufreq/policy*/scaling_max_freq 2>/dev/null").second
                        .lines().filter { it.matches(Regex("/sys/devices/system/cpu/cpufreq/policy[0-9]+/scaling_max_freq")) }
                    for ((index, path) in paths.withIndex()) {
                        val original = runShell("cat $path").second.toLongOrNull() ?: continue
                        val savedKey = "usage_cpu_max_$index"
                        if (!prefs.contains(savedKey)) prefs.edit().putString(savedKey, "$path=$original").apply()
                        val reduced = (original / 2L).coerceAtLeast(1L)
                        val result = runShell("echo $reduced > $path 2>&1; cat $path")
                        if (result.second.trim().toLongOrNull() == reduced) cpuPoliciesChanged++
                    }
                    prefs.edit().putInt("usage_cpu_policy_count", paths.size).apply()
                }

                var stopped = 0
                val candidates = if (profile != "hard") emptyList() else packageManager.getInstalledApplications(PackageManager.GET_META_DATA).filter { app ->
                    app.packageName != packageName && app.packageName != keepPackage &&
                        (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                        packageManager.getLaunchIntentForPackage(app.packageName) != null
                }
                for (app in candidates) {
                    val result = runShell("am force-stop '${app.packageName.replace("'", "")}'")
                    if (result.first == 0) stopped++
                }

                prefs.edit().putString("active_usage_profile", profile).apply()
                runOnUiThread {
                    if (::currentModeCard.isInitialized) updateCurrentModeCard()
                    status.text = when (profile) {
                        "streaming" -> "Streaming ativado: equilíbrio e 60 Hz solicitados. Apps continuam abertos; a XOS pode ignorar a preferência de Hz."
                        "efficiency" -> "Eficiência ativada: economia de bateria e 60 Hz solicitados. Apps continuam abertos."
                        "hard" -> "MODO HARD ativado. Mantido: $keepPackage; $stopped apps de usuário encerrados. Economia do Android ligada, brilho reduzido, tela apaga em 15 s e animações/desbloqueios táteis reduzidos. CPU limitada: $cpuPoliciesChanged políticas. Apps do sistema podem continuar ativos."
                        "auto_cooling" -> "Resfriamento automático ativado sem fechar apps: economia máxima, 60 Hz solicitado. Políticas CPU alteradas: $cpuPoliciesChanged. Se 0, a XOS/kernel bloqueou o limite."
                        else -> "Resfriamento solicitado: economia máxima e 60 Hz. Apps continuam abertos. Políticas CPU alteradas: $cpuPoliciesChanged. Se 0, a XOS/kernel bloqueou o limite; apague a tela e pare de usar enquanto esfria."
                    }
                    Toast.makeText(this, "Perfil aplicado. App mantido: $keepPackage", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Falha ao aplicar perfil: ${e.message ?: "erro desconhecido"}" }
            }
        }
    }

    private fun cleanRamAndBackgroundApps() {
        if (!hasShizukuPermission()) {
            updatePermissionState()
            Toast.makeText(this, "Inicie e autorize o Shizuku primeiro.", Toast.LENGTH_LONG).show()
            return
        }
        val keepPackage = keepPackages.getOrNull(keepSpinner.selectedItemPosition) ?: packageName
        status.text = "Solicitando parada dos apps selecionáveis…"
        executor.execute {
            var stopped = 0
            try {
                val candidates = packageManager.getInstalledApplications(PackageManager.GET_META_DATA).filter { app ->
                    app.packageName != packageName && app.packageName != keepPackage &&
                        (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                        packageManager.getLaunchIntentForPackage(app.packageName) != null
                }
                var failures = 0
                for (app in candidates) {
                    try {
                        val result = runShell("am force-stop '${app.packageName.replace("'", "")}'")
                        if (result.first == 0) stopped++ else failures++
                    } catch (_: Exception) {
                        failures++
                    }
                }
                runOnUiThread {
                    status.text = "Concluído: $stopped apps solicitados para parada; falhas: $failures. O Android administra a RAM automaticamente; nenhum processo ou dado em cache foi apagado. Mantido: $keepPackage."
                    Toast.makeText(this, "Apps selecionáveis processados", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Erro na limpeza: ${e.message ?: "erro desconhecido"}" }
            }
        }
    }

    private fun restoreUsageProfile() {
        if (!hasShizukuPermission()) {
            updatePermissionState()
            Toast.makeText(this, "Inicie e autorize o Shizuku primeiro.", Toast.LENGTH_LONG).show()
            return
        }
        executor.execute {
            try {
                val prefs = getSharedPreferences("turbolite_settings", MODE_PRIVATE)
                if (!prefs.getBoolean("usage_profile_saved", false)) {
                    prefs.edit().putBoolean("auto_thermal_cooling_active", false).apply()
                    runOnUiThread { status.text = "Nenhum perfil de energia salvo para restaurar." }
                    return@execute
                }
                val failures = mutableListOf<String>()
                fun restoreSetting(namespace: String, key: String, old: String, label: String) {
                    val command = if (old == "null") {
                        "settings delete $namespace $key"
                    } else if (old.matches(Regex("[0-9]+(\\.[0-9]+)?"))) {
                        "settings put $namespace $key $old"
                    } else {
                        failures.add("$label (valor salvo inválido)")
                        return
                    }
                    val result = runShell(command)
                    if (result.first != 0) {
                        val detail = result.second.take(80).takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
                        failures.add("$label$detail")
                    }
                }

                val lowPower = prefs.getString("usage_original_low_power", "0") ?: "0"
                val lowPowerResult = runShell("settings put global low_power " + if (lowPower.trim() == "1") "1" else "0")
                if (lowPowerResult.first != 0) failures.add("economia de bateria")
                for ((prefKey, setting) in listOf("usage_original_peak" to "peak_refresh_rate", "usage_original_min" to "min_refresh_rate")) {
                    restoreSetting("system", setting, prefs.getString(prefKey, "null") ?: "null", setting)
                }

                val hardSettings = listOf(
                    "system:screen_brightness",
                    "system:screen_off_timeout",
                    "system:haptic_feedback_enabled",
                    "global:window_animation_scale",
                    "global:transition_animation_scale",
                    "global:animator_duration_scale"
                )
                for (item in hardSettings) {
                    val (namespace, key) = item.split(":", limit = 2)
                    val saved = prefs.getString("hard_original_${namespace}_$key", null) ?: continue
                    restoreSetting(namespace, key, saved, key)
                }

                val count = prefs.getInt("usage_cpu_policy_count", 0)
                var cpuRestored = 0
                for (index in 0 until count) {
                    val saved = prefs.getString("usage_cpu_max_$index", null) ?: continue
                    val split = saved.lastIndexOf('=')
                    if (split <= 0) {
                        failures.add("política CPU $index (registro inválido)")
                        continue
                    }
                    val path = saved.substring(0, split)
                    val value = saved.substring(split + 1).toLongOrNull()
                    if (!path.matches(Regex("/sys/devices/system/cpu/cpufreq/policy[0-9]+/scaling_max_freq")) || value == null) {
                        failures.add("política CPU $index (registro inválido)")
                        continue
                    }
                    val result = runShell("echo $value > $path 2>&1; cat $path")
                    if (result.first == 0 && result.second.trim().toLongOrNull() == value) cpuRestored++
                    else failures.add("política CPU $index")
                }

                if (failures.isEmpty()) {
                    val editor = prefs.edit()
                        .putBoolean("usage_profile_saved", false)
                        .putBoolean("auto_thermal_cooling_active", false)
                        .putInt("usage_cpu_policy_count", 0)
                    for (item in hardSettings) {
                        val (namespace, key) = item.split(":", limit = 2)
                        editor.remove("hard_original_${namespace}_$key")
                    }
                    for (index in 0 until count) editor.remove("usage_cpu_max_$index")
                    editor.remove("active_usage_profile").apply()
                    runOnUiThread {
                        if (::currentModeCard.isInitialized) updateCurrentModeCard()
                        status.text = "Perfil restaurado. Políticas CPU restauradas: $cpuRestored. Apps encerrados não são reabertos automaticamente."
                    }
                } else {
                    runOnUiThread {
                        status.text = "Restauração incompleta (${failures.size} item(ns)). Os valores originais continuam salvos; corrija o acesso Shizuku e tente restaurar novamente."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Falha ao restaurar perfil: ${e.message ?: "erro desconhecido"}. Os valores salvos foram mantidos para nova tentativa." }
            }
        }
    }

    private fun formatNetworkRate(bytesPerSecond: Long): String = when {
        bytesPerSecond < 1024L -> "$bytesPerSecond B/s"
        bytesPerSecond < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f KB/s", bytesPerSecond / 1024.0)
        else -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
    }

    private fun accentColor(): Int {
        val key = getSharedPreferences("turbolite_settings", MODE_PRIVATE).getString("accent_theme", "cyan")
        return when (key) {
            "violet" -> 0xFFB388FF.toInt()
            "green" -> 0xFF54E6A5.toInt()
            else -> 0xFF00D4FF.toInt()
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    private fun lighten(color: Int, amount: Float): Int {
        val fraction = amount.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val value = color ushr shift and 0xFF
            return (value + (255 - value) * fraction).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        updatePermissionState()
        if (::gameFocusButton.isInitialized) gameFocusButton.text = gameFocusButtonLabel()
        if (selectedTabKey == "monitor") {
            monitorHandler.removeCallbacks(monitorUpdater)
            monitorHandler.post(monitorUpdater)
        }
        if (::autoThermalCheck.isInitialized && autoThermalCheck.isChecked) {
            thermalHandler.removeCallbacks(thermalMonitor)
            thermalHandler.post(thermalMonitor)
        }
    }

    override fun onPause() {
        monitorHandler.removeCallbacks(monitorUpdater)
        thermalHandler.removeCallbacks(thermalMonitor)
        super.onPause()
    }

    override fun onDestroy() {
        thermalHandler.removeCallbacks(thermalMonitor)
        monitorHandler.removeCallbacks(monitorUpdater)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        executor.shutdownNow()
        super.onDestroy()
    }
}
