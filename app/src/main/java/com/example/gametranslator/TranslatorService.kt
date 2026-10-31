package com.example.gametranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Служба: показывает плавающую панель, а по нажатию «Перевод»
 * делает снимок экрана, распознаёт текст, переводит его и рисует перевод поверх игры.
 */
class TranslatorService : Service() {

    companion object {
        const val EXTRA_ROOT = "root"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        const val ACTION_STOP = "com.example.gametranslator.STOP"
        private const val CHANNEL_ID = "translator"
        private const val NOTIFICATION_ID = 1
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    private lateinit var wm: WindowManager
    private var wmReady = false
    private var resultView: OverlayResultView? = null
    private var barView: LinearLayout? = null
    private var langButton: TextView? = null
    private var useRoot = false

    // Захват экрана через MediaProjection (обычный режим)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val frameLock = Any()
    private var latestImage: Image? = null
    private var capW = 0
    private var capH = 0

    // ML Kit: распознавание и перевод
    private val recognizers = HashMap<String, TextRecognizer>()
    private var translator: Translator? = null
    private var translatorKey = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        useRoot = intent?.getBooleanExtra(EXTRA_ROOT, false) ?: false

        try {
            startAsForeground()
        } catch (e: Exception) {
            toast("Не удалось запустить службу: " + (e.message ?: e.javaClass.simpleName))
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            toast("Нет разрешения «Поверх других окон»")
            stopSelf()
            return START_NOT_STICKY
        }
        wm = getSystemService(WindowManager::class.java)
        wmReady = true

        if (!useRoot) {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            val data = readProjectionData(intent)
            if (data == null) {
                toast("Нет разрешения на запись экрана. Запустите переводчик заново.")
                stopSelf()
                return START_NOT_STICKY
            }
            try {
                startProjection(resultCode, data)
            } catch (e: Exception) {
                toast("Не удалось начать запись экрана: " + (e.message ?: e.javaClass.simpleName))
                stopSelf()
                return START_NOT_STICKY
            }
        }

        createOverlays()
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readProjectionData(intent: Intent?): Intent? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_DATA)
        }
    }

    // ---------------------------------------------------------------- уведомление

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Экранный переводчик", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = Intent(this, TranslatorService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Остановить",
            stopPending
        ).build()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Экранный переводчик работает")
            .setContentText("Откройте игру и нажимайте «Перевод» на плавающей панели")
            .addAction(stopAction)
            .setOngoing(true)
            .build()
        val type = if (useRoot) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        startForeground(NOTIFICATION_ID, notification, type)
    }

    // ---------------------------------------------------------------- плавающая панель

    private fun languageLabel(): String {
        return Langs.source(Prefs.source(this)).label + "→" + Langs.target(Prefs.target(this)).label
    }

    private fun createOverlays() {
        val density = resources.displayMetrics.density

        // Прозрачный слой для перевода: пропускает все касания к игре
        val result = OverlayResultView(this)
        val resultParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        resultParams.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        wm.addView(result, resultParams)
        resultView = result

        fun makeButton(caption: String): TextView {
            val view = TextView(this)
            view.text = caption
            view.setTextColor(Color.WHITE)
            view.textSize = 14f
            view.gravity = Gravity.CENTER
            val padH = (10 * density).toInt()
            val padV = (9 * density).toInt()
            view.setPadding(padH, padV, padH, padV)
            return view
        }

        val handle = makeButton("≡")
        val go = makeButton("Перевод")
        val lang = makeButton(languageLabel())
        val hide = makeButton("Скрыть")
        langButton = lang

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        val background = GradientDrawable()
        background.setColor(Color.argb(215, 25, 25, 25))
        background.cornerRadius = 22 * density
        bar.background = background
        bar.addView(handle)
        bar.addView(go)
        bar.addView(lang)
        bar.addView(hide)

        val barParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        barParams.gravity = Gravity.TOP or Gravity.START
        barParams.x = (8 * density).toInt()
        barParams.y = (120 * density).toInt()

        go.setOnClickListener { onTranslateClicked() }
        hide.setOnClickListener { resultView?.clear() }
        lang.setOnClickListener { cycleSourceLanguage() }

        // Панель можно двигать за значок «≡»
        handle.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var downX = 0f
            private var downY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = barParams.x
                        startY = barParams.y
                        downX = event.rawX
                        downY = event.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        barParams.x = startX + (event.rawX - downX).toInt()
                        barParams.y = startY + (event.rawY - downY).toInt()
                        wm.updateViewLayout(bar, barParams)
                    }
                }
                return true
            }
        })

        wm.addView(bar, barParams)
        barView = bar
    }

    private fun cycleSourceLanguage() {
        val list = Langs.sources
        val current = list.indexOfFirst { it.code == Prefs.source(this) }
        val next = list[(current + 1) % list.size]
        Prefs.setSource(this, next.code)
        langButton?.text = languageLabel()
    }

    // ---------------------------------------------------------------- нажатие «Перевод»

    private fun onTranslateClicked() {
        if (!busy.compareAndSet(false, true)) return

        // Прячем панель и старый перевод, чтобы они не попали на снимок
        main.post {
            resultView?.clear()
            barView?.visibility = View.INVISIBLE
        }

        worker.execute {
            try {
                Thread.sleep(350)
                val bitmap = if (useRoot) captureRoot() else captureProjectionBitmap()
                if (bitmap == null) {
                    showMessage("Не удалось сделать снимок экрана. Нажмите «Перевод» ещё раз.")
                } else {
                    recognizeAndTranslate(bitmap)
                }
            } catch (e: Exception) {
                showMessage("Ошибка: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                main.post { barView?.visibility = View.VISIBLE }
                busy.set(false)
            }
        }
    }

    private fun showMessage(text: String) {
        main.post { resultView?.showMessage(text) }
    }

    private fun toast(text: String) {
        main.post { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    }

    // ---------------------------------------------------------------- снимок экрана

    /** Root-режим: снимок через команду su screencap. */
    private fun captureRoot(): Bitmap? {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "screencap -p"))
        return try {
            val bytes = process.inputStream.readBytes()
            process.waitFor()
            if (bytes.isEmpty()) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            process.destroy()
        }
    }

    /** Обычный режим: берём последний кадр записи экрана. */
    private fun captureProjectionBitmap(): Bitmap? {
        val bounds = wm.maximumWindowMetrics.bounds
        var rebuilt = false
        if (bounds.width() != capW || bounds.height() != capH) {
            // экран повернули — пересоздаём приёмник кадров под новый размер
            rebuildReader(bounds.width(), bounds.height())
            rebuilt = true
        }
        if (rebuilt) Thread.sleep(500)

        // ждём, пока придёт хотя бы один кадр (не дольше 2 секунд)
        var waited = 0
        while (waited < 2000) {
            val hasFrame = synchronized(frameLock) { latestImage != null }
            if (hasFrame) break
            Thread.sleep(50)
            waited += 50
        }

        return synchronized(frameLock) {
            val image = latestImage
            if (image == null) null else imageToBitmap(image)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val buffer = plane.buffer
        buffer.rewind()
        val wide = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        wide.copyPixelsFromBuffer(buffer)
        if (rowPadding == 0) return wide
        val cropped = Bitmap.createBitmap(wide, 0, 0, image.width, image.height)
        wide.recycle()
        return cropped
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val p = manager.getMediaProjection(resultCode, data)
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }, main)
        projection = p

        val thread = HandlerThread("capture")
        thread.start()
        captureThread = thread
        captureHandler = Handler(thread.looper)

        val bounds = wm.maximumWindowMetrics.bounds
        rebuildReader(bounds.width(), bounds.height())
    }

    /** Создаёт (или пересоздаёт при повороте экрана) приёмник кадров записи экрана. */
    private fun rebuildReader(w: Int, h: Int) {
        synchronized(frameLock) {
            latestImage?.close()
            latestImage = null
        }
        val old = imageReader
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            try {
                val image = r.acquireLatestImage()
                if (image != null) {
                    synchronized(frameLock) {
                        latestImage?.close()
                        latestImage = image
                    }
                }
            } catch (e: Exception) {
                // приёмник могли закрыть во время смены размера — это нормально
            }
        }, captureHandler)

        val dpi = resources.displayMetrics.densityDpi
        val display = virtualDisplay
        if (display == null) {
            virtualDisplay = projection!!.createVirtualDisplay(
                "screen-translator",
                w,
                h,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )
        } else {
            display.surface = reader.surface
            display.resize(w, h, dpi)
        }
        imageReader = reader
        capW = w
        capH = h

        old?.setOnImageAvailableListener(null, null)
        old?.close()
    }

    // ---------------------------------------------------------------- распознавание и перевод

    private fun recognizerFor(lang: String): TextRecognizer {
        val key = when (lang) {
            "ja" -> "ja"
            "zh" -> "zh"
            else -> "latin"
        }
        return recognizers.getOrPut(key) {
            when (key) {
                "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
        }
    }

    private fun translatorFor(src: String, tgt: String): Translator {
        val key = "$src>$tgt"
        if (key != translatorKey) {
            translator?.close()
            translator = Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(tgt)
                    .build()
            )
            translatorKey = key
        }
        return translator!!
    }

    private fun recognizeAndTranslate(bitmap: Bitmap) {
        val src = Prefs.source(this)
        val tgt = Prefs.target(this)
        val bitmapW = bitmap.width
        val bitmapH = bitmap.height

        val recognized = Tasks.await(recognizerFor(src).process(InputImage.fromBitmap(bitmap, 0)))
        bitmap.recycle()

        // у китайского и японского нет пробелов между словами
        val noSpaces = src == "zh" || src == "ja"
        val blocks = recognized.textBlocks.filter { block ->
            block.boundingBox != null && block.text.any { ch -> ch.isLetter() }
        }
        if (blocks.isEmpty()) {
            showMessage("Текст не найден. Нажмите «Перевод», когда текст появится на экране.")
            return
        }

        val items = ArrayList<OverlayResultView.Item>()
        if (src == tgt) {
            for (block in blocks) {
                items.add(OverlayResultView.Item(block.boundingBox!!, block.text))
            }
        } else {
            val tr = translatorFor(src, tgt)
            try {
                Tasks.await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            } catch (e: Exception) {
                showMessage("Не скачаны языковые модели. Откройте приложение и нажмите кнопку 2 (нужен интернет).")
                return
            }
            for (block in blocks) {
                val original = block.lines
                    .joinToString(if (noSpaces) "" else " ") { line -> line.text }
                    .trim()
                if (original.isEmpty()) continue
                val translated = try {
                    Tasks.await(tr.translate(original))
                } catch (e: Exception) {
                    original
                }
                items.add(OverlayResultView.Item(block.boundingBox!!, translated))
            }
        }
        main.post { resultView?.showItems(items, bitmapW, bitmapH) }
    }

    // ---------------------------------------------------------------- жизненный цикл

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // после поворота экрана старый перевод уже не совпадает с картинкой
        resultView?.clear()
    }

    override fun onDestroy() {
        if (wmReady) {
            try {
                resultView?.let { wm.removeView(it) }
            } catch (e: Exception) {
                // уже убрано
            }
            try {
                barView?.let { wm.removeView(it) }
            } catch (e: Exception) {
                // уже убрано
            }
        }
        resultView = null
        barView = null

        synchronized(frameLock) {
            latestImage?.close()
            latestImage = null
        }
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        projection?.stop()
        projection = null
        captureThread?.quitSafely()
        captureThread = null

        recognizers.values.forEach { it.close() }
        recognizers.clear()
        translator?.close()
        translator = null
        worker.shutdownNow()
        super.onDestroy()
    }
}
