package com.example.gametranslator

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
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
import android.hardware.input.InputManager
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
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

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

    // Окна поверх игры (меняются только из главного потока)
    private var overlayContext: Context? = null
    @Volatile
    private var wm: WindowManager? = null
    private var resultView: OverlayResultView? = null
    private var barView: LinearLayout? = null
    private var langButton: TextView? = null
    @Volatile
    private var useRoot = false

    // Захват экрана через MediaProjection (обычный режим). Всё защищено captureLock.
    private val captureLock = Any()
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var latestImage: Image? = null
    private var capW = 0
    private var capH = 0

    // ML Kit: распознавание и перевод (используются только в потоке worker)
    private val recognizers = HashMap<String, TextRecognizer>()
    private var translator: Translator? = null
    private var translatorKey = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val root = intent?.getBooleanExtra(EXTRA_ROOT, false) ?: false

        // startForeground нужно вызвать сразу (и обязательно до получения MediaProjection)
        try {
            startAsForeground(root)
        } catch (e: Exception) {
            toast("Не удалось запустить службу: " + describe(e))
            stopSelf()
            return START_NOT_STICKY
        }

        // Повторный запуск уже работающей службы: убираем старую панель и старую запись экрана,
        // иначе панели и виртуальные экраны будут множиться.
        removeOverlays()
        stopCapture()
        useRoot = root

        if (!Settings.canDrawOverlays(this)) {
            toast("Нет разрешения «Поверх других окон»")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!root) {
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
                toast("Не удалось начать запись экрана: " + describe(e))
                stopSelf()
                return START_NOT_STICKY
            }
        }

        try {
            createOverlays()
        } catch (e: Exception) {
            toast("Не удалось показать плавающую панель: " + describe(e))
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readProjectionData(intent: Intent?): Intent? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_DATA)
        }
    }

    private fun describe(e: Throwable): String = e.message ?: e.javaClass.simpleName

    // ---------------------------------------------------------------- уведомление

    @Suppress("DEPRECATION")
    private fun startAsForeground(root: Boolean) {
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
        val openPending = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
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
            .setContentIntent(openPending)
            .addAction(stopAction)
            .setOngoing(true)
            .build()
        val type = when {
            !root -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            // тип «specialUse» появился только в Android 14
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
        }
        startForeground(NOTIFICATION_ID, notification, type)
    }

    // ---------------------------------------------------------------- плавающая панель

    /**
     * WindowManager для окон поверх других приложений. У обычного контекста службы нет
     * «своего» экрана: размеры экрана из него могут быть неверными, а на Android 11
     * обращение к экрану из такого контекста может закончиться исключением.
     * Поэтому создаём специальный оконный контекст.
     */
    private fun ensureWindowManager(): WindowManager {
        wm?.let { return it }
        val context: Context = try {
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } catch (e: Exception) {
            this
        }
        val manager = context.getSystemService(WindowManager::class.java)
        overlayContext = context
        wm = manager
        return manager
    }

    /**
     * Android 12+ пропускает касания сквозь окно другого приложения, только если это окно
     * достаточно прозрачное (по умолчанию не плотнее 0.8). Без этого игра переставала бы
     * реагировать на нажатия, пока на экране показан перевод.
     */
    private fun passThroughAlpha(): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 1f
        val max = getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
        return if (max >= 0.5f) minOf(max, 1f) else 0.8f
    }

    private fun languageLabel(): String {
        return Langs.source(Prefs.source(this)).label + "→" + Langs.target(Prefs.target(this)).label
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlays() {
        val manager = ensureWindowManager()
        val context = overlayContext ?: this
        val density = context.resources.displayMetrics.density

        // Прозрачный слой для перевода: пропускает все касания к игре
        val result = OverlayResultView(context)
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
        resultParams.fitInsetsTypes = 0 // на весь экран, без отступов под системные панели
        resultParams.alpha = passThroughAlpha()
        manager.addView(result, resultParams)
        resultView = result

        fun makeButton(caption: String): TextView {
            val view = TextView(context)
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

        val bar = LinearLayout(context)
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
        val touchSlop = 8 * density
        handle.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var downX = 0f
            private var downY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = barParams.x
                        startY = barParams.y
                        downX = event.rawX
                        downY = event.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        barParams.x = startX + (event.rawX - downX).toInt()
                        barParams.y = startY + (event.rawY - downY).toInt()
                        try {
                            manager.updateViewLayout(bar, barParams)
                        } catch (e: IllegalArgumentException) {
                            // панель уже убрана с экрана
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (abs(event.rawX - downX) < touchSlop && abs(event.rawY - downY) < touchSlop) {
                            v.performClick()
                        }
                    }
                }
                return true
            }
        })

        manager.addView(bar, barParams)
        barView = bar
    }

    private fun removeOverlays() {
        val manager = wm
        if (manager != null) {
            resultView?.let { view ->
                try {
                    manager.removeView(view)
                } catch (e: Exception) {
                    // уже убрано
                }
            }
            barView?.let { view ->
                try {
                    manager.removeView(view)
                } catch (e: Exception) {
                    // уже убрано
                }
            }
        }
        resultView = null
        barView = null
        langButton = null
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
        resultView?.clear()
        barView?.visibility = View.INVISIBLE

        try {
            worker.execute {
                try {
                    Thread.sleep(350)
                    val bitmap = if (useRoot) captureRoot() else captureProjectionBitmap()
                    if (bitmap == null) {
                        showMessage("Не удалось сделать снимок экрана. Нажмите «Перевод» ещё раз.")
                    } else {
                        recognizeAndTranslate(bitmap)
                    }
                } catch (e: InterruptedException) {
                    // служба останавливается
                } catch (e: Exception) {
                    showMessage("Ошибка: " + describe(e))
                } finally {
                    main.post { barView?.visibility = View.VISIBLE }
                    busy.set(false)
                }
            }
        } catch (e: RejectedExecutionException) {
            // служба уже останавливается
            barView?.visibility = View.VISIBLE
            busy.set(false)
        }
    }

    private fun showMessage(text: String) {
        main.post { resultView?.showMessage(text) }
    }

    private fun toast(text: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(this, text, Toast.LENGTH_LONG).show()
        } else {
            main.post { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
        }
    }

    // ---------------------------------------------------------------- снимок экрана

    /** Root-режим: снимок через команду su screencap. */
    private fun captureRoot(): Bitmap? {
        // stderr отбрасываем: если его не читать, переполненный буфер может «подвесить» процесс
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "screencap -p 2>/dev/null"))
        return try {
            process.outputStream.close()
            val bytes = process.inputStream.use { it.readBytes() }
            process.waitFor()
            if (bytes.isEmpty()) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } finally {
            process.destroy()
        }
    }

    /** Обычный режим: берём последний кадр записи экрана. */
    private fun captureProjectionBitmap(): Bitmap? {
        val bounds = (wm ?: return null).maximumWindowMetrics.bounds
        val resized = synchronized(captureLock) {
            if (projection == null) return null
            if (bounds.width() != capW || bounds.height() != capH) {
                // экран повернули — меняем размер приёмника кадров
                rebuildReaderLocked(bounds.width(), bounds.height())
                true
            } else {
                false
            }
        }
        if (resized) Thread.sleep(500)

        // ждём, пока придёт хотя бы один кадр (не дольше 2 секунд)
        var waited = 0
        while (waited < 2000) {
            val hasFrame = synchronized(captureLock) { latestImage != null }
            if (hasFrame) break
            Thread.sleep(50)
            waited += 50
        }

        return synchronized(captureLock) {
            latestImage?.let { imageToBitmap(it) }
        }
    }

    /** Переводит кадр записи экрана в Bitmap (с учётом выравнивания строк, см. FramePacker). */
    private fun imageToBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val src: ByteBuffer = plane.buffer.duplicate()
        src.rewind()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rowBytes = width * 4
        if (pixelStride == 4 && rowStride == rowBytes && src.remaining() >= rowBytes * height) {
            bitmap.copyPixelsFromBuffer(src)
        } else {
            bitmap.copyPixelsFromBuffer(FramePacker.pack(src, width, height, pixelStride, rowStride))
        }
        return bitmap
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val p = manager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("система не выдала доступ к записи экрана")
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                // запись экрана остановлена (пользователем, системой или при блокировке экрана)
                val current = synchronized(captureLock) { projection === p }
                if (current) stopSelf()
            }
        }
        // Android 14+: колбэк обязательно регистрировать до createVirtualDisplay
        p.registerCallback(callback, main)

        val thread = HandlerThread("capture")
        thread.start()
        val bounds = ensureWindowManager().maximumWindowMetrics.bounds
        synchronized(captureLock) {
            projection = p
            projectionCallback = callback
            captureThread = thread
            captureHandler = Handler(thread.looper)
            rebuildReaderLocked(bounds.width(), bounds.height())
        }
    }

    /** Создаёт (или пересоздаёт при повороте экрана) приёмник кадров. Вызывать под captureLock. */
    private fun rebuildReaderLocked(w: Int, h: Int) {
        val p = projection ?: return
        latestImage?.close()
        latestImage = null

        val old = imageReader
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r -> onFrameAvailable(r) }, captureHandler)

        val dpi = resources.displayMetrics.densityDpi
        try {
            val display = virtualDisplay
            if (display == null) {
                // Android 14+: у одного MediaProjection можно создать только один виртуальный
                // экран, поэтому при повороте дальше только меняем его размер.
                virtualDisplay = p.createVirtualDisplay(
                    "screen-translator",
                    w,
                    h,
                    dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    null,
                    null
                ) ?: throw IllegalStateException("не удалось создать виртуальный экран")
            } else {
                display.surface = reader.surface
                display.resize(w, h, dpi)
            }
        } catch (e: Exception) {
            reader.setOnImageAvailableListener(null, null)
            reader.close()
            throw e
        }
        imageReader = reader
        capW = w
        capH = h

        old?.setOnImageAvailableListener(null, null)
        old?.close()
    }

    /** Новый кадр записи экрана: храним только самый свежий. */
    private fun onFrameAvailable(reader: ImageReader) {
        synchronized(captureLock) {
            if (reader !== imageReader) return
            try {
                val image = reader.acquireLatestImage() ?: return
                latestImage?.close()
                latestImage = image
            } catch (e: Exception) {
                // приёмник могли закрыть во время смены размера — это нормально
            }
        }
    }

    /** Останавливает запись экрана и освобождает всё, что с ней связано. */
    private fun stopCapture() {
        val old: Pair<MediaProjection, MediaProjection.Callback?>? = synchronized(captureLock) {
            latestImage?.close()
            latestImage = null
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            imageReader = null
            captureThread?.quitSafely()
            captureThread = null
            captureHandler = null
            capW = 0
            capH = 0
            val p = projection
            val cb = projectionCallback
            projection = null
            projectionCallback = null
            if (p == null) null else Pair(p, cb)
        }
        if (old != null) {
            val (p, cb) = old
            try {
                // сначала отписываемся, чтобы остановка не закрыла саму службу
                if (cb != null) p.unregisterCallback(cb)
                p.stop()
            } catch (e: Exception) {
                // запись уже остановлена
            }
        }
    }

    // ---------------------------------------------------------------- распознавание и перевод

    private fun recognizerFor(lang: String): TextRecognizer {
        val key = when (lang) {
            TranslateLanguage.JAPANESE -> "ja"
            TranslateLanguage.CHINESE -> "zh"
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
        val current = translator
        if (current != null && key == translatorKey) return current
        current?.close()
        val created = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(src)
                .setTargetLanguage(tgt)
                .build()
        )
        translator = created
        translatorKey = key
        return created
    }

    /** Скачаны ли модели перевода (английская для ML Kit — «опорная», её не проверяем). */
    private fun modelsDownloaded(vararg codes: String): Boolean {
        return try {
            val manager = RemoteModelManager.getInstance()
            codes.filter { it != TranslateLanguage.ENGLISH }.all { code ->
                Tasks.await(manager.isModelDownloaded(TranslateRemoteModel.Builder(code).build()))
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun recognizeAndTranslate(bitmap: Bitmap) {
        val src = Prefs.source(this)
        val tgt = Prefs.target(this)
        val bitmapW = bitmap.width
        val bitmapH = bitmap.height

        val recognized = try {
            Tasks.await(recognizerFor(src).process(InputImage.fromBitmap(bitmap, 0)))
        } finally {
            bitmap.recycle()
        }

        // у китайского и японского нет пробелов между словами
        val noSpaces = src == TranslateLanguage.CHINESE || src == TranslateLanguage.JAPANESE
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
            if (!modelsDownloaded(src, tgt)) {
                showMessage("Скачиваю языковые модели (около 30 МБ каждая), подождите…")
            }
            try {
                Tasks.await(tr.downloadModelIfNeeded(DownloadConditions.Builder().build()))
            } catch (e: InterruptedException) {
                throw e
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
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    original
                }
                items.add(OverlayResultView.Item(block.boundingBox!!, translated))
            }
        }
        main.post { resultView?.showItems(items, bitmapW, bitmapH) }
    }

    private fun closeMlKit() {
        recognizers.values.forEach { recognizer ->
            try {
                recognizer.close()
            } catch (e: Exception) {
                // уже закрыт
            }
        }
        recognizers.clear()
        translator?.close()
        translator = null
        translatorKey = ""
    }

    // ---------------------------------------------------------------- жизненный цикл

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // после поворота экрана старый перевод уже не совпадает с картинкой
        resultView?.clear()
    }

    override fun onDestroy() {
        removeOverlays()
        stopCapture()
        // ML Kit закрываем в том же потоке, где им пользуемся, — после текущей задачи
        try {
            worker.execute { closeMlKit() }
        } catch (e: RejectedExecutionException) {
            // уже остановлен
        }
        worker.shutdown()
        super.onDestroy()
    }
}
