package com.vnsants.turbolite

import android.app.Activity
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
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var actionButton: Button
    private lateinit var reloadButton: Button
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
                "• Não apaga cache, dados ou logins.\n\n" +
                "Antes de tocar no botão, selecione o jogo/app que quer manter. Depois, volte a abrir esse app. " +
                "Apps fechados podem perder downloads, reprodução ou notificações temporariamente; o Android pode reiniciá-los. " +
                "Esta beta não ativa automaticamente o modo gamer da Infinix."
            textSize = 14f
            setTextColor(0xFFB8C4D9.toInt())
            setPadding(0, dp(16), 0, dp(10))
        }
        root.addView(note)

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
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
