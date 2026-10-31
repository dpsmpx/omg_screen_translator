package com.example.gametranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel

/** Главный экран: выбор языков, разрешения, скачивание моделей, запуск и остановка. */
class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var rootBox: CheckBox
    private lateinit var sourceSpinner: Spinner
    private lateinit var targetSpinner: Spinner

    private val requestCapture = 1001
    private val requestNotifications = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), requestNotifications)
        }
    }

    override fun onResume() {
        super.onResume()
        // язык игры могли поменять кнопкой на плавающей панели
        sourceSpinner.setSelection(Langs.sources.indexOfFirst { it.code == Prefs.source(this) }.coerceAtLeast(0))
        targetSpinner.setSelection(Langs.targets.indexOfFirst { it.code == Prefs.target(this) }.coerceAtLeast(0))
        refreshStatus()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun buildUi(): View {
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(16), dp(16), dp(16), dp(32))

        fun addText(text: String, sizeSp: Float) {
            val view = TextView(this)
            view.text = text
            view.textSize = sizeSp
            view.setPadding(0, dp(8), 0, dp(4))
            column.addView(view)
        }

        fun addButton(caption: String, action: () -> Unit) {
            val button = Button(this)
            button.text = caption
            button.isAllCaps = false
            button.setOnClickListener { action() }
            column.addView(button)
        }

        fun addSpinner(titles: List<String>, selected: Int, onPick: (Int) -> Unit): Spinner {
            val spinner = Spinner(this)
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, titles)
            spinner.setSelection(selected)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    onPick(position)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            column.addView(spinner)
            return spinner
        }

        addText("Экранный переводчик для игр", 22f)
        addText(
            "Порядок такой: 1) разрешите показ поверх других окон, 2) скачайте языковые модели, " +
                "3) нажмите «Запустить», откройте игру и нажимайте «Перевод» на плавающей панели.",
            15f
        )

        addText("Язык игры (с какого переводим):", 16f)
        sourceSpinner = addSpinner(
            Langs.sources.map { it.title },
            Langs.sources.indexOfFirst { it.code == Prefs.source(this) }.coerceAtLeast(0)
        ) { position -> Prefs.setSource(this, Langs.sources[position].code) }

        addText("Переводить на:", 16f)
        targetSpinner = addSpinner(
            Langs.targets.map { it.title },
            Langs.targets.indexOfFirst { it.code == Prefs.target(this) }.coerceAtLeast(0)
        ) { position -> Prefs.setTarget(this, Langs.targets[position].code) }

        addButton("1. Разрешить показ поверх других окон") { openOverlaySettings() }
        addButton("2. Скачать языковые модели (нужен интернет, около 150 МБ)") { downloadModels() }

        rootBox = CheckBox(this)
        rootBox.text = "Режим root: снимок экрана через su (без запроса записи экрана)"
        rootBox.isChecked = Prefs.root(this)
        rootBox.setOnCheckedChangeListener { _, checked ->
            Prefs.setRoot(this, checked)
            if (checked) testRoot()
        }
        column.addView(rootBox)

        addButton("3. Запустить переводчик") { startTranslator() }
        addButton("Остановить переводчик") { stopTranslator() }

        statusView = TextView(this)
        statusView.textSize = 14f
        statusView.setPadding(0, dp(16), 0, 0)
        column.addView(statusView)

        val scroll = ScrollView(this)
        scroll.addView(column)
        return scroll
    }

    private fun openOverlaySettings() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    /** Показывает, что уже разрешено и какие языковые модели скачаны. */
    private fun refreshStatus() {
        val overlay = if (Settings.canDrawOverlays(this)) {
            "разрешён"
        } else {
            "НЕ разрешён (нажмите кнопку 1)"
        }
        statusView.text = "Показ поверх других окон: $overlay"
        RemoteModelManager.getInstance()
            .getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                val have = models.map { it.language }.toSet()
                val names = Langs.allModels.joinToString("\n") { code ->
                    code.uppercase() + if (code in have) " - скачан" else " - НЕ скачан"
                }
                statusView.text = "Показ поверх других окон: $overlay\n\nЯзыковые модели:\n$names"
            }
    }

    private fun downloadModels() {
        val manager = RemoteModelManager.getInstance()
        val total = Langs.allModels.size
        var done = 0
        var failed = 0
        statusView.text = "Скачиваю языковые модели... (0 из $total). Нужен интернет, лучше Wi-Fi."
        for (code in Langs.allModels) {
            val model = TranslateRemoteModel.Builder(code).build()
            manager.download(model, DownloadConditions.Builder().build())
                .addOnSuccessListener {
                    done++
                    onDownloadProgress(done, failed, total)
                }
                .addOnFailureListener {
                    failed++
                    onDownloadProgress(done, failed, total)
                }
        }
    }

    private fun onDownloadProgress(done: Int, failed: Int, total: Int) {
        if (done + failed < total) {
            statusView.text = "Скачано $done из $total..."
        } else if (failed == 0) {
            toast("Все языковые модели скачаны")
            refreshStatus()
        } else {
            toast("Не удалось скачать моделей: $failed. Проверьте интернет и нажмите кнопку 2 ещё раз.")
            refreshStatus()
        }
    }

    /** Проверяет, что root-доступ действительно есть (при первом запуске появится запрос Magisk). */
    private fun testRoot() {
        Thread {
            val ok = try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                output.contains("uid=0")
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                if (ok) {
                    toast("Root-доступ получен")
                } else {
                    toast("Root-доступ не получен. Режим root выключен.")
                    rootBox.isChecked = false
                    Prefs.setRoot(this, false)
                }
            }
        }.start()
    }

    private fun startTranslator() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Сначала нажмите кнопку 1 и разрешите показ поверх других окон")
            return
        }
        if (Prefs.root(this)) {
            launchService(true, 0, null)
        } else {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+: нужен снимок ВСЕГО экрана. При записи «одного приложения»
                // координаты текста не совпадут с экраном и перевод окажется не на месте.
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager.createScreenCaptureIntent()
            }
            startActivityForResult(captureIntent, requestCapture)
        }
    }

    private fun stopTranslator() {
        stopService(Intent(this, TranslatorService::class.java))
        toast("Переводчик остановлен")
    }

    private fun launchService(useRoot: Boolean, resultCode: Int, data: Intent?) {
        // Если служба уже работает, она сама уберёт старую панель и старую запись экрана
        val intent = Intent(this, TranslatorService::class.java)
        intent.putExtra(TranslatorService.EXTRA_ROOT, useRoot)
        intent.putExtra(TranslatorService.EXTRA_RESULT_CODE, resultCode)
        if (data != null) {
            intent.putExtra(TranslatorService.EXTRA_DATA, data)
        }
        startForegroundService(intent)
        toast("Переводчик запущен. Сверните приложение и откройте игру.")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestCapture) {
            if (resultCode == RESULT_OK && data != null) {
                launchService(false, resultCode, data)
            } else {
                toast("Запись экрана не разрешена. Нажмите «Запустить» ещё раз и выберите «Начать».")
            }
        }
    }
}
