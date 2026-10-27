package com.vnsants.turbolite

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var actionButton: Button
    private lateinit var reloadButton: Button
    private lateinit var performanceButton: Button
    private lateinit var restoreButton: Button
    private lateinit var overlayButton: Button
    private lateinit var stopOverlayButton: Button
    private lateinit var keepSpinner: Spinner
    private var userApps: List<ApplicationInfo> = emptyList()
    private var keepPackages: List<String> = emptyList()

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        runOnUiThread {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                status.text = "Shizuku autorizado. Pronto para otimizar."
                actionButton.isEnabled = true
            } else {
                status.text = "Permissão negada. Autorize o TurboLite no Shizuku."
                actionButton.isEnabled = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        buildUi()
        val overlayPrefs = getSharedPreferences("turbolite_overlay", MODE_PRIVATE)
        if (!overlayPrefs.contains("show_fps")) {
            overlayPrefs.edit().putBoolean("show_fps", false).putBoolean("show_ram", true).putBoolean("show_ping", false).apply()
        }
        loadAppsToKeep()
        updatePermissionState()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            setBackgroundColor(0xFF0B1020.toInt())
        }

        val title = TextView(this).apply {
            text = "⚡ TurboLite"
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "BETA 0.1 • Android 14 • sem root"
            textSize = 14f
            setTextColor(0xFF9CAAC4.toInt())
            setPadding(0, dp(5), 0, dp(18))
        }
        root.addView(subtitle)

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
        root.addView(status)

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
            text = "RESTAURAR CONFIGURAÇÕES ORIGINAIS"
            setOnClickListener { setAnimationMode(false) }
        }
        root.addView(restoreButton, LinearLayout.LayoutParams(-1, dp(54)))

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
        addMetricToggle("Mostrar FPS (estimativa; depende do Android)", "show_fps", true)
        addMetricToggle("Mostrar uso de CPU (Shizuku)", "show_cpu", false)
        addMetricToggle("Mostrar temperatura térmica (se o fabricante liberar)", "show_temp", false)

        reloadButton = Button(this).apply {
            text = "ATUALIZAR LISTA / PERMISSÃO"
            setOnClickListener {
                loadAppsToKeep()
                updatePermissionState()
            }
        }
        root.addView(reloadButton, LinearLayout.LayoutParams(-1, dp(52)))

        val note = TextView(this).apply {
            text = "\nO que esta beta faz:\n" +
                "• Mantém o TurboLite e o app escolhido na lista.\n" +
                "• Força a parada de outros apps instalados pelo usuário.\n" +
                "• Não força a parada de apps de sistema.\n" +
                "• Não apaga cache, dados ou logins.\n" +
                "• Modo desempenho: reduz animações do Android para 0,5x; não aumenta diretamente o FPS.\n" +
                "• Restaurar: volta às escalas de animação guardadas antes da primeira ativação.\n\n" +
                "Antes de fechar apps, selecione o jogo que quer manter. Apps fechados podem perder downloads, reprodução, VPN ou notificações temporariamente; o Android pode reiniciá-los. " +
                "A restauração recupera as animações, mas não reabre automaticamente os apps fechados. " +
                "Esta beta não ativa automaticamente o modo gamer da Infinix."
            textSize = 14f
            setTextColor(0xFFB8C4D9.toInt())
            setPadding(0, dp(16), 0, dp(10))
        }
        root.addView(note)

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
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
        try {
            userApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                        packageManager.getLaunchIntentForPackage(app.packageName) != null
                }
                .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() }

            val labels = mutableListOf("TurboLite (manter aberto)")
            val packages = mutableListOf(packageName)
            userApps.filter { it.packageName != packageName }.forEach { app ->
                labels.add("${packageManager.getApplicationLabel(app)} • ${app.packageName}")
                packages.add(app.packageName)
            }
            keepPackages = packages

            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            keepSpinner.adapter = adapter
            status.text = "Lista carregada: ${labels.size - 1} apps disponíveis para manter."
        } catch (e: Exception) {
            status.text = "Não consegui listar os apps: ${e.message ?: "erro desconhecido"}"
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
                    Shizuku.requestPermission(1001)
                }
            } else {
                status.text = "Abra o Shizuku, inicie o serviço (depuração sem fio) e volte aqui."
                actionButton.isEnabled = false
            }
        } catch (e: Exception) {
            status.text = "Shizuku indisponível: ${e.message ?: "verifique a configuração"}"
            actionButton.isEnabled = false
        }
    }


    private fun hasShizukuPermission(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    private fun runShell(command: String): Pair<Int, String> {
        val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exitCode = process.waitFor()
        return Pair(exitCode, output)
    }

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
                    val p = Shizuku.newProcess(
                        arrayOf("sh", "-c", "am force-stop '${app.packageName.replace("'", "")}'"),
                        null,
                        null
                    )
                    val exitCode = p.waitFor()
                    if (exitCode == 0) stopped++ else failures.add(app.packageName)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        executor.shutdownNow()
        super.onDestroy()
    }
}
